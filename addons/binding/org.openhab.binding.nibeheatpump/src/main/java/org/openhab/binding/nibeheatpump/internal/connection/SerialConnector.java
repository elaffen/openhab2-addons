/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TooManyListenersException;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.IOUtils;
import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.config.NibeHeatPumpConfiguration;
import org.openhab.binding.nibeheatpump.internal.message.ModbusReadRequestMessage;
import org.openhab.binding.nibeheatpump.internal.message.ModbusWriteRequestMessage;
import org.openhab.binding.nibeheatpump.internal.message.NibeHeatPumpMessage;
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import gnu.io.UnsupportedCommOperationException;

/**
 * Connector for serial port communication.
 *
 * @author Pauli Anttila - Initial contribution
 */
public class SerialConnector extends NibeHeatPumpBaseConnector {

    private static final Logger logger = LoggerFactory.getLogger(SerialConnector.class);

    private final int MAX_QUEUED_MSGS = 1;

    private InputStream in = null;
    private OutputStream out = null;
    private SerialPort serialPort = null;
    private Thread readerThread = null;
    private NibeHeatPumpConfiguration conf = null;

    private List<byte[]> readQueue = new ArrayList<byte[]>();
    private List<byte[]> writeQueue = new ArrayList<byte[]>();

    // state machine states
    private enum StateMachine {
        WAIT_START,
        WAIT_DATA,
        OK_MESSAGE_RECEIVED,
        WRITE_TOKEN_RECEIVED,
        READ_TOKEN_RECEIVED,
        CRC_FAILURE,
    };

    private static enum msgStatus {
        VALID,
        VALID_BUT_NOT_READY,
        INVALID
    }

    public SerialConnector() {

        logger.debug("Nibe heatpump Serial Port message listener started");
    }

    @Override
    public void connect(NibeHeatPumpConfiguration configuration) throws NibeHeatPumpException {
        if (isConnected()) {
            return;
        }

        conf = configuration;

        try {
            CommPortIdentifier portIdentifier = CommPortIdentifier.getPortIdentifier(conf.serialPort);

            CommPort commPort = portIdentifier.open(this.getClass().getName(), 2000);

            serialPort = (SerialPort) commPort;
            serialPort.setSerialPortParams(38400, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

            serialPort.enableReceiveThreshold(1);
            serialPort.disableReceiveTimeout();

            in = serialPort.getInputStream();
            out = serialPort.getOutputStream();

            out.flush();
            if (in.markSupported()) {
                in.reset();
            }
        } catch (NoSuchPortException e) {
            throw new NibeHeatPumpException("Connection failed, no such a port", e);
        } catch (PortInUseException e) {
            throw new NibeHeatPumpException("Connection failed, port in use", e);
        } catch (UnsupportedCommOperationException | IOException e) {
            throw new NibeHeatPumpException("Connection failed, reason: " + e.getMessage(), e);
        }

        readQueue.clear();
        writeQueue.clear();

        readerThread = new SerialReader(in);
        readerThread.start();
        connected = true;
    }

    @Override
    public void disconnect() throws NibeHeatPumpException {

        logger.debug("Disconnecting");

        if (readerThread != null) {
            logger.debug("Interrupt serial listener");
            readerThread.interrupt();
        }

        if (out != null) {
            logger.debug("Close serial out stream");
            IOUtils.closeQuietly(out);
        }
        if (in != null) {
            logger.debug("Close serial in stream");
            IOUtils.closeQuietly(in);
        }

        if (serialPort != null) {
            logger.debug("Close serial port");
            serialPort.close();
        }

        readerThread = null;
        serialPort = null;
        out = null;
        in = null;
        connected = false;
        logger.debug("Closed");
    }

    @Override
    public void sendDatagram(NibeHeatPumpMessage msg) throws NibeHeatPumpException {
        logger.debug("Sending request: {}", msg.toHexString());

        if (msg instanceof ModbusWriteRequestMessage) {
            if (writeQueue.size() >= MAX_QUEUED_MSGS) {
                logger.warn("Too many messages on write queue ({}), ignore message '{}'", writeQueue.size(),
                        msg.toString());
            } else {
                writeQueue.add(msg.decodeMessage());
                logger.debug("Write queue contains {} messages", writeQueue.size());
            }
        } else if (msg instanceof ModbusReadRequestMessage) {
            if (readQueue.size() >= MAX_QUEUED_MSGS) {
                logger.warn("Too many messages on read queue ({}), ignore message '{}'", readQueue.size(),
                        msg.toString());
            } else {
                readQueue.add(msg.decodeMessage());
                logger.debug("Read queue contains {} messages", readQueue.size());
            }
        } else {
            logger.debug("Ignore PDU: {}", msg.getClass().toString());
        }
    }

    public class SerialReader extends Thread implements SerialPortEventListener {
        boolean interrupted = false;
        InputStream in;

        public SerialReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void interrupt() {
            interrupted = true;
            super.interrupt();
            IOUtils.closeQuietly(in);
        }

        @Override
        public void run() {
            logger.debug("Data listener started");

            // RXTX serial port library causes high CPU load
            // Start event listener, which will just sleep and slow down event
            // loop
            try {
                serialPort.addEventListener(this);
                serialPort.notifyOnDataAvailable(true);
            } catch (TooManyListenersException e) {
                // TODO
            }

            try {
                final int dataBufferMaxLen = 100;
                ByteBuffer msg = ByteBuffer.allocate(dataBufferMaxLen);
                StateMachine state = StateMachine.WAIT_START;
                int b;

                while (interrupted != true) {

                    switch (state) {

                        case WAIT_START:
                            if ((b = in.read()) > -1) {
                                logger.trace("Received byte: {}", b);
                                if (b == NibeHeatPumpProtocol.FRAME_START_CHAR_FROM_NIBE) {
                                    logger.trace("Frame start found");
                                    msg.put((byte) b);
                                    state = StateMachine.WAIT_DATA;
                                }
                            }
                            break;

                        case WAIT_DATA:
                            if ((b = in.read()) > -1) {
                                if (msg.position() >= dataBufferMaxLen) {
                                    logger.trace("Too long message received");
                                    state = StateMachine.WAIT_START;
                                } else {
                                    msg.put((byte) b);

                                    try {
                                        msgStatus status = checkNibeMessage(msg.asReadOnlyBuffer());
                                        switch (status) {
                                            case INVALID:
                                                state = StateMachine.WAIT_START;
                                                break;
                                            case VALID:
                                                state = StateMachine.OK_MESSAGE_RECEIVED;
                                                break;
                                            case VALID_BUT_NOT_READY:
                                                break;
                                        }
                                    } catch (NibeHeatPumpException e) {
                                        state = StateMachine.CRC_FAILURE;
                                    }
                                }
                            }
                            break;

                        case CRC_FAILURE:
                            logger.trace("CRC failure");
                            try {
                                sendNakToNibe();
                            } catch (IOException e) {
                                sendErrorToListeners(e.getMessage());
                            }
                            state = StateMachine.WAIT_START;
                            break;

                        case OK_MESSAGE_RECEIVED:
                            logger.trace("OK message received");
                            msg.flip();
                            byte[] data = new byte[msg.remaining()];
                            msg.get(data, 0, data.length);

                            if (NibeHeatPumpProtocol.isModbus40ReadTokenPdu(data)) {
                                state = StateMachine.READ_TOKEN_RECEIVED;
                            }
                            if (NibeHeatPumpProtocol.isModbus40WriteTokenPdu(data)) {
                                state = StateMachine.WRITE_TOKEN_RECEIVED;
                            } else {
                                try {
                                    sendAckToNibe(msg.get(NibeHeatPumpProtocol.OFFSET_ADR));
                                } catch (IOException e) {
                                    sendErrorToListeners(e.getMessage());
                                }
                                sendMsgToListeners(data);
                                state = StateMachine.WAIT_START;
                            }
                            break;

                        case READ_TOKEN_RECEIVED:
                            logger.trace("Read token received");
                            try {
                                if (!readQueue.isEmpty()) {
                                    byte[] d = readQueue.remove(0);
                                    sendDataToNibe(d);
                                } else {
                                    sendAckToNibe(msg.get(NibeHeatPumpProtocol.OFFSET_ADR));
                                }
                            } catch (IOException e) {
                                sendErrorToListeners(e.getMessage());
                            }
                            state = StateMachine.WAIT_START;
                            break;

                        case WRITE_TOKEN_RECEIVED:
                            logger.trace("Write token received");
                            try {
                                if (!writeQueue.isEmpty()) {
                                    byte[] d = writeQueue.remove(0);
                                    sendDataToNibe(d);
                                } else {
                                    sendAckToNibe(msg.get(NibeHeatPumpProtocol.OFFSET_ADR));
                                }
                            } catch (IOException e) {
                                sendErrorToListeners(e.getMessage());
                            }
                            state = StateMachine.WAIT_START;
                            break;
                    }
                }
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted via InterruptedIOException");
            } catch (IOException e) {
                logger.error("Reading from serial port failed", e);
                sendErrorToListeners(e.getMessage());
            }

            serialPort.removeEventListener();
            logger.debug("Data listener stopped");
        }

        @Override
        public void serialEvent(SerialPortEvent arg0) {
            try {
                /*
                 * See more details from
                 * https://github.com/NeuronRobotics/nrjavaserial/issues/22
                 */
                logger.trace("RXTX library CPU load workaround, sleep forever");
                sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        }

    }

    /*
     * Throws NibeHeatPumpException when checksum fails
     */
    private static msgStatus checkNibeMessage(ByteBuffer byteBuffer) throws NibeHeatPumpException {
        byteBuffer.flip();
        int len = byteBuffer.remaining();

        if (len >= 1) {
            if (byteBuffer.get(0) != NibeHeatPumpProtocol.FRAME_START_CHAR_FROM_NIBE) {
                return msgStatus.INVALID;
            }

            if (len >= 2) {
                if (!(byteBuffer.get(1) == 0x00)) {
                    return msgStatus.INVALID;
                }
            }

            if (len >= 6) {
                int datalen = byteBuffer.get(NibeHeatPumpProtocol.OFFSET_LEN);

                // check if all bytes received
                if (len < datalen + 6) {
                    return msgStatus.VALID_BUT_NOT_READY;
                }

                // calculate XOR checksum
                byte calc_checksum = 0;
                for (int i = 2; i < (datalen + 5); i++) {
                    calc_checksum ^= byteBuffer.get(i);
                }

                byte msg_checksum = byteBuffer.get(datalen + 5);

                if (calc_checksum != msg_checksum) {

                    // if checksum is 0x5C (start character), heat pump seems to
                    // send 0xC5 checksum
                    if (calc_checksum != 0x5C && msg_checksum != 0xC5) {
                        throw new NibeHeatPumpException(
                                "Checksum failure, expected checksum " + msg_checksum + " was " + calc_checksum);
                    }
                }

                return msgStatus.VALID;
            }
        }

        return msgStatus.VALID_BUT_NOT_READY;
    }

    private void sendNakToNibe() throws IOException {
        // Do nothing
        /*
         * logger.debug("Send Nak");
         * out.write(0x15);
         * out.flush();
         */

    }

    private void sendAckToNibe(byte address) throws IOException {
        boolean sendack = false;

        if (address == NibeHeatPumpProtocol.ADR_MODBUS40 && conf.sendAckToMODBUS40) {
            sendack = true;
        } else if (address == NibeHeatPumpProtocol.ADR_SMS40 && conf.sendAckToSMS40) {
            sendack = true;
        } else if (address == NibeHeatPumpProtocol.ADR_RMU40 && conf.sendAckToRMU40) {
            sendack = true;
        }

        if (sendack) {
            sendAckToNibe();
        }
    }

    private void sendAckToNibe() throws IOException {
        logger.debug("Send Ack");
        out.write(0x06);
        out.flush();
    }

    private void sendDataToNibe(byte[] data) throws IOException {
        logger.trace("Sending data (len={}): {}", data.length, DatatypeConverter.printHexBinary(data));
        out.write(data);
        out.flush();
    }
}

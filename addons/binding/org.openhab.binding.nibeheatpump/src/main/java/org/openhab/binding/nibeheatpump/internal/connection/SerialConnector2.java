/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.connection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
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
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocolContext;
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocolDefaultContext;
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
public class SerialConnector2 extends NibeHeatPumpBaseConnector {

    private static final Logger logger = LoggerFactory.getLogger(SerialConnector2.class);

    private InputStream in = null;
    private OutputStream out = null;
    private SerialPort serialPort = null;
    private Thread readerThread = null;
    private NibeHeatPumpConfiguration conf = null;

    private List<byte[]> readQueue = new ArrayList<byte[]>();
    private List<byte[]> writeQueue = new ArrayList<byte[]>();

    public SerialConnector2() {

        logger.debug("Nibe heatpump Serial Port message listener created");
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
            writeQueue.add(msg.decodeMessage());
            logger.debug("Write queue: {} messages", writeQueue.size());
        } else if (msg instanceof ModbusReadRequestMessage) {
            readQueue.add(msg.decodeMessage());
            logger.debug("Read queue: {} messages", readQueue.size());
        } else {
            logger.debug("Ignore PDU: {}", msg.getClass().toString());
        }

        logger.debug("Read queue: {}, Write queue: {}", readQueue.size(), writeQueue.size());
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
            // Start event listener, which will just sleep and slow down event loop
            try {
                serialPort.addEventListener(this);
                serialPort.notifyOnDataAvailable(true);
            } catch (TooManyListenersException e) {
                logger.info("RXTX high CPU load workaround failed, reason {}", e.getMessage());
            }

            NibeHeatPumpProtocolContext context = new NibeHeatPumpProtocolDefaultContext() {
                @Override
                public void sendAck() {
                    try {
                        byte addr = msg().get(NibeHeatPumpProtocol.OFFSET_ADR);
                        sendAckToNibe(addr);
                    } catch (IOException e) {
                        sendErrorToListeners(e.getMessage());
                    }
                }

                @Override
                public void sendNak() {
                    // do nothing
                }

                @Override
                public void msgReceived(byte[] data) {
                    sendMsgToListeners(data);
                }

                @Override
                public void sendWriteMsg() {
                    try {
                        if (!readQueue.isEmpty()) {
                            sendDataToNibe(readQueue.remove(0));
                        } else {
                            // no messages to send, send ack to pump
                            byte addr = msg().get(NibeHeatPumpProtocol.OFFSET_ADR);
                            sendAckToNibe(addr);
                        }
                    } catch (IOException e) {
                        sendErrorToListeners(e.getMessage());
                    }
                }

                @Override
                public void sendReadMsg() {
                    try {
                        if (!readQueue.isEmpty()) {
                            sendDataToNibe(readQueue.remove(0));
                        } else {
                            // no messages to send, send ack to pump
                            byte addr = msg().get(NibeHeatPumpProtocol.OFFSET_ADR);
                            sendAckToNibe(addr);
                        }
                    } catch (IOException e) {
                        sendErrorToListeners(e.getMessage());
                    }
                }

                @Override
                public void log(String format) {
                    logger.trace(format);
                }

                @Override
                public void log(String format, Object... arg) {
                    logger.trace(format, arg);
                }
            };

            while (interrupted != true) {
                try {
                    final byte[] data = getAllAvailableBytes(in);
                    if (data != null) {
                        context.buffer().put(data);
                    }
                } catch (InterruptedIOException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted via InterruptedIOException");
                } catch (IOException e) {
                    logger.error("Reading from serial port failed", e);
                    sendErrorToListeners(e.getMessage());
                }

                // run state machine to process all received data
                while (context.state().process(context)) {
                    if (interrupted) {
                        break;
                    }
                }
            }

            serialPort.removeEventListener();
            logger.debug("Data listener stopped");
        }

        private byte[] getAllAvailableBytes(InputStream in) throws IOException {
            ByteArrayOutputStream os = new ByteArrayOutputStream();

            int b;
            // wait first byte (blocking)
            if ((b = in.read()) > -1) {
                byte d[] = new byte[] { (byte) b };
                os.write(d);

                // read rest available bytes
                byte[] buffer = new byte[100];
                int available = in.available();
                if (available > 0) {
                    int len = in.read(buffer, 0, available);
                    if (len > -1) {
                        os.write(buffer, 0, len);
                    }
                }

                os.flush();
                return os.toByteArray();
            }

            return null;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
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

    @SuppressWarnings("unused")
    private void sendNakToNibe() throws IOException {
        logger.debug("Send Nak");
        out.write(0x15);
        out.flush();
    }

    private void sendAckToNibe(byte address) throws IOException {
        boolean sendack = false;

        if (address == NibeHeatPumpProtocol.ADR_MODBUS40 && conf.sendAckToMODBUS40) {
            logger.debug("Send ack to MODBUS40 message");
            sendack = true;
        } else if (address == NibeHeatPumpProtocol.ADR_SMS40 && conf.sendAckToSMS40) {
            logger.debug("Send ack to SMS40 message");
            sendack = true;
        } else if (address == NibeHeatPumpProtocol.ADR_RMU40 && conf.sendAckToRMU40) {
            logger.debug("Send ack to RMU40 message");
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

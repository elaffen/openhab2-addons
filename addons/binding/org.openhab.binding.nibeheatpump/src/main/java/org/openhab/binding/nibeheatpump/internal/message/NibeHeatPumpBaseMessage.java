package org.openhab.binding.nibeheatpump.internal.message;

import javax.xml.bind.DatatypeConverter;

import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocol;

public abstract class NibeHeatPumpBaseMessage implements NibeHeatPumpMessage {

    public enum MessageType {
        MODBUS_DATA_READ_OUT_MSG(NibeHeatPumpProtocol.CMD_MODBUS_DATA_MSG),
        MODBUS_READ_REQUEST_MSG(NibeHeatPumpProtocol.CMD_MODBUS_READ_REQ),
        MODBUS_READ_RESPONSE_MSG(NibeHeatPumpProtocol.CMD_MODBUS_READ_RESP),
        MODBUS_WRITE_REQUEST_MSG(NibeHeatPumpProtocol.CMD_MODBUS_WRITE_REQ),
        MODBUS_WRITE_RESPONSE_MSG(NibeHeatPumpProtocol.CMD_MODBUS_WRITE_RESP),

        UNKNOWN(-1);

        private final int msgType;

        MessageType(int msgType) {
            this.msgType = msgType;
        }

        MessageType(byte msgType) {
            this.msgType = msgType;
        }

        public byte toByte() {
            return (byte) msgType;
        }

    }

    public byte[] rawMessage;
    public MessageType msgType = MessageType.UNKNOWN;
    public byte msgId = 0;

    public NibeHeatPumpBaseMessage() {

    }

    public NibeHeatPumpBaseMessage(byte[] data) throws NibeHeatPumpException {
        encodeMessage(data);
    }

    @Override
    public void encodeMessage(byte[] data) throws NibeHeatPumpException {
        data = NibeHeatPumpProtocol.checkMessageChecksumAndRemoveDoubles(data);
        rawMessage = data;

        msgType = MessageType.UNKNOWN;
        msgId = data[1];

        for (MessageType pt : MessageType.values()) {
            if (pt.toByte() == data[NibeHeatPumpProtocol.OFFSET_CMD]) {
                msgType = pt;
                break;
            }
        }
    }

    @Override
    public String toString() {
        String str = "";
        str += "Message type = " + msgType;
        return str;
    }

    @Override
    public String toHexString() {
        if (rawMessage == null) {
            return null;
        } else {
            return DatatypeConverter.printHexBinary(rawMessage);
        }
    }

}

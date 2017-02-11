package org.openhab.binding.nibeheatpump.internal.message;

import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocol;

public class ModbusWriteResponseMessage extends NibeHeatPumpBaseMessage {

    private boolean result = false;

    private ModbusWriteResponseMessage(MessageBuilder builder) {
        super.msgType = MessageType.MODBUS_WRITE_RESPONSE_MSG;
        this.result = builder.result;
    }

    public ModbusWriteResponseMessage(byte[] data) throws NibeHeatPumpException {
        encodeMessage(data);
    }

    @Override
    public void encodeMessage(byte[] data) throws NibeHeatPumpException {
        result = modbus40WriteSuccess(data);
    }

    public boolean isSuccessfull() {
        return result;
    }

    @Override
    public byte[] decodeMessage() {
        return createModbusWriteResponsePdu(result);
    }

    private byte[] createModbusWriteResponsePdu(boolean result) {
        byte[] data = new byte[7];

        data[0] = NibeHeatPumpProtocol.FRAME_START_CHAR_FROM_NIBE;
        data[1] = 0x00;
        data[2] = NibeHeatPumpProtocol.ADR_MODBUS40;
        data[3] = NibeHeatPumpProtocol.CMD_MODBUS_WRITE_RESP;
        data[4] = (byte) 0x01; // data len
        data[5] = result ? (byte) 0x01 : (byte) 0x00;
        data[6] = NibeHeatPumpProtocol.calculateChecksum(data, 2, 6);

        return data;
    }

    @Override
    public String toString() {
        String str = "";

        str += super.toString();
        str += ", Result = " + result;

        return str;
    }

    private boolean modbus40WriteSuccess(byte[] data) throws NibeHeatPumpException {
        if (NibeHeatPumpProtocol.isModbus40WriteResponsePdu(data)) {
            super.encodeMessage(data);
            if (data[NibeHeatPumpProtocol.OFFSET_DATA] == 1) {
                return true;
            }
        } else {
            throw new NibeHeatPumpException("Not Write Response message");
        }

        return false;
    }

    public static class MessageBuilder {
        private boolean result;

        public MessageBuilder result(boolean result) {
            this.result = result;
            return this;
        }

        public ModbusWriteResponseMessage build() {
            return new ModbusWriteResponseMessage(this);
        }
    }
}

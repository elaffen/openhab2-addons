package org.openhab.binding.nibeheatpump.internal.protocol;

import java.nio.ByteBuffer;

public class NibeHeatPumpProtocolDefaultContext implements NibeHeatPumpProtocolContext {
    NibeHeatPumpProtocolStates state = NibeHeatPumpProtocolStates.WAIT_START;
    ByteBuffer buffer = ByteBuffer.allocate(1000);
    ByteBuffer msg = ByteBuffer.allocate(100);

    @Override
    public NibeHeatPumpProtocolStates state() {
        return this.state;
    }

    @Override
    public void state(NibeHeatPumpProtocolStates state) {
        this.state = state;
    }

    @Override
    public ByteBuffer buffer() {
        return buffer;
    }

    @Override
    public ByteBuffer msg() {
        return msg;
    }

    @Override
    public void sendAck() {
    }

    @Override
    public void sendNak() {
    }

    @Override
    public void msgReceived(byte[] data) {
    }

    @Override
    public void sendWriteMsg() {
    }

    @Override
    public void sendReadMsg() {
    }

    @Override
    public void log(String format) {
    }

    @Override
    public void log(String format, Object... arg) {
    }
}

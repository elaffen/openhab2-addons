package org.openhab.binding.nibeheatpump.internal.protocol;

import java.nio.ByteBuffer;

public interface NibeHeatPumpProtocolContext {
    void log(String format);

    void log(String format, Object... arg);

    ByteBuffer buffer();

    ByteBuffer msg();

    NibeHeatPumpProtocolStates state();

    void state(NibeHeatPumpProtocolStates state);

    void sendAck();

    void sendNak();

    void sendWriteMsg();

    void sendReadMsg();

    void msgReceived(byte[] data);
}

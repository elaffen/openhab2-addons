package org.openhab.binding.nibeheatpump.internal.protocol;

public interface NibeHeatPumpProtocolState {
    /**
     * @return true to keep processing, false to read more data.
     */
    boolean process(NibeHeatPumpProtocolContext context);
}

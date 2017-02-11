package org.openhab.binding.nibeheatpump.internal.message;

import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;

public interface NibeHeatPumpMessage {

    /**
     * Procedure for present class information in string format. Used for
     * logging purposes.
     *
     */
    @Override
    String toString();

    /**
     * Procedure for encode raw data.
     *
     * @param data
     *            Raw data.
     */
    void encodeMessage(byte[] data) throws NibeHeatPumpException;

    /**
     * Procedure for decode object to raw data.
     *
     * @return raw data.
     */
    byte[] decodeMessage();

    /**
     * Procedure to covert message to hex string format. Used for
     * logging purposes.
     *
     */
    String toHexString();
}

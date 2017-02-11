/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.config;

/**
 * Configuration class for {@link RfxcomBinding} device.
 *
 * @author Pauli Anttila - Initial contribution
 */

public class NibeHeatPumpConfiguration {
    public static final String HOST_NAME = "hostName";
    public static final String UDP_PORT = "port";
    public static final String SERIAL_PORT = "serialPort";
    public static final String REFRESH_INTERVAL = "refreshInterval";
    public static final String ENABLE_READ_COMMANDS = "enableReadCommands";
    public static final String ENABLE_WRITE_COMMANDS = "enableWriteCommands";
    public static final String ENABLE_ACK_MODBUS40 = "sendAckToMODBUS40";
    public static final String ENABLE_ACK_RMU40 = "sendAckToRMU40";
    public static final String ENABLE_ACK_SM40 = "sendAckToSM40";

    public String hostName;
    public int port;
    public String serialPort;
    public int refreshInterval;
    public boolean enableReadCommands;
    public boolean enableWriteCommands;
    public boolean sendAckToMODBUS40;
    public boolean sendAckToRMU40;
    public boolean sendAckToSMS40;

    @Override
    public String toString() {
        String str = "";

        str += "hostName = " + hostName;
        str += ", port = " + port;
        str += ", serialPort = " + serialPort;
        str += ", refreshInterval = " + refreshInterval;
        str += ", enableReadCommands = " + enableReadCommands;
        str += ", enableWriteCommands = " + enableWriteCommands;
        str += ", sendAckToMODBUS40 = " + sendAckToMODBUS40;
        str += ", sendAckToRMU40 = " + sendAckToRMU40;
        str += ", sendAckToSMS40 = " + sendAckToSMS40;

        return str;
    }
}
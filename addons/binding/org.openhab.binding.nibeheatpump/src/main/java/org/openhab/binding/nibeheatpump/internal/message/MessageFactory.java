/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.message;

import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.message.NibeHeatPumpBaseMessage.MessageType;
import org.openhab.binding.nibeheatpump.internal.protocol.NibeHeatPumpProtocol;

public class MessageFactory {

    public static NibeHeatPumpMessage getMessage(byte[] message) throws NibeHeatPumpException {

        if (message != null) {

            byte messageTypeByte;

            if (message[NibeHeatPumpProtocol.OFFSET_START] == NibeHeatPumpProtocol.FRAME_START_CHAR_FROM_NIBE) {
                messageTypeByte = message[NibeHeatPumpProtocol.OFFSET_CMD];
            } else if (message[NibeHeatPumpProtocol.OFFSET_START] == NibeHeatPumpProtocol.FRAME_START_CHAR_TO_NIBE) {
                messageTypeByte = message[1];
            } else {
                throw new NibeHeatPumpException("Message not implemented");
            }

            MessageType messageType = getMessageType(messageTypeByte);

            switch (messageType) {
                case MODBUS_DATA_READ_OUT_MSG:
                    return new ModbusDataReadOutMessage(message);
                case MODBUS_READ_REQUEST_MSG:
                    return new ModbusReadRequestMessage(message);
                case MODBUS_READ_RESPONSE_MSG:
                    return new ModbusReadResponseMessage(message);
                case MODBUS_WRITE_REQUEST_MSG:
                    return new ModbusWriteRequestMessage(message);
                case MODBUS_WRITE_RESPONSE_MSG:
                    return new ModbusWriteResponseMessage(message);
                case UNKNOWN:
                    break;
            }

            throw new NibeHeatPumpException("Message not implemented");
        }

        throw new NibeHeatPumpException("Illegal message (null)");
    }

    private static MessageType getMessageType(byte messageType) {
        for (MessageType p : MessageType.values()) {
            if (p.toByte() == messageType) {
                return p;
            }
        }
        return MessageType.UNKNOWN;
    }
}

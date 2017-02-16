/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.connection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.message.MessageFactory;
import org.openhab.binding.nibeheatpump.internal.message.NibeHeatPumpMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class NibeHeatPumpBaseConnector implements NibeHeatPumpConnector {
    private static final Logger logger = LoggerFactory.getLogger(NibeHeatPumpBaseConnector.class);

    private List<NibeHeatPumpEventListener> listeners = new ArrayList<NibeHeatPumpEventListener>();
    public boolean connected = false;

    @Override
    public synchronized void addEventListener(NibeHeatPumpEventListener listener) {
        if (!listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    @Override
    public synchronized void removeEventListener(NibeHeatPumpEventListener listener) {
        this.listeners.remove(listener);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    public void sendMsgToListeners(byte[] data) {
        try {
            NibeHeatPumpMessage msg = MessageFactory.getMessage(data);
            sendMsgToListeners(msg);
        } catch (NibeHeatPumpException e) {
            logger.error("Invalid message received, exception {}", e.getMessage());
        }
    }

    public void sendMsgToListeners(NibeHeatPumpMessage msg) {
        try {
            Iterator<NibeHeatPumpEventListener> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                iterator.next().msgReceived(msg);
            }
        } catch (Exception e) {
            logger.error("Event listener invoking error, exception {}", e.getMessage());
        }
    }

    public void sendErrorToListeners(String error) {
        try {
            Iterator<NibeHeatPumpEventListener> iterator = listeners.iterator();
            while (iterator.hasNext()) {
                iterator.next().errorOccured(error);
            }
        } catch (Exception e) {
            logger.error("Event listener invoking error, exception {}", e.getMessage());
        }
    }
}
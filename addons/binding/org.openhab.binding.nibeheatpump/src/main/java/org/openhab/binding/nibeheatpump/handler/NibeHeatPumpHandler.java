/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.handler;

import static org.openhab.binding.nibeheatpump.NibeHeatPumpBindingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpCommandResult;
import org.openhab.binding.nibeheatpump.internal.NibeHeatPumpException;
import org.openhab.binding.nibeheatpump.internal.config.NibeHeatPumpConfiguration;
import org.openhab.binding.nibeheatpump.internal.connection.NibeHeatPumpConnector;
import org.openhab.binding.nibeheatpump.internal.connection.NibeHeatPumpEventListener;
import org.openhab.binding.nibeheatpump.internal.connection.SerialConnector2;
import org.openhab.binding.nibeheatpump.internal.connection.SimulatorConnector;
import org.openhab.binding.nibeheatpump.internal.connection.UDPConnector;
import org.openhab.binding.nibeheatpump.internal.message.ModbusDataReadOutMessage;
import org.openhab.binding.nibeheatpump.internal.message.ModbusReadRequestMessage;
import org.openhab.binding.nibeheatpump.internal.message.ModbusReadResponseMessage;
import org.openhab.binding.nibeheatpump.internal.message.ModbusValue;
import org.openhab.binding.nibeheatpump.internal.message.ModbusWriteRequestMessage;
import org.openhab.binding.nibeheatpump.internal.message.ModbusWriteResponseMessage;
import org.openhab.binding.nibeheatpump.internal.message.NibeHeatPumpMessage;
import org.openhab.binding.nibeheatpump.internal.models.PumpModel;
import org.openhab.binding.nibeheatpump.internal.models.VariableInformation;
import org.openhab.binding.nibeheatpump.internal.models.VariableInformation.NibeDataType;
import org.openhab.binding.nibeheatpump.internal.models.VariableInformation.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NibeHeatPumpHandler} is responsible for handling commands, which
 * are sent to one of the channels.
 *
 * @author Pauli Anttila - Initial contribution
 */
public class NibeHeatPumpHandler extends BaseThingHandler implements NibeHeatPumpEventListener {

    private Logger logger = LoggerFactory.getLogger(NibeHeatPumpHandler.class);

    private PumpModel pumpModel = PumpModel.F1245;
    private NibeHeatPumpConfiguration configuration;

    private NibeHeatPumpConnector connector = null;
    private int timeout = 4500;

    private boolean reconnectionRequest = false;

    private NibeHeatPumpCommandResult writeResult;
    private NibeHeatPumpCommandResult readResult;

    private ScheduledFuture<?> connectorTask;
    private ScheduledFuture<?> pollingJob;
    private List<Integer> itemsToPoll = new ArrayList<Integer>();

    private Map<Integer, CacheObject> stateMap = Collections.synchronizedMap(new HashMap<Integer, CacheObject>());

    protected class CacheObject {
        long lastUpdateTime;
        Double value;

        CacheObject(long lastUpdateTime, Double value) {
            this.lastUpdateTime = lastUpdateTime;
            this.value = value;
        }
    }

    public NibeHeatPumpHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received channel: {}, command: {}", channelUID, command);

        int coilAddress = parseCoildAddressFromChannelUID(channelUID);

        if (command.equals(RefreshType.REFRESH)) {
            logger.debug("Clearing cache value for channel '{}' to refresh channel data", channelUID);
            clearCache(coilAddress);
            return;
        }

        if (configuration.enableWriteCommands == false) {
            logger.debug("All write commands denied, ignoring command!");
            return;
        }

        if (connector != null) {

            VariableInformation variableInfo = VariableInformation.getVariableInfo(pumpModel, coilAddress);

            logger.debug("Usig variable information to coil address {}: {}", coilAddress, variableInfo);

            if (variableInfo != null && variableInfo.type == VariableInformation.Type.Setting) {

                int value = convertStateToNibeValue(command);
                value = value * variableInfo.factor;

                ModbusWriteRequestMessage msg = new ModbusWriteRequestMessage.MessageBuilder().coilAddress(coilAddress)
                        .value(value).build();

                logger.debug("Sending message: {}", msg.toString());

                try {
                    writeResult = sendMessageToNibe(msg);
                    ModbusWriteResponseMessage result = (ModbusWriteResponseMessage) writeResult.get(timeout,
                            TimeUnit.MILLISECONDS);
                    if (result != null) {
                        if (result.isSuccessfull()) {
                            logger.debug("Write message sending to heat pump succeeded");

                        } else {
                            logger.error("Message sending to heat pump failed, value not accepted by the heat pump");
                        }
                    }
                } catch (TimeoutException e) {
                    logger.error("Message sending to heat pump failed, no response");
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                } catch (InterruptedException e) {
                    logger.error("Message sending to heat pump failed, sending interrupted");
                } catch (NibeHeatPumpException e) {
                    logger.error("Message sending to heat pump failed, exception {}", e.getMessage());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                } catch (ExecutionException e) {
                    logger.error("Message sending to heat pump failed, exception {}", e.getMessage());
                } finally {
                    writeResult = null;
                }

                // Clear cache value to refresh coil data from the pump.
                // We might not know if write message have succeed or not, so let's always refresh it.
                logger.debug("Clearing cache value for channel '{}' to refresh channel data", channelUID);
                clearCache(coilAddress);

            } else {
                logger.error("Command to channel '{}' rejected, because item is read only parameter", channelUID);
            }
        } else {
            logger.warn("No connection to heat pump");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("channelLinked: {}", channelUID);

        // Add channel to polling loop
        int coilAddress = parseCoildAddressFromChannelUID(channelUID);
        synchronized (itemsToPoll) {
            itemsToPoll.add(coilAddress);
        }
        clearCache(coilAddress);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        logger.debug("channelUnlinked: {}", channelUID);

        // remove channel from polling loop
        int coilAddress = parseCoildAddressFromChannelUID(channelUID);
        synchronized (itemsToPoll) {
            itemsToPoll.remove(coilAddress);
        }
    }

    private int parseCoildAddressFromChannelUID(ChannelUID channelUID) {
        if (channelUID.getId().contains("#")) {
            String[] parts = channelUID.getId().split("#");
            return Integer.parseInt(parts[parts.length - 1]);
        } else {
            return Integer.parseInt(channelUID.getId());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize() {
        configuration = getConfigAs(NibeHeatPumpConfiguration.class);

        logger.debug("Initialized Nibe Heat Pump device handler for {}", getThing().getUID());
        logger.info("Using configuration: {}", configuration.toString());

        try {
            pumpModel = PumpModel.getPumpModel(thing.getThingTypeUID().getId().toString());
        } catch (IllegalArgumentException e) {
            logger.warn("Illegal pump model '{}', using default model '{}'", thing.getThingTypeUID().toString(),
                    pumpModel);
        }

        if (thing.getThingTypeUID().equals(THING_TYPE_F1245_UDP)) {
            connector = new UDPConnector();
        } else if (thing.getThingTypeUID().equals(THING_TYPE_F1245_SERIAL)) {
            connector = new SerialConnector2();
        } else if (thing.getThingTypeUID().equals(THING_TYPE_F1245_SIMULATOR)) {
            connector = new SimulatorConnector();
        }

        clearCache();

        if (connectorTask == null || connectorTask.isCancelled()) {
            connectorTask = scheduler.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    if (reconnectionRequest) {
                        logger.debug("Restarting requested, restarting...");
                        reconnectionRequest = false;
                        closeConnection();
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                    }

                    logger.debug("Checking Nibe Heat pump connection, thing status = {}", thing.getStatus());
                    connect();
                }
            }, 0, 10, TimeUnit.SECONDS);
        }
    }

    private void connect() {
        if (!connector.isConnected()) {
            logger.debug("Connecting to heat pump");
            try {
                connector.addEventListener(this);
                connector.connect(configuration);
                updateStatus(ThingStatus.ONLINE);

                if (pollingJob == null || pollingJob.isCancelled()) {
                    logger.debug("Start refresh task, interval={}sec", 1);
                    pollingJob = scheduler.scheduleAtFixedRate(pollingRunnable, 0, 1, TimeUnit.SECONDS);
                }
            } catch (NibeHeatPumpException e) {
                logger.error("Error occured when connecting to heat pump, exception {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
            }
        } else {
            logger.debug("Connecting to heat pump already open");
        }
    }

    @Override
    public void dispose() {
        logger.debug("Thing {} disposed.", getThing().getUID());

        closeConnection();

        if (connectorTask != null && !connectorTask.isCancelled()) {
            connectorTask.cancel(true);
            connectorTask = null;
        }
    }

    private void closeConnection() {
        logger.debug("Closing connection to the heat pump");
        if (connector != null) {
            connector.removeEventListener(this);
            try {
                connector.disconnect();
            } catch (NibeHeatPumpException e) {
                logger.error("Error occured when disconnecting form heat pump, exception {}", e.getMessage());
            }
        }

        if (pollingJob != null && !pollingJob.isCancelled()) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
    }

    private Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {

            if (configuration.enableReadCommands == false) {
                logger.debug("All read commands denied, skip polling!");
                return;
            }

            List<Integer> items = null;
            synchronized (itemsToPoll) {
                items = new ArrayList<Integer>(itemsToPoll);
            }

            for (int item : items) {
                if (connector != null && connector.isConnected()) {

                    CacheObject oldValue = stateMap.get(item);
                    if (oldValue == null
                            || (oldValue.lastUpdateTime + configuration.refreshInterval) < System.currentTimeMillis()
                                    / 1000) {

                        // it's time to refresh data
                        logger.debug("Query coil address '{}'", item);

                        ModbusReadRequestMessage msg = new ModbusReadRequestMessage.MessageBuilder().coilAddress(item)
                                .build();

                        try {
                            readResult = sendMessageToNibe(msg);
                            ModbusReadResponseMessage result = (ModbusReadResponseMessage) readResult.get(timeout,
                                    TimeUnit.MILLISECONDS);
                            if (result != null) {
                                handleVariableUpdate(pumpModel, result.getValueAsModbusValue());
                            }
                        } catch (TimeoutException e) {
                            logger.error("Message sending to heat pump failed, no response");
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                        } catch (InterruptedException e) {
                            logger.error("Message sending to heat pump failed, sending interrupted");
                        } catch (NibeHeatPumpException e) {
                            logger.error("Message sending to heat pump failed, exception {}", e.getMessage());
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                        } catch (ExecutionException e) {
                            logger.error("Message sending to heat pump failed, exception {}", e.getMessage());
                        } finally {
                            readResult = null;
                        }
                    } else {
                        logger.trace("Skip query to coil address '{}'", item);
                    }
                } else {
                    logger.warn("No connection to heat pump");
                }
            }
        }
    };

    private int convertStateToNibeValue(Command command) {
        int value;

        if (command instanceof OnOffType) {
            value = command == OnOffType.ON ? 1 : 0;
        } else {
            value = Integer.parseInt(command.toString());
        }

        return value;
    }

    private State convertNibeValueToState(NibeDataType dataType, double value, String acceptedItemType) {
        State state = UnDefType.UNDEF;

        if ("String".equalsIgnoreCase(acceptedItemType)) {
            state = new StringType(String.valueOf((int) value));

        } else if ("Switch".equalsIgnoreCase(acceptedItemType)) {
            state = value == 0 ? OnOffType.OFF : OnOffType.ON;

        } else if ("Number".equalsIgnoreCase(acceptedItemType)) {
            switch (dataType) {
                case U8:
                case U16:
                case U32:
                    state = new DecimalType(value);
                    break;
                case S8:
                case S16:
                case S32:
                    BigDecimal bd = new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN);
                    state = new DecimalType(bd);
                    break;
            }
        }

        return state;
    }

    private void clearCache() {
        stateMap.clear();
    }

    private void clearCache(int coilAddress) {
        stateMap.put(coilAddress, null);
    }

    private synchronized NibeHeatPumpCommandResult sendMessageToNibe(NibeHeatPumpMessage msg)
            throws NibeHeatPumpException {
        connector.sendDatagram(msg);
        return new NibeHeatPumpCommandResult();
    }

    @Override
    public void msgReceived(NibeHeatPumpMessage msg) {
        logger.debug("Received raw data: {}", msg.toHexString());
        logger.debug("Received message: {}", msg.toString());

        updateStatus(ThingStatus.ONLINE);

        if (msg instanceof ModbusReadResponseMessage) {
            handleReadResponseMessage((ModbusReadResponseMessage) msg);
        } else if (msg instanceof ModbusWriteResponseMessage) {
            handleWriteResponseMessage((ModbusWriteResponseMessage) msg);
        } else if (msg instanceof ModbusDataReadOutMessage) {
            handleDataReadOutMessage((ModbusDataReadOutMessage) msg);
        } else {
            logger.debug("Received unknow message: {}", msg.toString());
        }
    }

    @Override
    public void errorOccured(String error) {
        logger.debug("Error '{}' occured, re-establish the connection", error);
        reconnectionRequest = true;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
    }

    private void handleReadResponseMessage(ModbusReadResponseMessage msg) {
        logger.debug("Read response received");
        if (readResult != null) {
            readResult.set(msg);
        }
    }

    private void handleWriteResponseMessage(ModbusWriteResponseMessage msg) {
        logger.debug("Write response received");
        if (writeResult != null) {
            writeResult.set(msg);
        }
    }

    private void handleDataReadOutMessage(ModbusDataReadOutMessage msg) {
        logger.debug("Data readout received");

        List<ModbusValue> regValues = msg.getValues();

        if (regValues != null) {
            for (ModbusValue val : regValues) {
                handleVariableUpdate(pumpModel, val);
            }
        }
    }

    private void handleVariableUpdate(PumpModel pumpModel, ModbusValue value) {
        logger.debug("Received variable update: {}", value);
        int coilAddress = value.getCoilAddress();

        VariableInformation variableInfo = VariableInformation.getVariableInfo(pumpModel, coilAddress);

        if (variableInfo == null) {
            logger.debug("Unknown variable {}", coilAddress);
        } else {
            logger.debug("Usig variable information to coil address {}: {}", coilAddress, variableInfo);

            double val = (double) value.getValue() / (double) variableInfo.factor;
            logger.debug("{} = {}", coilAddress + ":" + variableInfo.variable, val);

            CacheObject oldValue = stateMap.get(coilAddress);

            if (oldValue != null && val == oldValue.value) {
                logger.trace("Value haven't been changed, ignore update");
            } else {
                stateMap.put(coilAddress, new CacheObject(System.currentTimeMillis() / 1000, val));

                final String channelPrefix = (variableInfo.type == Type.Setting ? "setting#" : "sensor#");
                final String channelId = channelPrefix + String.valueOf(coilAddress);
                final String acceptedItemType = thing.getChannel(channelId).getAcceptedItemType();

                logger.debug("AcceptedItemType for channel {} = {}", channelId, acceptedItemType);
                State state = convertNibeValueToState(variableInfo.dataType, val, acceptedItemType);
                updateState(new ChannelUID(getThing().getUID(), channelId), state);
            }
        }
    }
}
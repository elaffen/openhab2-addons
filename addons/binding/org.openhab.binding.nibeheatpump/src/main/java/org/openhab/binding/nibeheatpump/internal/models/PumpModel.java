/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.models;

/**
 * Class for
 *
 * @author Pauli Anttila - Initial contribution
 */
public enum PumpModel {
    F1145("F1145"),
    F1245("F1245");

    private final String pumpModel;

    private PumpModel(String pumpModel) {
        this.pumpModel = pumpModel;
    }

    @Override
    public String toString() {
        return pumpModel;
    }

    public static PumpModel getPumpModel(String pumpModel) throws IllegalArgumentException {

        for (PumpModel c : PumpModel.values()) {
            if (c.pumpModel.equalsIgnoreCase(pumpModel)) {
                return c;
            }
        }

        throw new IllegalArgumentException("Not valid pump model");
    }
}

/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nibeheatpump.internal.message;

public class ModbusValue {

    private int coilAddress;
    private int value;

    public ModbusValue(int coilAddress, int value) {
        this.coilAddress = coilAddress;
        this.value = value;
    }

    public int getCoilAddress() {
        return coilAddress;
    }

    public void setCoilAddress(int coilAddress) {
        this.coilAddress = coilAddress;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        String str = "{";

        str += "Coil address = " + coilAddress;
        str += ", Value = " + value;
        str += "}";

        return str;
    }
}

package org.openhab.binding.nibeheatpump.internal.models;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class VariableInformationTest {

    @Before
    public void Before() {
    }

    @Test
    public void TestF1245Variable() {
        final int coilAddress = 40004;
        final VariableInformation variableInfo = VariableInformation.getVariableInfo(PumpModel.F1245, coilAddress);
        assertEquals(10, variableInfo.factor);
        assertEquals("BT1 Outdoor temp", variableInfo.variable);
        assertEquals(VariableInformation.NibeDataType.S16, variableInfo.dataType);
        assertEquals(VariableInformation.Type.Sensor, variableInfo.type);
    }
}

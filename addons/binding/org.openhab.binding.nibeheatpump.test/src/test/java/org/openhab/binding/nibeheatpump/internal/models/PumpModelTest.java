package org.openhab.binding.nibeheatpump.internal.models;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class PumpModelTest {

    @Before
    public void Before() {
    }

    @Test
    public void TestF1245() {
        final String pumpModelString = "F1245";
        final PumpModel pumpModel = PumpModel.getPumpModel(pumpModelString);
        assertEquals(PumpModel.F1245, pumpModel);
    }

    @Test
    public void TestF1145() {
        final String pumpModelString = "F1145";
        final PumpModel pumpModel = PumpModel.getPumpModel(pumpModelString);
        assertEquals(PumpModel.F1145, pumpModel);
    }

    @Test
    public void badPumpModelTest() {
        final String pumpModelString = "XXXX";
        try {
            @SuppressWarnings("unused")
            final PumpModel pumpModel = PumpModel.getPumpModel(pumpModelString);
            fail("Method didn't throw IllegalArgumentException when expected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().equalsIgnoreCase("Not valid pump model"));
        }
    }
}

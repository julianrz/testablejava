/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package timer;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TestTimer {

    @Test
    public void testRate_InduceCondition() throws Exception {
        //Timer calls System.currentTimeMillis() twice, before and after timed operation
        //There is a small chance that the call will return the same number, in which
        // case timer.rate() SHOULD return Infinite (by contract)
        //How to reliably induce and test this condition? Even with an empty operation
        //once in a while the result will NOT be Infinite, which will ruin the test!

        Timer timer = new Timer();

        Runnable testRun = () -> {
            //do nothing, trying to make elapsed time = 0
        };

        //give it a try to see if this works reliably:

        for (int i=0;i<10000;i++) {
            double actual = timer.rate(testRun, 1);
            if (!Double.isInfinite(actual)) {
                System.out.println("proved that sometimes rate returns not Infinite on an empty operation!");
                break;
            }
        }
        //so the following statement will sometimes fail even though rate() is performing correctly
        assertTrue(Double.isInfinite(timer.rate(testRun, 1)));

        //instead, with TestableJava, you can induce condition where consecutive calls to System.currentTimeMills
        // will return the same value!

        timer.$$System$currentTimeMillis = (ctx) -> 42384723984L;

        assertTrue(Double.isInfinite(timer.rate(testRun, 1)));
    }
}

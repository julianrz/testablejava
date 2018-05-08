/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package timer;

public class Timer {
    public double rate(Runnable operation, int count) {
        long t0 = System.currentTimeMillis();

        for (int i = 0; i < count; i++)
            operation.run();

        long t1 = System.currentTimeMillis();
        return count * 1000.0 / (t1 - t0);
    }

}

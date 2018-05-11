/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package multiplier;

import org.junit.Test;
import testablejava.Helpers;

public class MultiplierTest {
    @Test(expected = NumberFormatException.class)
    public void multiplyDoublesAsStrings() throws Exception {
        //we know that Double.parseDouble sometimes fails and want to see how Multiplier deals with that
        //but we do not want to invent unparsable strings
        Multiplier multiplier = new Multiplier();

        //make Double.parseDouble(String) throw
        Multiplier.$$Double$parseDouble$$String = (ctx, s) -> {
            Helpers.uncheckedThrow(new NumberFormatException());
            return null;
        };
        //make a call that has parseDouble as a dependency, it should throw (propagate exception from parseDouble)
        multiplier.multiplyDoublesAsStrings("1","2");
    }

}
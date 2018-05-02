/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

public class X {
    String fnUsingNew(String text){return new String(text);}
    int fnUsingExternalCall(String text){return Integer.parseInt(text);}
}

/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.jdt.internal.compiler;

public enum InstrumentationOptions {INSERT_REDIRECTORS, INSERT_LISTENERS;
    public static final InstrumentationOptions[] ALL = {INSERT_REDIRECTORS, INSERT_LISTENERS};
}

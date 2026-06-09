/**
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.io.rest.logs.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Data Transfer Object representing log file metadata.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogFileInfo {

    private final String name;
    private final long size;
    private final String lastModified;

    public LogFileInfo(String name, long size, String lastModified) {
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public String getLastModified() {
        return lastModified;
    }
}

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

import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Response wrapper DTO for the file listing endpoint.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogFilesResult {

    private final String logDirectory;
    private final List<LogFileInfo> files;

    public LogFilesResult(String logDirectory, List<LogFileInfo> files) {
        this.logDirectory = logDirectory;
        this.files = files;
    }

    public String getLogDirectory() {
        return logDirectory;
    }

    public List<LogFileInfo> getFiles() {
        return files;
    }
}

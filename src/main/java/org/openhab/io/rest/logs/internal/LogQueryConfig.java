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
 * Configuration for the Log Query service, populated via OSGi ConfigAdmin.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@NonNullByDefault
public class LogQueryConfig {

    /** Maximum number of lines returned per tail request */
    public int maxLines = 1000;

    /** Maximum entries returned by search endpoint */
    public int maxSearchResults = 1000;

    /** Comma-separated glob patterns for allowed log files */
    public String allowedFiles = "openhab.log*,events.log*";

    /** Maximum time in milliseconds for regex pattern matching per file */
    public int regexTimeoutMs = 5000;
}

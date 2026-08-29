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

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.auth.Role;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * JAX-RS REST resource providing log file query endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /rest/logs} — Tail recent log entries</li>
 *   <li>{@code GET /rest/logs/search} — Search by regex pattern</li>
 *   <li>{@code GET /rest/logs/files} — List available log files</li>
 * </ul>
 *
 * <p>All endpoints require admin role.
 *
 * @author openHAB Log Query Add-on contributors - Initial contribution
 */
@Component
@JaxrsResource
@JaxrsName("logs")
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "=" + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path("logs")
@RolesAllowed({ Role.ADMIN })
@SecurityRequirement(name = "oauth2", scopes = { "admin" })
@Tag(name = "logs")
@NonNullByDefault
public class LogQueryResource implements RESTResource {

    private final LogFileService logFileService;

    @Activate
    public LogQueryResource(@Reference LogFileService logFileService) {
        this.logFileService = logFileService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getLogEntries",
            summary = "Get recent log entries (tail)",
            responses = {
                    @ApiResponse(responseCode = "200", description = "OK"),
                    @ApiResponse(responseCode = "400", description = "Invalid parameters"),
                    @ApiResponse(responseCode = "404", description = "Log file not found")
            })
    public Response getLogEntries(
            @QueryParam("file") @DefaultValue("openhab.log")
            @Parameter(description = "Log file name (basename only)") String file,
            @QueryParam("lines") @DefaultValue("100")
            @Parameter(description = "Number of lines to return (max 1000)") int lines,
            @QueryParam("level")
            @Parameter(description = "Minimum log level filter (ERROR, WARN, INFO, DEBUG, TRACE)") @Nullable String level,
            @QueryParam("logger")
            @Parameter(description = "Logger name substring filter") @Nullable String logger,
            @QueryParam("since")
            @Parameter(description = "ISO 8601 start time") @Nullable String since,
            @QueryParam("until")
            @Parameter(description = "ISO 8601 end time") @Nullable String until,
            @Context UriInfo uriInfo) {

        if (lines < 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"lines must be at least 1\"}")
                    .build();
        }

        try {
            LogQueryResult result = logFileService.tail(file, lines, level, logger, since, until);
            return Response.ok(result).build();
        } catch (LogFileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (java.io.IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"" + escapeJson("I/O error: " + e.getMessage()) + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "searchLogs",
            summary = "Search log entries by regex pattern",
            responses = {
                    @ApiResponse(responseCode = "200", description = "OK"),
                    @ApiResponse(responseCode = "400", description = "Invalid pattern or parameters"),
                    @ApiResponse(responseCode = "404", description = "Log file not found")
            })
    public Response searchLogs(
            @QueryParam("file") @DefaultValue("openhab.log")
            @Parameter(description = "Log file name") String file,
            @QueryParam("pattern")
            @Parameter(description = "Java regex pattern (required). Matching is case-insensitive by "
                    + "default, so pattern=unifi matches 'UniFi Controller'. Use '.' to match all "
                    + "messages when filtering only by level, logger or time.") @Nullable String pattern,
            @QueryParam("level")
            @Parameter(description = "Minimum log level filter") @Nullable String level,
            @QueryParam("logger")
            @Parameter(description = "Logger name substring filter") @Nullable String logger,
            @QueryParam("since")
            @Parameter(description = "ISO 8601 start time") @Nullable String since,
            @QueryParam("until")
            @Parameter(description = "ISO 8601 end time") @Nullable String until,
            @QueryParam("limit") @DefaultValue("200")
            @Parameter(description = "Max results to return (max 1000)") int limit,
            @QueryParam("includeRotated") @DefaultValue("false")
            @Parameter(description = "Also search rotated log files") boolean includeRotated,
            @QueryParam("caseSensitive") @DefaultValue("false")
            @Parameter(description = "Match the pattern case-sensitively. Default false — letter case "
                    + "is ignored, matching the level and logger filters.") boolean caseSensitive,
            @Context UriInfo uriInfo) {

        if (pattern == null || pattern.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"pattern parameter is required\"}")
                    .build();
        }

        if (limit < 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"limit must be at least 1\"}")
                    .build();
        }

        try {
            LogQueryResult result = logFileService.search(
                    file, pattern, level, logger, since, until, limit, includeRotated, caseSensitive);
            return Response.ok(result).build();
        } catch (LogFileNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
                    .build();
        } catch (java.io.IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"" + escapeJson("I/O error: " + e.getMessage()) + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/files")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listLogFiles",
            summary = "List available log files",
            responses = {
                    @ApiResponse(responseCode = "200", description = "OK")
            })
    public Response listLogFiles(@Context UriInfo uriInfo) {
        return Response.ok(logFileService.listFiles()).build();
    }

    /**
     * Basic JSON string escaping for error messages.
     */
    private static String escapeJson(@Nullable String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

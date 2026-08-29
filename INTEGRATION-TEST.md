# Integration Testing Guide

Manual integration tests for the Log Query REST API addon against a running openHAB 5.x instance.

## Prerequisites

1. openHAB 5.x running and accessible at `http://localhost:8080`
2. An admin API token (create at: Main UI → Profile → API Tokens)
3. The addon JAR deployed to `$OPENHAB_HOME/addons/`

```bash
export TOKEN="your-admin-api-token-here"
export OH_URL="http://localhost:8080"
```

## 1. Verify Bundle Loaded

Check that the bundle is active in Karaf:

```bash
# Via Karaf console (ssh localhost -p 8101):
bundle:list | grep "Log Query"

# Expected: Active state
# [xxx] [Active] [  80] openHAB Add-ons :: I/O :: REST Log Query (1.1.0)
```

## 2. List Log Files

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs/files" | jq .
```

**Expected response:**
```json
{
  "logDirectory": "/var/log/openhab",
  "files": [
    { "name": "openhab.log", "size": 2456789, "lastModified": "2026-06-09T12:35:00+02:00" },
    { "name": "openhab.log.1", "size": 10485760, "lastModified": "2026-06-09T00:00:01+02:00" },
    { "name": "events.log", "size": 1234567, "lastModified": "2026-06-09T12:34:58+02:00" }
  ]
}
```

## 3. Tail Recent Entries

### Basic tail (last 10 lines)

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs?lines=10" | jq .
```

### Tail with level filter (errors only)

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs?lines=50&level=ERROR" | jq .
```

### Tail with logger filter

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs?lines=20&logger=zwave" | jq .
```

### Tail with time range

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs?lines=100&since=2026-06-09T10:00:00&until=2026-06-09T12:00:00" | jq .
```

### Tail events.log

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs?file=events.log&lines=20" | jq .
```

## 4. Search Logs

### Simple pattern search

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=timeout" | jq .
```

### Regex search with filters

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=timeout|refused&level=WARN&limit=10" | jq .
```

### Search with time range

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=error&since=2026-06-09T00:00:00&until=2026-06-09T23:59:59" | jq .
```

### Search including rotated files

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=OutOfMemory&includeRotated=true&limit=5" | jq .
```

## 5. Error Cases

### File not found (404)

```bash
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs?file=nonexistent.log"
```

### Invalid regex (400)

```bash
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=[invalid"
```

### Missing pattern (400)

```bash
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search"
```

### Path traversal attempt (404)

```bash
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs?file=../../etc/passwd"
```

### Unauthorized access (401/403)

```bash
# No auth header — should get 401 or 403
curl -s -w "\nHTTP %{http_code}\n" "$OH_URL/rest/logs/files"

# Regular user token (non-admin) — should get 403
curl -s -w "\nHTTP %{http_code}\n" -H "Authorization: Bearer $USER_TOKEN" \
  "$OH_URL/rest/logs/files"
```

## 6. Performance Checks

### Large tail request

```bash
time curl -s -H "Authorization: Bearer $TOKEN" "$OH_URL/rest/logs?lines=1000" | jq '.totalEntries'
```

### Search with many results

```bash
time curl -s -H "Authorization: Bearer $TOKEN" \
  "$OH_URL/rest/logs/search?pattern=.&limit=1000" | jq '.totalEntries'
```

## 7. Swagger UI

Once deployed, the endpoint documentation should appear in:
- openHAB API Explorer: `http://localhost:8080/doc/index.html`
- Look for the `logs` tag with three endpoints

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| 404 on all endpoints | Bundle not started | Check `bundle:list` in Karaf |
| 403 Forbidden | Not admin user | Use admin API token |
| Empty file list | Wrong log directory | Check `openhab.logdir` system property |
| No entries returned | File empty or filters too strict | Try without filters first |
| "Search service is busy" | Too many concurrent searches | Wait and retry |

# Web UI and Enhanced Metrics

This document describes the new web UI and enhanced Prometheus metrics added to Nexus DDNS.

## Web UI

### Features

The web UI provides a browser-based interface to view and filter DNS records stored in the PowerDNS database.

**Access:** Navigate to `http://your-server:7065/` in your web browser.

### Capabilities

1. **Record Browsing**
   - View all DNS records with domain, name, type, content, TTL, and status
   - Sortable columns (click any header to sort ascending/descending)
   - Real-time statistics showing total records, domains, and record type counts

2. **Filtering**
   - **Domain Filter**: Filter records by specific domain
   - **Record Type Filter**: Filter by A, AAAA, SSHFP, TXT, SOA, or NS records
   - **Search Box**: Free-text search across record names and content
   - Filters can be combined for precise queries

3. **Auto-Refresh**
   - Records automatically refresh every 30 seconds
   - Manual refresh button available

4. **Responsive Design**
   - Mobile-friendly interface
   - Color-coded record types for easy identification
   - Hover to see full content for long records

### API Endpoint

The web UI consumes the following API endpoint:

```
GET /api/v2/records
```

**Response Format:**
```json
{
  "records": [
    {
      "id": 123,
      "domain": "example.com",
      "name": "host.example.com",
      "type": "A",
      "content": "192.168.1.100",
      "ttl": 300,
      "prio": null,
      "disabled": false
    }
  ]
}
```

This endpoint requires no authentication and can be used by external tools.

## Enhanced Prometheus Metrics

### New Metrics

The following metrics have been added and are exposed at `/metrics`:

#### Request Metrics

- **`http_requests_total`** (Counter)
  - Total number of HTTP requests received
  - Incremented for every API request

- **`request_rate`** (Meter)
  - Rate of incoming requests (per second, 1m/5m/15m averages)

- **`request_duration_seconds`** (Histogram)
  - Request processing time distribution
  - Includes p50, p95, p99, and p999 percentiles

- **`request_size_bytes`** (Histogram)
  - Distribution of request body sizes

- **`response_size_bytes`** (Histogram)
  - Distribution of response body sizes

#### Update Metrics

- **`ipv4_updates_total`** (Counter)
  - Total number of IPv4 (A record) updates

- **`ipv6_updates_total`** (Counter)
  - Total number of IPv6 (AAAA record) updates

- **`sshfp_updates_total`** (Counter)
  - Total number of SSHFP record updates

- **`batch_updates_total`** (Counter)
  - Total number of batch updates (multiple record types at once)

- **`challenge_creates_total`** (Counter)
  - Total number of ACME challenge records created

- **`challenge_deletes_total`** (Counter)
  - Total number of ACME challenge records deleted

#### Security Metrics

- **`auth_failures_total`** (Counter)
  - Total number of authentication failures
  - Tracks missing signatures, invalid signatures, and missing keys

- **`unique_client_ips`** (Gauge)
  - Number of unique client IP addresses that have made requests
  - Tracks IPs from `X-Forwarded-For`, `X-Real-IP`, or `remote-addr`

#### Error Metrics

- **`errors`** (Counter)
  - Total number of server errors during request processing

### Accessing Metrics

```bash
# View all metrics
curl http://your-server:7065/metrics

# Scrape with Prometheus
# Add to prometheus.yml:
scrape_configs:
  - job_name: 'nexus-ddns'
    static_configs:
      - targets: ['nexus-server:7065']
```

### Example Prometheus Queries

```promql
# Request rate over last 5 minutes
rate(http_requests_total[5m])

# 95th percentile request latency
histogram_quantile(0.95, rate(request_duration_seconds_bucket[5m]))

# Update rate by type
rate(ipv4_updates_total[5m])
rate(ipv6_updates_total[5m])
rate(sshfp_updates_total[5m])

# Authentication failure rate
rate(auth_failures_total[5m])

# Number of active clients
unique_client_ips
```

### Grafana Dashboard

Consider creating a Grafana dashboard with panels for:

1. **Request Overview**
   - Total requests (counter)
   - Request rate (graph)
   - Request duration percentiles (graph)

2. **DNS Updates**
   - Update rate by type (stacked graph)
   - Total updates by type (stat panels)

3. **Performance**
   - Request latency heatmap
   - Request/response size distribution

4. **Security**
   - Authentication failures over time
   - Failed auth rate
   - Unique client IPs

5. **Errors**
   - Error rate (graph)
   - Total errors (counter)

## Implementation Details

### Code Changes

1. **src/nexus/datastore.clj**
   - Added `list-all-records` protocol method

2. **src/nexus/sql_datastore.clj**
   - Implemented `list-all-records-impl` with JOIN query
   - Returns all records with domain information

3. **src/nexus/metrics.clj**
   - Added comprehensive counter/meter/histogram definitions
   - Implemented unique IP tracking with atom
   - Added helper functions for metric updates

4. **src/nexus/server.clj**
   - Added `list-records` handler for API endpoint
   - Added `serve-web-ui` handler to serve HTML
   - Added `serve-metrics` handler for Prometheus
   - Enhanced all mutation handlers to track metrics
   - Added metrics tracking to authentication middleware
   - Updated routing to include new endpoints

5. **resources/web-ui.html**
   - Self-contained HTML/CSS/JavaScript application
   - No external dependencies
   - Real-time filtering and sorting

6. **deps.edn**
   - Added `resources` to paths for web-ui.html

## Configuration

No additional configuration is required. The web UI and metrics are automatically available when the server starts.

### Optional: Reverse Proxy Headers

If running behind a reverse proxy (nginx, Caddy, etc.), ensure these headers are set for accurate IP tracking:

```nginx
# Nginx example
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

## Security Considerations

### Web UI

- The web UI at `/` serves read-only data and requires no authentication
- Consider restricting access via firewall rules or reverse proxy auth
- Does not expose HMAC keys or sensitive configuration

### Metrics Endpoint

- The `/metrics` endpoint exposes operational metrics
- Contains no sensitive data (no IPs, hostnames, or record content)
- Consider restricting to internal monitoring networks
- Standard practice: only accessible from Prometheus server

### API Endpoint

- `/api/v2/records` requires no authentication
- Returns all DNS records in the database
- Should be protected if DNS data is sensitive
- Consider adding authentication middleware if needed

## Troubleshooting

### Web UI not loading

```bash
# Verify web-ui.html exists
ls resources/web-ui.html

# Check server logs for errors
tail -f /var/log/nexus-server.log

# Test API endpoint directly
curl http://localhost:7065/api/v2/records
```

### Metrics not appearing

```bash
# Check metrics endpoint
curl http://localhost:7065/metrics

# Verify Prometheus is scraping
# Check Prometheus UI -> Status -> Targets
```

### Empty records list

```bash
# Check database connectivity
psql -h localhost -U powerdns -d powerdns -c "SELECT COUNT(*) FROM records;"

# Verify datastore implementation
# Check server logs for SQL errors
```

## Future Enhancements

Potential improvements for future versions:

1. **Web UI**
   - Pagination for large record sets
   - Export to CSV/JSON
   - Record modification interface (with authentication)
   - Real-time updates via WebSocket
   - Dark mode theme

2. **Metrics**
   - Per-domain update metrics
   - Per-host update metrics
   - Record type distribution gauge
   - Serial change tracking
   - DNS query metrics (requires PowerDNS integration)

3. **API**
   - GraphQL endpoint for flexible queries
   - Bulk operations endpoint
   - Record history/audit log
   - Webhook notifications for changes

## Contributing

When adding new features:

1. Update metrics for new operations (use `inc-counter!`)
2. Add logging for important events
3. Update this documentation
4. Add tests for new functionality
5. Follow existing code patterns

## License

Same as Nexus DDNS main project.

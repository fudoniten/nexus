# PowerDNS Configuration Analysis and Improvements

## Executive Summary

The PowerDNS configuration has been improved with better documentation, optimized timers, and a fix for the secondary notification issue. The main problem was that the `nexus-powerdns-check-updates` service wasn't properly chained to actually trigger notifications.

## Issues Found and Fixed

### 1. **Secondary Notifications Not Working Reliably** ⚠️ CRITICAL

**Root Cause**: The database trigger increments the serial, but there's no automatic mechanism to notify secondaries immediately after record changes. The system relies on:
1. A database trigger that increments `notified_serial` when records change
2. A timer-based check-updates service that polls every 10 minutes
3. Manual notifications via timer every 30 minutes

**Problems**:
- **10-minute polling interval** is too slow for real-time updates
- **No path monitoring** - The trigger updates the database, but nothing immediately kicks off notifications
- **Race conditions** - The serial might increment multiple times between checks
- **Manual timer redundancy** - Having both check-updates and manual notify timers is confusing

**Solutions Applied**:

1. ✅ **Reduced check-updates interval from 10m to 5m** for faster detection
2. ✅ **Added `also-notify` directive** to PowerDNS config (line 47) - this tells PowerDNS to auto-notify secondaries
3. ✅ **Improved check-updates service** to properly detect and log changes
4. ✅ **Added `Type = "oneshot"`** to notification services for proper systemd handling
5. ✅ **Fixed missing `requires` dependency** in check-updates timer (was `after` only)

### 2. **Configuration Generation Issues**

**Found**: Duplicate `genConfig` calls in increment-serial service (line 382-383 in original)

**Fixed**: Removed duplicate config generation

### 3. **Missing Documentation**

**Fixed**: Added comprehensive inline documentation explaining:
- What each service does
- When and why services run
- The relationship between services
- Configuration parameters and their purposes

### 4. **Timer Configuration Problems**

**Issues**:
- `nexus-powerdns-notify` timer had `after` but not `requires` dependency
- Timer intervals weren't optimally tuned

**Fixed**:
- All timers now have proper `requires` + `after` dependencies
- check-updates timer starts at 2m (not 1m) to let PowerDNS fully initialize
- check-updates interval reduced to 5m for faster propagation

## Recommended Further Improvements

### Option A: Path-Based Triggers (BEST SOLUTION)

Instead of polling, use systemd path units to trigger notifications when the database changes:

```nix
systemd.paths.nexus-powerdns-watch-db = {
  description = "Watch for DNS record changes";
  wantedBy = [ "multi-user.target" ];
  pathConfig = {
    # Monitor PostgreSQL WAL directory or use pg_notify
    PathModified = "/var/lib/postgresql/...";
    Unit = "nexus-powerdns-notify.service";
  };
};
```

**Pros**: Instant notifications, no polling overhead
**Cons**: Requires PostgreSQL integration (pg_notify or log monitoring)

### Option B: Hook into the Nexus Server

Since the Nexus server is what updates records, it could trigger notifications directly:

```clojure
(defn update-record [domain host value]
  (sql/update! ...)
  ;; Trigger notification
  (notify-secondaries domain))
```

**Pros**: Immediate, clean integration
**Cons**: Requires server code changes

### Option C: Use PowerDNS Native Features

PowerDNS has `also-notify` (now added) which should auto-notify secondaries on zone changes.

**Pros**: Built-in, reliable, no extra services needed
**Cons**: Relies on PowerDNS detecting the change (which should work with the trigger)

### Option D: Webhook/HTTP Trigger

Add a webhook endpoint that the Nexus server calls after updates:

```bash
# In nexus server after update:
curl -X POST http://localhost:8080/notify/${domain}
```

**Pros**: Simple, explicit control
**Cons**: Adds HTTP dependency, requires server changes

## Key Changes Made

### 1. PowerDNS Configuration (lines 40-48)

```nix
secondary-clause = optionalString (secondary-servers != [ ]) ''
  allow-axfr-ips=${secondary-server-str}
  also-notify=${secondary-server-str}  # ← NEW: Auto-notify secondaries
'';
```

The `also-notify` directive tells PowerDNS to automatically send NOTIFY messages to secondaries when zones change. This should work in conjunction with the serial auto-increment trigger.

### 2. Check-Updates Service (lines 421-502)

**Improvements**:
- Added `requires = [ "nexus-powerdns.service" ]` (was missing)
- Changed to `Type = "oneshot"` for proper systemd handling  
- Improved logging with zone-specific messages
- Per-zone serial tracking (was using same file for all zones)
- Better serial comparison logic

### 3. Notification Service (lines 505-522)

**Improvements**:
- Added detailed logging of which secondary is being notified
- Changed to `Type = "oneshot"`
- Better documentation

### 4. Timer Configuration (lines 606-624)

**Changes**:
- Reduced `OnUnitActivateSec` from 10m to 5m
- Changed `OnBootSec` from 1m to 2m (let PowerDNS fully start)
- Added proper `requires` dependency

## Testing the Fix

### 1. Monitor Serial Changes

```bash
# Watch the check-updates service
journalctl -fu nexus-powerdns-check-updates.service

# Check current serials
systemctl start nexus-powerdns-check-updates.service
```

### 2. Test Zone Update

```bash
# Update a record via nexus-server
curl -X PUT https://ddns.example.com/api/v2/domain/example.com/host/test/ipv4 \
  -H "Access-Signature: ..." \
  -d "192.0.2.1"

# Watch for notifications
journalctl -fu nexus-powerdns-check-updates.service
```

### 3. Verify Secondary Received Update

```bash
# On secondary server
dig @secondary.dns.server example.com SOA

# Compare serial to primary
dig @primary.dns.server example.com SOA
```

## Performance Characteristics

| Scenario | Before | After | Notes |
|----------|--------|-------|-------|
| Update detection | 10 min avg | 5 min avg | Polling interval reduced |
| Min notification time | 10 min | 5 min | With check-updates timer |
| Max notification time | 20 min | 10 min | Worst case scenario |
| Auto-notify (ideal) | N/A | ~seconds | If `also-notify` works with trigger |

## Architecture Diagram

```
┌─────────────────┐
│  Nexus Server   │
│  (updates DNS)  │
└────────┬────────┘
         │ INSERT/UPDATE records
         v
┌─────────────────────────┐
│  PostgreSQL Database    │
│  ┌──────────────────┐  │
│  │ records table    │  │
│  └────────┬─────────┘  │
│           │ TRIGGER    │
│           v             │
│  ┌──────────────────┐  │
│  │ Auto-increment   │  │
│  │ notified_serial  │  │
│  └──────────────────┘  │
└─────────────────────────┘
         │
         │ PowerDNS reads serial
         v
┌─────────────────────────┐
│   PowerDNS Server       │
│   ┌─────────────────┐  │
│   │ Detects change  │  │
│   │ (via also-notify)│  │
│   └────────┬────────┘  │
│            │            │
│            v            │
│   ┌─────────────────┐  │      ┌──────────────────┐
│   │ Send NOTIFY     │──────> │ Secondary DNS     │
│   └─────────────────┘  │      │ (pulls via AXFR)  │
└─────────────────────────┘      └──────────────────┘
         ^
         │ Fallback: polling every 5m
         │
┌─────────────────────────┐
│ check-updates.service   │
│ (systemd timer)         │
└─────────────────────────┘
```

## Configuration Options to Consider

Add to `options.nix`:

```nix
options.nexus.dns-server = {
  # ...existing options...
  
  notify-check-interval = mkOption {
    type = types.str;
    default = "5m";
    description = "How often to check for zone updates and notify secondaries";
  };
  
  enable-auto-notify = mkOption {
    type = types.bool;
    default = true;
    description = "Enable PowerDNS also-notify for automatic secondary notifications";
  };
};
```

## Summary of Improvements

1. ✅ **Faster notifications**: 5-minute interval instead of 10 minutes
2. ✅ **Auto-notify enabled**: PowerDNS can now notify secondaries automatically
3. ✅ **Better logging**: Clear messages about what's happening when
4. ✅ **Fixed service dependencies**: Proper systemd requires/after chains
5. ✅ **Comprehensive documentation**: Every service and timer is now explained
6. ✅ **Per-zone serial tracking**: No more conflicts between zones
7. ✅ **Proper service types**: oneshot for triggered services
8. ✅ **Removed redundancy**: Cleaned up duplicate config generation

## Migration Guide

1. **Deploy the updated configuration**
2. **Restart PowerDNS**: `systemctl restart nexus-powerdns.service`
3. **Monitor notifications**: `journalctl -fu nexus-powerdns-check-updates.service`
4. **Test with a record update** and verify secondaries receive it within 5 minutes
5. **If auto-notify works**, consider increasing check-updates interval to 15m as a safety net

## Future Enhancements

- [ ] Add Prometheus metrics for notification success/failure
- [ ] Implement webhook-based instant notifications
- [ ] Add alerting when secondaries don't respond to NOTIFY
- [ ] Create dashboard showing serial drift between primary and secondaries
- [ ] Consider using PowerDNS API for programmatic control

# Envi Heater Hubitat Integration

Integrates Envi Smart Heaters (eHeat.com) with Hubitat Elevation via the Envi Cloud API.

## Features
- ✅ Authenticate with Envi cloud account
- ✅ Auto-discover all heaters on your account
- ✅ Control heaters: On/Off, Set heating setpoint
- ✅ Real-time temperature monitoring (ambient + target)
- ✅ JWT token expiry detection with proactive refresh
- ✅ Event change filtering (reduces log spam)
- ✅ Async HTTP calls (non-blocking)
- ✅ Manual refresh button in app UI
- ✅ Auto-remove orphaned devices (optional)

## Installation

### 1. Install Driver Code
1. Navigate to **Drivers Code** in Hubitat UI
2. Click **New Driver**
3. Paste contents of `EnviHeaterDevice.groovy`
4. Click **Save**

### 2. Install App Code
1. Navigate to **Apps Code** in Hubitat UI
2. Click **New App**
3. Paste contents of `EnviHeaterApp2.groovy`
4. Click **Save**

### 3. Add App Instance
1. Navigate to **Apps** → **Add User App**
2. Select **Envi Heater**
3. Enter your Envi account credentials (email/password)
4. Adjust polling interval (default: 5 minutes)
5. Click **Done**

### 4. Verify
- Check logs for "Authentication success" message
- Wait ~5 seconds, check for "Discovered X Envi heater(s)" message
- Navigate to **Devices** to see created heater devices

## Usage

### Device Commands
Each heater device supports:
- **On/Off** - Turn heater on or off
- **Set Heating Setpoint** - Set target temperature (°F)
- **Refresh** - Manually update current state

### Thermostat Integration
Devices implement the Thermostat capability:
- `thermostatMode`: `heat` or `off`
- `thermostatOperatingState`: `heating` or `idle`
- `temperature`: Current ambient temperature
- `heatingSetpoint`: Target temperature

### Manual Refresh
Open the Envi Heater app and click **Refresh Now** to force immediate update of all devices.

## Configuration Options

| Setting | Default | Description |
|---------|---------|-------------|
| Username (email) | - | Your Envi account email |
| Password | - | Your Envi account password |
| Override Device ID | (auto) | Optional hex string for stable device ID |
| Refresh Interval | 5 min | How often to poll API (1-60 minutes) |
| Enable Debug Logging | On | Log detailed operations |
| Verbose Auth Logging | Off | Log full authentication responses |
| Auto-remove orphaned devices | Off | Delete child devices no longer in API |

## Troubleshooting

### Authentication Fails (400 Bad Request)
1. Verify email/password at https://enviliving.com
2. Remove Override Device ID to generate fresh UUID
3. Enable Verbose Auth Logging to see response body
4. Check network/VPN not blocking enviliving.com

### Child Device Not Created
Error: "Device type 'Envi Heater Device' in namespace 'null' not found"
- **Solution**: Install the driver code first (step 1 above)
- Verify driver namespace matches: `bcn-israelforst`

### Devices Not Updating
- Check app logs for "Refresh error" messages
- Verify token hasn't expired (Status section shows expiry)
- Click **Refresh Now** button to force immediate update
- Check polling interval isn't too long

### Token Expired
- App automatically refreshes token 5 minutes before expiry
- If expired, next API call will trigger re-authentication
- Check Status section for token expiry time

## API Rate Limits
- Envi API has undocumented rate limits
- Recommended polling: 5-15 minutes
- Manual commands trigger immediate refresh after 2 seconds

## Advanced

### JWT Token Expiry
- App decodes JWT token to read expiration (exp field)
- Schedules proactive refresh 5 minutes before expiry
- Typical token lifetime: ~365 days

### Async HTTP
- GET requests use asynchttpGet (non-blocking)
- PATCH falls back to sync if asynchttpPatch unavailable
- Reduces Hubitat thread contention

### Event Filtering
- Child devices only receive events when values change
- Reduces database writes and automation triggers
- Debug logs show "Event name=value sent" only on changes

## Limitations
- No cooling mode (heaters only)
- No fan control
- No scheduling (use Hubitat Rule Machine)
- Fahrenheit only (API limitation)
- Cloud-dependent (requires internet)

## Version History
- **1.0** (2025-11-13) - Initial release
  - Authentication, discovery, basic control
  - JWT expiry parsing, async HTTP
  - Event change filtering, manual refresh

## Support
Report issues at: https://github.com/bcn-israelforst/hubitat

## Credits
Ported from Home Assistant integration: https://github.com/wlatic/envi_heater

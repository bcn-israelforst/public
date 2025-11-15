/*
 Envi Heater Hubitat Integration (Parent App)
 -------------------------------------------
 Ports core functionality from Home Assistant custom component:
     https://github.com/wlatic/envi_heater/

 Responsibilities:
     - Handle authentication (login) and token lifecycle
     - Discover all heaters (device list)
     - Create / maintain child devices for each heater
     - Provide HTTP API wrapper methods for child drivers
     - Schedule periodic refresh of device states

 Child driver file (to be created separately): EnviHeaterDevice.groovy

 Initial version focuses on basic control (on/off, set heating setpoint, state refresh).
 Future enhancements: token refresh endpoint, adaptive polling, error backoff, metrics.
*/


definition(
    name: "Envi Heater",
    namespace: "bcn-israelforst",
    author: "Israel Forst",
    description: "Integrates Envi Smart Heaters via Envi Cloud API",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Envi Heater Integration", install: true, uninstall: true) {
        section("Cloud Credentials") {
            input name: "enviUsername", type: "text", title: "Username (email)", required: true
            input name: "enviPassword", type: "password", title: "Password", required: true
        }
        section("Options") {
            input name: "pollMinutes", type: "number", title: "Refresh Interval (minutes)", defaultValue: 5, range: "1..60"
            input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
            input name: "verboseAuth", type: "bool", title: "Verbose Auth Logging", defaultValue: false
            input name: "removeOrphanDevices", type: "bool", title: "Auto-remove orphaned child devices", defaultValue: false, description: "Delete child devices no longer reported by API"
        }
        section("Status") {
            def tokenExp = state.tokenExpiry ? new Date(state.tokenExpiry).format("yyyy-MM-dd HH:mm") : "Unknown"
            def statusMsg = state.token ? "Authenticated. Devices: ${state.deviceIds?.size() ?: 0}. Token expires: ${tokenExp}" : "Not authenticated yet."
            paragraph statusMsg
            
            if(state.lastRefresh) paragraph "Last refresh: ${new Date(state.lastRefresh).format('HH:mm:ss')}"
            
            // Health metrics
            def pollInterval = state.currentPollMinutes ?: state.normalPollMinutes ?: settings.pollMinutes ?: 5
            paragraph "Polling: Every ${pollInterval} minute(s)"
            
            if(state.avgLatency) paragraph "Avg API latency: ${state.avgLatency}ms"
            
            if(state.consecutiveErrors && state.consecutiveErrors > 0) {
                paragraph "<span style='color:orange'>⚠️ Consecutive errors: ${state.consecutiveErrors}</span>"
            }
            
            if(state.circuitOpen) {
                paragraph "<span style='color:red'>⛔ Circuit breaker OPEN - polling paused</span>"
            }
            
            input name: "btnRefresh", type: "button", title: "Refresh Now", submitOnChange: false
        }
    }
}

def appButtonHandler(btn) {
    if(btn == "btnRefresh") {
        logInfo "Manual refresh triggered"
        refreshAll()
    }
}

// Lifecycle
def installed() {
    logInfo "Installed"
    initialize()
}

def updated() {
    logInfo "Updated"
    unschedule()
    initialize()
}

def initialize() {
    if(!state.deviceUUID){
        state.deviceUUID = UUID.randomUUID().toString().replace('-', '') // mimic HA style hex
    }
    authenticateIfNeeded()
    if(state.token) {
        discoverAndCreateChildren()
        schedulePolling()
    }
}

// Authentication
private authenticateIfNeeded(force=false) {
    def tokenAgeMins = state.tokenUpdated ? ((now() - state.tokenUpdated) / 60000) : null
    if(!(force || !state.token || (tokenAgeMins && tokenAgeMins > 60))) return
    def user = settings.enviUsername?.trim()
    def pass = settings.enviPassword
    if(!user || !pass){ logWarn "Missing username/password for authentication"; return }
    logDebug "Authenticating with Envi API (device_id=${state.deviceUUID})"
    
    def success = doLogin()
    if(!success) {
        logWarn "Authentication failed";
        logWarn "Troubleshooting tips: (1) Verify email/password at official app. (2) Remove Override Device ID so a fresh ID is generated. (3) Ensure no VPN blocking. (4) Turn on Verbose Auth Logging.";
    }
}

private boolean doLogin(){
    def payload = [
        username: settings.enviUsername?.trim(),
        password: settings.enviPassword,
        login_type: 1,
        device_id: state.deviceUUID,
        device_type: 'homeassistant'
    ]
    def params = [
        uri: 'https://app-apis.enviliving.com/apis/v1/auth/login',
        headers: [ 'Accept':'application/json' ],
        contentType: 'application/json',
        body: payload
    ]
    try {
        httpPost(params) { resp ->
            if(verboseAuth) logDebug "Auth attempt type='homeassistant' status=${resp.status} body=${resp.data}"
            if(resp.status == 200 && resp.data?.status == 'success') {
                def t = resp.data?.data?.token
                if(t) {
                    state.token = t
                    state.tokenUpdated = now()
                    state.tokenExpiry = parseJwtExpiry(t)
                    if(state.tokenExpiry) {
                        def expMins = ((state.tokenExpiry - now()) / 60000) as Integer
                        logInfo "Authentication success, token expires in ${expMins} minutes"
                        scheduleTokenRefresh()
                    } else {
                        logInfo "Authentication success"
                    }
                } else {
                    logWarn "Auth succeeded but token missing"
                }
            } else {
                logWarn "Auth failed status=${resp.status} body=${resp.data}";
            }
        }
    } catch(groovyx.net.http.HttpResponseException e){
        logWarn "Authentication HTTP ${e.statusCode} body=${e.response?.data}"
    } catch(Exception e){
        logWarn "Authentication error: ${e.message}";
    }
    return state.token != null
}

// JWT expiry parsing
private parseJwtExpiry(String token){
    try {
        def parts = token?.split('\\.')
        if(parts?.size() >= 2){
            def payload = parts[1].replace('-','+').replace('_','/')
            // Add padding if needed
            while(payload.length() % 4 != 0) payload += '='
            def payloadJson = new String(payload.decodeBase64())
            def data = new groovy.json.JsonSlurper().parseText(payloadJson)
            return data?.exp ? (data.exp * 1000L) : null
        }
    } catch(Exception e){
        logWarn "JWT parse error: ${e.message}"
    }
    return null
}

private scheduleTokenRefresh(){
    if(!state.tokenExpiry) return
    def expMins = ((state.tokenExpiry - now()) / 60000) as Integer
    if(expMins > 10){
        def refreshMins = expMins - 5  // Refresh 5 minutes before expiry
        logDebug "Scheduling token refresh in ${refreshMins} minutes"
        runIn(refreshMins * 60, 'refreshToken')
    }
}

def refreshToken(){
    logInfo "Proactive token refresh triggered"
    authenticateIfNeeded(true)
}

// Device discovery & children
private discoverAndCreateChildren() {
    if(!state.token) { logWarn "Cannot discover devices without token"; return }
    try {
        def params = [
            uri: 'https://app-apis.enviliving.com/apis/v1/device/list',
            headers: [ 'Authorization': "Bearer ${state.token}" ],
            contentType: 'application/json'
        ]
        httpGet(params) { resp ->
            if(resp.status == 200 && resp.data?.status == 'success') {
                def devices = resp.data?.data ?: []
                state.deviceIds = devices.collect{ it.id }
                logInfo "Discovered ${devices.size()} Envi heater(s): ${state.deviceIds}"
                devices.each { dev ->
                    createOrUpdateChild(dev)
                }
                // Device removal handling
                if(settings.removeOrphanDevices) {
                    removeOrphanedChildren(state.deviceIds)
                }
            } else {
                logWarn "Device list fetch failed: status=${resp.status} body=${resp.data}"
            }
        }
    } catch(Exception e){
        logWarn "Device discovery error: ${e.message}"; return
    }
}

private removeOrphanedChildren(activeIds){
    def allChildren = getChildDevices()
    allChildren.each { child ->
        def dni = child.deviceNetworkId
        if(dni?.startsWith('envi-')){
            def deviceId = dni.substring(5) as String
            def activeIdsAsStrings = activeIds.collect{ it.toString() }
            if(!(deviceId in activeIdsAsStrings)){
                logWarn "Removing orphaned child device ${child.label} (${dni}) - deviceId ${deviceId} not in ${activeIdsAsStrings}"
                try {
                    deleteChildDevice(dni)
                } catch(Exception e){
                    logWarn "Failed to remove orphaned device ${dni}: ${e.message}"
                }
            }
        }
    }
}

private createOrUpdateChild(dev){
    def dni = "envi-${dev.id}" // Device Network ID
    def label = dev.name ? "Envi Heater ${dev.name}" : "Envi Heater ${dev.id}"
    def child = getChildDevice(dni)
    if(!child) {
        try {
            // Explicit namespace string; app.namespace may be null in some environments
            child = addChildDevice("bcn-israelforst", 'Envi Heater Device', dni, [name: label, label: label])
            logInfo "Created child device ${label} (${dni})"
        } catch(Exception e){
            logWarn "Failed to create child for deviceId=${dev.id} dni=${dni}: ${e.message}"
            return
        }
    }
}

// Polling
private schedulePolling() {
    def mins = (settings.pollMinutes ?: 5) as Integer
    state.normalPollMinutes = mins
    logInfo "Scheduling refresh every ${mins} minute(s)"
    runIn(2, 'refreshAll') // quick initial refresh after install
    schedule("0 */${mins} * * * ?", 'refreshAll')
}

// Adaptive polling: adjust frequency based on heater activity
private adjustPollingIfNeeded() {
    def anyHeating = false
    getChildDevices().each { child ->
        if(child.currentValue('thermostatOperatingState') == 'heating') {
            anyHeating = true
        }
    }
    
    def currentInterval = state.currentPollMinutes ?: state.normalPollMinutes
    def targetInterval = anyHeating ? 2 : state.normalPollMinutes
    
    if(currentInterval != targetInterval) {
        state.currentPollMinutes = targetInterval
        unschedule('refreshAll')
        schedule("0 */${targetInterval} * * * ?", 'refreshAll')
        logInfo "Adjusted polling to ${targetInterval} minute(s) (${anyHeating ? 'heating active' : 'all idle'})"
    }
}

def refreshAll() {
    if(!state.token) { authenticateIfNeeded(true) }
    if(!state.token) { logWarn "Skip refresh; no token"; return }
    
    def startTime = now()
    state.lastRefresh = startTime
    
    // Batch retrieval: get all device states in one API call
    def params = [
        path: "device/list",
        errorMsg: "Batch refresh error"
    ]
    doApiCall('GET', params) { resp ->
        def latency = now() - startTime
        if(resp.status == 200 && resp.data?.status == 'success') {
            def devices = resp.data?.data ?: []
            logDebug "Batch refresh retrieved ${devices.size()} devices in ${latency}ms"
            
            // Update health metrics
            recordSuccess(latency)
            
            devices.each { data ->
                updateChildDevice(data.id, data)
            }
            
            // Adaptive polling: check if any heater is actively heating
            adjustPollingIfNeeded()
        } else {
            recordFailure()
            logWarn "Batch refresh failed: status=${resp.status} body=${resp.data}"
        }
    }
}

// Individual device refresh (called from child device or after commands)
private refreshChild(deviceId) {
    def params = [
        path: "device/${deviceId}",
        errorMsg: "Refresh error for device ${deviceId}"
    ]
    doApiCall('GET', params) { resp ->
        if(resp.status == 200 && resp.data?.status == 'success') {
            def data = resp.data?.data
            updateChildDevice(deviceId, data)
        } else {
            logWarn "Device ${deviceId} state fetch failed: ${resp.data}"
        }
    }
}

private updateChildDevice(deviceId, data) {
    def dni = "envi-${deviceId}"
    def child = getChildDevice(dni)
    if(!child) { logWarn "Missing child device for ${deviceId}"; return }
    // Data fields (based on HA integration): ambient_temperature, current_temperature, state, status
    def ambient = data?.ambient_temperature
    def target = data?.current_temperature
    def powerState = data?.state // 1 on, 0 off
    def available = data?.status == 1
    sendEventIfChanged(child, 'temperature', ambient, 'F')
    sendEventIfChanged(child, 'heatingSetpoint', target, 'F')
    sendEventIfChanged(child, 'thermostatMode', powerState == 1 ? 'heat' : 'off')
    sendEventIfChanged(child, 'thermostatOperatingState', powerState == 1 ? 'heating' : 'idle')
    sendEventIfChanged(child, 'switch', powerState == 1 ? 'on' : 'off')
    sendEventIfChanged(child, 'available', available)
    if(logEnable) log.debug "Refreshed device ${deviceId} ambient=${ambient} target=${target} state=${powerState}"
}

// Event change filtering helper
private sendEventIfChanged(child, name, value, unit=null){
    def oldValue = child.currentValue(name)
    if(oldValue != value || oldValue == null){
        def evt = [name: name, value: value]
        if(unit) evt.unit = unit
        child.sendEvent(evt)
        if(logEnable) log.debug "Event ${name}=${value} sent to ${child.label}"
    }
}

// Command entrypoints from child driver
def childSetTemperature(deviceId, temperature) {
    def temp = temperature as Integer
    
    // Validate temperature range (50-85°F) as safety check
    if(temp < 50 || temp > 85) {
        logWarn "Temperature ${temp}°F outside valid range (50-85°F) for device ${deviceId} - rejecting"
        return
    }
    
    patchDevice(deviceId, [temperature: temp])
    runIn(2, 'refreshChild', [data: deviceId])
}
def childTurnOn(deviceId) {
    patchDevice(deviceId, [state: 1])
    runIn(2, 'refreshChild', [data: deviceId])
}
def childTurnOff(deviceId) {
    patchDevice(deviceId, [state: 0])
    runIn(2, 'refreshChild', [data: deviceId])
}

private patchDevice(deviceId, bodyMap) {
    if(!state.token) { authenticateIfNeeded(true) }
    if(!state.token) { logWarn "Cannot send command; no token"; return }
    def params = [
        path: "device/update-temperature/${deviceId}",
        body: bodyMap,
        errorMsg: "Patch error for device ${deviceId}"
    ]
    doApiCall('PATCH', params) { resp ->
        if(resp.status == 200) {
            logInfo "Patched device ${deviceId} body=${bodyMap}"
        } else {
            logWarn "Patch failed ${resp.status} device ${deviceId} body=${bodyMap}"
        }
    }
}

// Unified HTTP API helper with async support and auth retry
private doApiCall(String method, Map params, Closure callback) {
    if(!state.token) { authenticateIfNeeded(true) }
    if(!state.token) { 
        logWarn params.errorMsg ?: "API call failed: no token"
        return 
    }
    
    def uri = "https://app-apis.enviliving.com/apis/v1/${params.path}"
    def httpParams = [
        uri: uri,
        headers: [ 'Authorization': "Bearer ${state.token}" ],
        contentType: 'application/json',
        timeout: 15
    ]
    if(params.body) httpParams.body = params.body
    
    try {
        // Use synchronous HTTP calls (async not available in all Hubitat versions)
        if(method == 'GET') {
            httpGet(httpParams) { resp ->
                if(resp.status in [401, 403]) {
                    logWarn "Auth issue (${resp.status}) - retrying with fresh token"
                    authenticateIfNeeded(true)
                    if(state.token && !params.retried) {
                        params.retried = true
                        doApiCall(method, params, callback)
                        return
                    }
                }
                callback(resp)
            }
        } else if(method == 'PATCH') {
            httpPatch(httpParams) { resp -> 
                if(resp.status in [401, 403]) {
                    logWarn "Auth issue (${resp.status}) - retrying with fresh token"
                    authenticateIfNeeded(true)
                    if(state.token && !params.retried) {
                        params.retried = true
                        doApiCall(method, params, callback)
                        return
                    }
                }
                callback(resp)
            }
        } else if(method == 'POST') {
            httpPost(httpParams) { resp -> 
                if(resp.status in [401, 403]) {
                    logWarn "Auth issue (${resp.status}) - retrying with fresh token"
                    authenticateIfNeeded(true)
                    if(state.token && !params.retried) {
                        params.retried = true
                        doApiCall(method, params, callback)
                        return
                    }
                }
                callback(resp)
            }
        }
    } catch(Exception e) {
        logWarn "${params.errorMsg ?: 'API call error'}: ${e.message}"
        recordFailure()
    }
}

// Health metrics and circuit breaker
private recordSuccess(latency) {
    state.consecutiveErrors = 0
    state.lastSuccessTime = now()
    
    // Track average latency (rolling average of last 10 samples)
    def latencies = state.latencies ?: []
    latencies.add(latency)
    if(latencies.size() > 10) latencies = latencies.drop(latencies.size() - 10)
    state.latencies = latencies
    state.avgLatency = (latencies.sum() / latencies.size()) as Integer
    
    // Resume polling if circuit was open
    if(state.circuitOpen) {
        state.circuitOpen = false
        logInfo "Circuit breaker closed - resuming normal operation"
        schedulePolling()
    }
}

private recordFailure() {
    state.consecutiveErrors = (state.consecutiveErrors ?: 0) + 1
    
    if(state.consecutiveErrors >= 5 && !state.circuitOpen) {
        state.circuitOpen = true
        unschedule('refreshAll')
        def cooldownMins = 30
        logWarn "Circuit breaker opened after ${state.consecutiveErrors} failures - pausing polling for ${cooldownMins} minutes"
        runIn(cooldownMins * 60, 'attemptCircuitReset')
    }
}

def attemptCircuitReset() {
    logInfo "Circuit breaker cooldown expired - attempting to resume"
    authenticateIfNeeded(true)
    if(state.token) {
        refreshAll()
    } else {
        // Retry in another 30 minutes
        runIn(1800, 'attemptCircuitReset')
    }
}

// Logging helpers
private logDebug(msg){ if(logEnable) log.debug "EnviHeaterApp: ${msg}" }
private logInfo(msg){ log.info "EnviHeaterApp: ${msg}" }
private logWarn(msg){ log.warn "EnviHeaterApp: ${msg}" }

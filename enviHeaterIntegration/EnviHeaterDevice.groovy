metadata {
  definition(name: "Envi Heater Device", namespace: "bcn-israelforst", author: "Israel Forst") {
    capability "TemperatureMeasurement"
    capability "Switch"
    capability "Refresh"

    // Custom attributes (instead of full Thermostat capability)
    attribute "heatingSetpoint", "number"
    attribute "thermostatMode", "enum", ["heat", "off"]
    attribute "thermostatOperatingState", "enum", ["heating", "idle"]
    attribute "available", "boolean"
    
    // Commands
    command "setHeatingSetpoint", [[name:"temperature", type:"NUMBER", description:"Target temperature in Fahrenheit"]]
    command "setThermostatMode", [[name:"mode", type:"ENUM", constraints:["heat","off"], description:"Set mode to heat or off"]]
  }
  preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
  }
}

// Hubitat required methods
void installed(){ 
  logInfo "Installed"
}
void updated(){ 
  logInfo "Updated"
}

// Command implementations
void refresh(){ parent?.refreshChild(deviceId()) }

String deviceId(){ device.deviceNetworkId?.substring(5) } // Remove 'envi-' prefix

void setHeatingSetpoint(temperature){
  logDebug "setHeatingSetpoint(${temperature})"
  def temp = temperature as Integer
  
  // Validate temperature range (50-85°F)
  if(temp < 50 || temp > 85) {
    logWarn "Temperature ${temp}°F outside valid range (50-85°F) - clamping"
    temp = Math.max(50, Math.min(85, temp))
  }
  
  parent?.childSetTemperature(deviceId(), temp)
}

void setThermostatMode(String mode){
  logDebug "setThermostatMode(${mode})"
  if(mode == 'heat') {
    parent?.childTurnOn(deviceId())
  } else if(mode == 'off') {
    parent?.childTurnOff(deviceId())
  } else {
    logWarn "Unsupported thermostatMode ${mode}"
  }
}

void on(){ logDebug "on()"; parent?.childTurnOn(deviceId()) }
void off(){ logDebug "off()"; parent?.childTurnOff(deviceId()) }

// Logging helpers
private logDebug(msg){ if(logEnable) log.debug "EnviHeaterDevice(${deviceId()}): ${msg}" }
private logInfo(msg){ log.info "EnviHeaterDevice(${deviceId()}): ${msg}" }
private logWarn(msg){ log.warn "EnviHeaterDevice(${deviceId()}): ${msg}" }

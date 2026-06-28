/**
 * Dropcountr Water Usage Driver for Hubitat - Optimized Edition (with Fixed Leak Detection)
 *
 * Features:
 * - Login / session cookie management
 * - Cached API templates to reduce redundant lookups
 * - Fixed scheduling options (15m, 30m, 1h, 2h, 4h)
 * - Native Hubitat "LeakSensor" capability mapping (wet/dry states)
 * - Only commits database state changes when attributes actually change
 *
 * Author : Custom / Optimized
 * Version: 1.2.0
 */

metadata {
    definition(
        name: "Dropcountr Water Monitor",
        namespace: "community",
        author: "Custom"
    ) {
        capability "Sensor"
        capability "Refresh"
        capability "Initialize"
        capability "WaterSensor" // Standardizes wet/dry states for Rule Machine & Safety Monitor

        attribute "todayUsageGallons",     "number"
        attribute "yesterdayUsageGallons", "number"
        attribute "monthUsageGallons",      "number"
        attribute "monthCostDollars",       "number"
        attribute "leak",                   "enum", ["dry", "wet"]
        attribute "leakDetected",           "enum", ["true", "false"] // Retained for backwards compatibility
        attribute "lastUpdated",            "string"
        attribute "sessionStatus",          "enum", ["LoggedIn", "LoggedOut", "Error"]

        command "login"
        command "logout"
        command "refresh"
    }

    preferences {
        input name: "email", type: "text", title: "Dropcountr Email", required: true
        input name: "password", type: "password", title: "Dropcountr Password", required: true
        input name: "pollIntervalMinutes", type: "enum", title: "Poll Interval (Minutes)", options: ["15", "30", "60", "120", "240"], defaultValue: "60", required: true
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: false
    }
}

Map dc() {
    return [
        LOGIN_URL : "https://dropcountr.com/login",
        ME_URL    : "https://dropcountr.com/api/me",
        LOGOUT_URL: "https://dropcountr.com/api/logout",
        API_ACCEPT: "application/vnd.dropcountr.api+json;version=2",
        USER_AGENT: "HubitatDropcountrDriver/1.2"
    ]
}

void installed() {
    log.info "Dropcountr driver installed."
    initialize()
}

void updated() {
    log.info "Dropcountr driver updated — re-scheduling."
    unschedule()
    schedulePoll()
    if (!hasCookies()) {
        login()
    } else {
        refresh()
    }
}

void initialize() {
    updateAttribute("sessionStatus", "LoggedOut")
    login()
}

// ── Commands ───────────────────────────────────────────────────────────────────

void login() {
    logDebug "Logging in to Dropcountr..."
    state.cookies = [:]
    state.usageTemplate = null
    state.costTemplate = null

    Map params = [
        uri                 : dc().LOGIN_URL,
        contentType         : "application/x-www-form-urlencoded",
        requestContentType  : "application/x-www-form-urlencoded",
        body                : "email=${URLEncoder.encode(email, 'UTF-8')}&password=${URLEncoder.encode(password, 'UTF-8')}",
        followRedirects     : false,
        ignoreSSLIssues     : false
    ]

    try {
        httpPost(params) { resp ->
            resp.headers.each { h ->
                if (h.name?.toLowerCase() == "set-cookie") parseCookieHeader(h.value)
            }

            if (!state.cookies && resp.cookies) {
                resp.cookies.each { name, cookie ->
                    if (!state.cookies) state.cookies = [:]
                    state.cookies[name] = cookie.value ?: cookie
                }
            }

            if (state.cookies && resp.status in [200, 301, 302, 303]) {
                updateAttribute("sessionStatus", "LoggedIn")
                log.info "Dropcountr login successful."
                schedulePoll()
                runIn(1, "refresh")
            } else {
                log.warn "Login returned status ${resp.status} without valid session cookies."
                updateAttribute("sessionStatus", "Error")
            }
        }
    } catch (Exception e) {
        log.error "Dropcountr login error: ${e.message}"
        updateAttribute("sessionStatus", "Error")
    }
}

void logout() {
    logDebug "Logging out."
    apiGet(dc().LOGOUT_URL) {}
    state.cookies = [:]
    state.usageTemplate = null
    state.costTemplate = null
    unschedule()
    updateAttribute("sessionStatus", "LoggedOut")
}

void refresh() {
    if (!hasCookies()) {
        log.warn "No session — attempting auto login."
        login()
        return
    }
    
    if (!state.usageTemplate) {
        fetchTemplatesAndData()
    } else {
        fetchMetrics()
    }
}

// ── Data Fetching Logic ────────────────────────────────────────────────────────

private void fetchTemplatesAndData() {
    logDebug "Fetching API endpoint structures..."
    apiGet(dc().ME_URL) { Map me ->
        Map premise = me?.attributes
        List connections = premise?.service_connections ?: []
        if (connections.isEmpty()) {
            log.warn "No connection points found."
            return
        }

        Map connection = connections[0]
        state.usageTemplate = connection?.usage_series?.template
        state.costTemplate  = connection?.cost_series?.template

        if (!state.usageTemplate) {
            log.warn "Failed to resolve usage links dynamically."
            return
        }
        fetchMetrics()
    }
}

private void fetchMetrics() {
    logDebug "Updating standard usage fields..."
    String ut = state.usageTemplate
    String ct = state.costTemplate

    // 1. Today's usage & Leak Detection
    apiGet(expandTemplate(ut, "day", todayDateRange())) { data ->
        Number gallons = sumGallons(data)
        updateAttribute("todayUsageGallons", gallons, "gal")
        
        // Deep parsing data map structure for abnormal usage
        boolean isLeaking = detectLeak(data)
        updateAttribute("leakDetected", isLeaking.toString())
        updateAttribute("leak", isLeaking ? "wet" : "dry")
    }

    // 2. Yesterday's usage
    apiGet(expandTemplate(ut, "day", yesterdayDateRange())) { data ->
        updateAttribute("yesterdayUsageGallons", sumGallons(data), "gal")
    }

    // 3. Current Month Summary usage
    apiGet(expandTemplate(ut, "month", currentMonthRange())) { data ->
        updateAttribute("monthUsageGallons", sumGallons(data), "gal")
    }

    // 4. Current Month Financial Cost
    if (ct) {
        apiGet(expandTemplate(ct, "month", currentMonthRange())) { data ->
            updateAttribute("monthCostDollars", sumCost(data), "\$")
        }
    }

    updateAttribute("lastUpdated", new Date().toString())
}

// ── Networking Helpers ─────────────────────────────────────────────────────────

private void apiGet(String url, Closure handler) {
    Map params = [
        uri            : url,
        headers        : ["Accept": dc().API_ACCEPT, "User-Agent": dc().USER_AGENT, "Cookie": cookieHeader()],
        contentType    : "application/json",
        ignoreSSLIssues: false
    ]

    try {
        httpGet(params) { resp ->
            resp.headers.each { h ->
                if (h.name?.toLowerCase() == "set-cookie") parseCookieHeader(h.value)
            }

            if (resp.status == 401) {
                log.warn "Session token invalidated by remote host (401). Retrying authentication..."
                updateAttribute("sessionStatus", "LoggedOut")
                login()
                return
            }

            if (resp.status == 200) {
                def body = resp.data
                def node = (body instanceof Map) ? (body.data ?: body) : body
                handler(node)
            } else {
                log.warn "HTTP Request failed against target server with code: ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "Network interface communication fault: ${e.message}"
    }
}

// ── Parsers & Formatters ───────────────────────────────────────────────────────

private void parseCookieHeader(String header) {
    if (!header) return
    String pair = header.split(";")[0].trim()
    if (pair.contains("=")) {
        List parts = pair.split("=", 2)
        if (!state.cookies) state.cookies = [:]
        state.cookies[parts[0].trim()] = parts[1].trim()
    }
}

private String cookieHeader() {
    return state.cookies ? state.cookies.collect { k, v -> "${k}=${v}" }.join("; ") : ""
}

private boolean hasCookies() {
    return state.cookies && !state.cookies.isEmpty()
}

private String expandTemplate(String template, String period, String during) {
    return template
        .replace("{?during,period}", "?during=${during}&period=${period}")
        .replace("{?period,during}", "?period=${period}&during=${during}")
        .replace("{/period}", "/${period}")
        .replace("{/during}", "/${during}")
        .replace("{period}",  period)
        .replace("{during}",  during)
}

private Number sumGallons(def data) {
    if (!data) return 0
    List members = data?.member ?: (data instanceof List ? data : [data])
    double total = 0.0
    members.each { m ->
        if (m?.total_gallons != null) {
            total += (m.total_gallons as double)
        } else if (m?.member) {
            m.member.each { inner ->
                def v = inner?.quantity?.value ?: inner?.total_gallons
                if (v != null) total += (v as double)
            }
        } else {
            def v = m?.quantity?.value ?: m?.quantity ?: m?.gallons
            if (v != null) total += (v as double)
        }
    }
    return total
}

private Number sumCost(def data) {
    if (!data) return 0
    List members = data?.member ?: (data instanceof List ? data : [data])
    BigDecimal total = 0.0
    members.each { m ->
        def val = m?.price ?: m?.amount ?: m?.cost ?: m?.total
        if (val != null) {
            try {
                total = total + new BigDecimal(val.toString().trim())
            } catch (e) { /* skip */ }
        }
    }
    return total.setScale(2, BigDecimal.ROUND_HALF_UP).toDouble()
}

private boolean detectLeak(def data) {
    if (!data) return false
    List members = data?.member ?: (data instanceof List ? data : [data])
    
    return members.any { m ->
        if (m?.leak == true || m?.has_leak == true || m?.leakDetected == true || m?.anomaly == true) {
            return true
        }
        if (m?.member) {
            return m.member.any { inner -> 
                inner?.leak == true || inner?.has_leak == true || inner?.anomaly == true 
            }
        }
        return false
    }
}

// ── Time & Attribute Management ───────────────────────────────────────────────

private void updateAttribute(String name, def value, String unit = null) {
    def descriptionText = unit ? "${name.capitalize()} updated to ${value} ${unit}" : "${name.capitalize()} changed to ${value}"
    if (device.currentValue(name) != value) {
        Map eventMap = [name: name, value: value, descriptionText: descriptionText, isStateChange: true]
        if (unit) eventMap.unit = unit
        sendEvent(eventMap)
    }
}

private String todayDateRange() {
    Date now = new Date()
    return "${fmtZ(midnightLocal(now, 0))}/${fmtZ(midnightLocal(now, 1))}"
}

private String yesterdayDateRange() {
    Date now = new Date()
    return "${fmtZ(midnightLocal(now, -1))}/${fmtZ(midnightLocal(now, 0))}"
}

private String currentMonthRange() {
    Date now = new Date()
    String y = now.format("yyyy", location.timeZone)
    String m = now.format("MM",   location.timeZone)
    return "${y}-${m}-01/${now.format("yyyy-MM-dd", location.timeZone)}"
}

private Date midnightLocal(Date base, int offsetDays) {
    Calendar cal = Calendar.getInstance(location.timeZone)
    cal.setTime(base)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DATE, offsetDays)
    return cal.getTime()
}

private String fmtZ(Date d) {
    return d.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
}

private void schedulePoll() {
    int minutes = (pollIntervalMinutes ?: "60").toInteger()
    logDebug "Scheduling execution cycle for every ${minutes} minutes."
    
    switch (minutes) {
        case 15:  schedule("0 */15 * ? * *", "refresh"); break
        case 30:  schedule("0 */30 * ? * *", "refresh"); break
        case 120: schedule("0 0 */2 ? * *", "refresh"); break
        case 240: schedule("0 0 */4 ? * *", "refresh"); break
        default:  schedule("0 0 * ? * *", "refresh"); break // 60 mins fallback
    }
}

private void logDebug(String msg) {
    if (logEnable) log.debug "[Dropcountr] ${msg}"
}

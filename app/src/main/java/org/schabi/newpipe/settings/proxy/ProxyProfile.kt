/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.proxy

import org.json.JSONObject

/**
 * One saved proxy profile (global list; exactly one may be selected as active).
 */
data class ProxyProfile(
    val id: String,
    val displayName: String,
    /** [org.schabi.newpipe.R.string] proxy_type_http_value or proxy_type_socks5_value */
    val proxyType: String,
    val host: String,
    val port: String,
    val username: String,
    val password: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(JSON_ID, id)
        put(JSON_NAME, displayName)
        put(JSON_TYPE, proxyType)
        put(JSON_HOST, host)
        put(JSON_PORT, port)
        put(JSON_USERNAME, username)
        put(JSON_PASSWORD, password)
    }

    companion object {
        private const val JSON_ID = "id"
        private const val JSON_NAME = "name"
        private const val JSON_TYPE = "type"
        private const val JSON_HOST = "host"
        private const val JSON_PORT = "port"
        private const val JSON_USERNAME = "username"
        private const val JSON_PASSWORD = "password"

        @JvmStatic
        fun fromJson(obj: JSONObject): ProxyProfile = ProxyProfile(
            id = obj.optString(JSON_ID, ""),
            displayName = obj.optString(JSON_NAME, ""),
            proxyType = obj.optString(JSON_TYPE, ""),
            host = obj.optString(JSON_HOST, ""),
            port = obj.optString(JSON_PORT, ""),
            username = obj.optString(JSON_USERNAME, ""),
            password = obj.optString(JSON_PASSWORD, "")
        )
    }
}

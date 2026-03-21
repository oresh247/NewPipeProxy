/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings.proxy

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.schabi.newpipe.R

/**
 * Persists multiple [ProxyProfile] entries in default [SharedPreferences] as JSON, with one active id.
 */
object ProxyProfileStore {

    @JvmStatic
    fun migrateLegacyIfNeeded(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val jsonKey = context.getString(R.string.proxy_profiles_json_key)
        if (prefs.contains(jsonKey)) {
            return
        }
        val rawHost = prefs.getString(context.getString(R.string.proxy_host_key), "")?.trim().orEmpty()
        if (rawHost.isEmpty()) {
            prefs.edit().putString(jsonKey, "[]").apply()
            return
        }
        val id = UUID.randomUUID().toString()
        val type = prefs.getString(
            context.getString(R.string.proxy_type_key),
            context.getString(R.string.proxy_type_http_value)
        ) ?: context.getString(R.string.proxy_type_http_value)
        val port = prefs.getString(
            context.getString(R.string.proxy_port_key),
            context.getString(R.string.proxy_port_default)
        ) ?: context.getString(R.string.proxy_port_default)
        val profile = ProxyProfile(
            id = id,
            displayName = context.getString(R.string.proxy_default_profile_name),
            proxyType = type.trim().lowercase(),
            host = rawHost,
            port = port.trim(),
            username = prefs.getString(context.getString(R.string.proxy_username_key), "").orEmpty(),
            password = prefs.getString(context.getString(R.string.proxy_password_key), "").orEmpty()
        )
        writeProfilesInternal(prefs, context, listOf(profile))
        prefs.edit().putString(context.getString(R.string.proxy_active_profile_id_key), id).apply()
    }

    @JvmStatic
    fun readProfiles(context: Context): List<ProxyProfile> {
        migrateLegacyIfNeeded(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(context.getString(R.string.proxy_profiles_json_key), "[]")
            ?: "[]"
        return parseProfilesJson(raw)
    }

    @JvmStatic
    fun saveProfiles(context: Context, profiles: List<ProxyProfile>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        writeProfilesInternal(prefs, context, profiles)
    }

    private fun writeProfilesInternal(
        prefs: SharedPreferences,
        context: Context,
        profiles: List<ProxyProfile>
    ) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(p.toJson())
        }
        prefs.edit().putString(context.getString(R.string.proxy_profiles_json_key), arr.toString()).apply()
    }

    private fun parseProfilesJson(raw: String): List<ProxyProfile> {
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = ProxyProfile.fromJson(o)
                    if (p.id.isNotEmpty()) {
                        add(p)
                    }
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    @JvmStatic
    fun getActiveProfileId(context: Context): String? {
        migrateLegacyIfNeeded(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val id = prefs.getString(context.getString(R.string.proxy_active_profile_id_key), null)
        return id?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun setActiveProfileId(context: Context, profileId: String?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(
                context.getString(R.string.proxy_active_profile_id_key),
                profileId?.takeIf { it.isNotEmpty() }
            )
            .apply()
    }

    /**
     * Profile used for [org.schabi.newpipe.DownloaderImpl] when proxy is enabled, or null if none valid.
     */
    @JvmStatic
    fun getActiveProfileForConnection(context: Context): ProxyProfile? {
        migrateLegacyIfNeeded(context)
        val profiles = readProfiles(context)
        if (profiles.isEmpty()) {
            return null
        }
        val activeId = getActiveProfileId(context)
        val byId = activeId?.let { id -> profiles.firstOrNull { it.id == id } }
        return byId ?: profiles.first()
    }
}

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.proxy.ProxyProfile
import org.schabi.newpipe.settings.proxy.ProxyProfileStore

/**
 * Add / edit / delete saved proxy profiles (one active profile is chosen on the parent content screen).
 */
class ProxyProfilesFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResourceRegistry(rootKey)

        findPreference<Preference>(getString(R.string.proxy_add_profile_pref_key))!!
            .setOnPreferenceClickListener {
                showProfileDialog(requireContext(), null)
                true
            }
        refreshProfileList()
    }

    override fun onResume() {
        super.onResume()
        refreshProfileList()
    }

    private fun refreshProfileList() {
        val category = findPreference<PreferenceCategory>(getString(R.string.proxy_profile_list_category_key))
            ?: return
        while (category.preferenceCount > 0) {
            category.removePreference(category.getPreference(0))
        }
        val ctx = requireContext()
        val profiles = ProxyProfileStore.readProfiles(ctx)
        for (profile in profiles) {
            val pref = Preference(ctx).apply {
                key = "proxy_profile_${profile.id}"
                title = profile.displayName.ifEmpty { profile.host }
                summary = "${profile.host}:${profile.port} · ${profile.proxyType.uppercase()}"
                isSingleLineTitle = false
                setOnPreferenceClickListener {
                    showProfileDialog(ctx, profile)
                    true
                }
            }
            category.addPreference(pref)
        }
    }

    private fun showProfileDialog(context: Context, existing: ProxyProfile?) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_proxy_profile, null)
        val nameInput = view.findViewById<TextInputEditText>(R.id.proxy_dialog_name)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.proxy_dialog_name_layout)
        val typeSpinner = view.findViewById<androidx.appcompat.widget.AppCompatSpinner>(
            R.id.proxy_dialog_type_spinner
        )
        val hostInput = view.findViewById<TextInputEditText>(R.id.proxy_dialog_host)
        val hostLayout = view.findViewById<TextInputLayout>(R.id.proxy_dialog_host_layout)
        val portInput = view.findViewById<TextInputEditText>(R.id.proxy_dialog_port)
        val portLayout = view.findViewById<TextInputLayout>(R.id.proxy_dialog_port_layout)
        val userInput = view.findViewById<TextInputEditText>(R.id.proxy_dialog_username)
        val passInput = view.findViewById<TextInputEditText>(R.id.proxy_dialog_password)

        val typeEntries = resources.getStringArray(R.array.proxy_type_entries)
        val typeValues = resources.getStringArray(R.array.proxy_type_entry_values)
        typeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, typeEntries)

        if (existing != null) {
            nameInput.setText(existing.displayName)
            hostInput.setText(existing.host)
            portInput.setText(existing.port)
            userInput.setText(existing.username)
            passInput.setText(existing.password)
            val typeIndex = typeValues.indexOf(existing.proxyType).coerceAtLeast(0)
            typeSpinner.setSelection(typeIndex)
        } else {
            portInput.setText(getString(R.string.proxy_port_default))
            val httpIndex = typeValues.indexOf(getString(R.string.proxy_type_http_value)).coerceAtLeast(0)
            typeSpinner.setSelection(httpIndex)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(
                if (existing == null) {
                    getString(R.string.proxy_add_profile)
                } else {
                    getString(R.string.proxy_edit_profile_title)
                }
            )
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)

        builder.setPositiveButton(getString(R.string.proxy_dialog_save)) { _, _ -> }

        if (existing != null) {
            builder.setNeutralButton(getString(R.string.proxy_dialog_delete), null)
        }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            nameLayout.error = null
            hostLayout.error = null
            portLayout.error = null
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val host = hostInput.text?.toString()?.trim().orEmpty()
            val port = portInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameLayout.error = getString(R.string.proxy_profile_name_hint)
                return@setOnClickListener
            }
            if (host.isEmpty()) {
                hostLayout.error = getString(R.string.proxy_host_summary)
                return@setOnClickListener
            }
            val portNum = port.toIntOrNull()
            if (portNum == null || portNum !in 1..65535) {
                portLayout.error = getString(R.string.proxy_port_summary)
                return@setOnClickListener
            }
            val typeIndex = typeSpinner.selectedItemPosition.coerceIn(typeValues.indices)
            val type = typeValues[typeIndex]
            val profile = ProxyProfile(
                id = existing?.id ?: UUID.randomUUID().toString(),
                displayName = name,
                proxyType = type.trim().lowercase(),
                host = host,
                port = port,
                username = userInput.text?.toString()?.trim().orEmpty(),
                password = passInput.text?.toString().orEmpty()
            )
            val list = ProxyProfileStore.readProfiles(context).toMutableList()
            if (existing != null) {
                val idx = list.indexOfFirst { it.id == existing.id }
                if (idx >= 0) {
                    list[idx] = profile
                } else {
                    list.add(profile)
                }
            } else {
                list.add(profile)
            }
            ProxyProfileStore.saveProfiles(context, list)
            if (existing == null && ProxyProfileStore.getActiveProfileId(context) == null && list.size == 1) {
                ProxyProfileStore.setActiveProfileId(context, profile.id)
            }
            DownloaderImpl.getInstance().updateNetworkConfiguration(context)
            dialog.dismiss()
            refreshProfileList()
        }

        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.proxy_delete_confirm_title)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val list = ProxyProfileStore.readProfiles(context).filter { it.id != existing.id }
                        ProxyProfileStore.saveProfiles(context, list)
                        val activeId = ProxyProfileStore.getActiveProfileId(context)
                        if (activeId == existing.id) {
                            ProxyProfileStore.setActiveProfileId(context, list.firstOrNull()?.id)
                        }
                        DownloaderImpl.getInstance().updateNetworkConfiguration(context)
                        dialog.dismiss()
                        refreshProfileList()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}

package com.passoassist.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.action_settings)

        val prefs = requireContext().getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        val editBaseUrl = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_base_url)
        val btnSave = view.findViewById<Button>(R.id.btn_save_base_url)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_base_url)
        val chkAdvanced = view.findViewById<CheckBox>(R.id.chk_advanced)
        val advancedContainer = view.findViewById<LinearLayout>(R.id.advanced_container)
        val editProtocol = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_protocol)
        val editDomain = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_domain)
        val editPort = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_port)
        val btnBuildFromParts = view.findViewById<Button>(R.id.btn_build_from_parts)

        val current = prefs.getString("base_url", "") ?: ""
        editBaseUrl.setText(current)
        editProtocol.setText(prefs.getString("protocol", "https") ?: "https")
        editDomain.setText(prefs.getString("domain", "") ?: "")
        editPort.setText(prefs.getString("port", "") ?: "")

        chkAdvanced.setOnCheckedChangeListener { _, isChecked ->
            advancedContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnBuildFromParts.setOnClickListener {
            val protocol = (editProtocol.text?.toString()?.trim()?.ifEmpty { "https" }) ?: "https"
            val domain = editDomain.text?.toString()?.trim().orEmpty()
            val port = editPort.text?.toString()?.trim().orEmpty()
            if (domain.isEmpty()) {
                editDomain.error = "Domain required"
                return@setOnClickListener
            }
            val url = if (port.isNotEmpty() && port != "80" && port != "443") {
                "$protocol://$domain:$port"
            } else {
                "$protocol://$domain"
            }
            editBaseUrl.setText(url)
        }

        btnSave.setOnClickListener {
            val value = editBaseUrl.text?.toString()?.trim().orEmpty()
            if (value.startsWith("http://") || value.startsWith("https://")) {
                // Derive protocol/domain/port
                try {
                    val uri = android.net.Uri.parse(value)
                    val protocol = uri.scheme ?: "https"
                    val domain = uri.host ?: ""
                    val port = if (uri.port != -1) uri.port.toString() else ""

                    prefs.edit()
                        .putString("protocol", protocol)
                        .putString("domain", domain)
                        .putString("port", port)
                        .putString("base_url", value)
                        .apply()

                    (activity as? MainActivity)?.reloadFromPreferences()
                } catch (_: Exception) { }
            } else {
                editBaseUrl.error = "Enter a valid http/https URL"
            }
        }

        btnClear.setOnClickListener {
            prefs.edit()
                .remove("protocol")
                .remove("domain")
                .remove("port")
                .remove("base_url")
                .apply()
            editBaseUrl.setText("")
            (activity as? MainActivity)?.reloadFromPreferences()
        }
    }
}






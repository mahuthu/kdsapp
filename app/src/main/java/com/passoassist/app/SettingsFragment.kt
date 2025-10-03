package com.passoassist.app

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
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
    companion object {
        private const val PREFS = "PassoAssistPrefs"
        private const val KEY_POLLING_INTERVAL = "polling_interval_seconds"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_URI = "sound_uri"
        private const val KEY_PRINTER_MAC = "printer_mac"
        private const val KEY_PAPER_WIDTH = "paper_width_mm"
        private const val KEY_VENDOR_PRINTER = "use_vendor_printer"
        private const val RINGTONE_REQUEST_CODE = 1001
    }
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

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editBaseUrl = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_base_url)
        val btnSave = view.findViewById<Button>(R.id.btn_save_base_url)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_base_url)
        val chkAdvanced = view.findViewById<CheckBox>(R.id.chk_advanced)
        val advancedContainer = view.findViewById<LinearLayout>(R.id.advanced_container)
        val editProtocol = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_protocol)
        val editDomain = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_domain)
        val editPort = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_port)
        val btnBuildFromParts = view.findViewById<Button>(R.id.btn_build_from_parts)

        val editPolling = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_polling_interval)
        val chkSound = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_sound)
        val btnSelectSound = view.findViewById<Button>(R.id.btn_select_sound)
        val txtSelectedSound = view.findViewById<TextView>(R.id.txt_selected_sound)
        val editPrinterMac = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_printer_mac)
        val btnTestPrint = view.findViewById<Button>(R.id.btn_test_print)
        val editPaperWidth = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_paper_width)
        val switchVendor = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vendor_printer)

        val current = prefs.getString("base_url", "") ?: ""
        editBaseUrl.setText(current)
        editProtocol.setText(prefs.getString("protocol", "https") ?: "https")
        editDomain.setText(prefs.getString("domain", "") ?: "")
        editPort.setText(prefs.getString("port", "") ?: "")

        val savedInterval = prefs.getInt(KEY_POLLING_INTERVAL, 10)
        editPolling.setText(savedInterval.toString())
        val soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        chkSound.isChecked = soundEnabled
        val soundUri = prefs.getString(KEY_SOUND_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString())
        txtSelectedSound.text = soundUri?.let { RingtoneManager.getRingtone(requireContext(), Uri.parse(it))?.getTitle(requireContext()) } ?: ""
        editPrinterMac.setText(prefs.getString(KEY_PRINTER_MAC, "") ?: "")
        editPaperWidth.setText((prefs.getInt(KEY_PAPER_WIDTH, 58)).toString())
        switchVendor.isChecked = prefs.getBoolean(KEY_VENDOR_PRINTER, true)

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

                    val interval = editPolling.text?.toString()?.toIntOrNull()?.coerceIn(5, 300) ?: 10

                    val paperWidth = editPaperWidth.text?.toString()?.toIntOrNull()?.coerceIn(48, 80) ?: 58
                    prefs.edit()
                        .putString("protocol", protocol)
                        .putString("domain", domain)
                        .putString("port", port)
                        .putString("base_url", value)
                        .putInt(KEY_POLLING_INTERVAL, interval)
                        .putBoolean(KEY_SOUND_ENABLED, chkSound.isChecked)
                        .putString(KEY_PRINTER_MAC, editPrinterMac.text?.toString()?.trim().orEmpty())
                        .putInt(KEY_PAPER_WIDTH, paperWidth)
                        .putBoolean(KEY_VENDOR_PRINTER, switchVendor.isChecked)
                        .apply()

                    (activity as? MainActivity)?.reloadFromPreferences()
                } catch (_: Exception) { }
            } else {
                editBaseUrl.error = getString(R.string.invalid_url)
            }
        }

        btnClear.setOnClickListener {
            prefs.edit()
                .remove("protocol")
                .remove("domain")
                .remove("port")
                .remove("base_url")
                .remove(KEY_POLLING_INTERVAL)
                .remove(KEY_SOUND_ENABLED)
                .remove(KEY_SOUND_URI)
                .remove(KEY_PRINTER_MAC)
                .apply()
            editBaseUrl.setText("")
            (activity as? MainActivity)?.reloadFromPreferences()
        }

        chkSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOUND_ENABLED, isChecked).apply()
        }

        btnSelectSound.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                val existing = prefs.getString(KEY_SOUND_URI, null)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing?.let { Uri.parse(it) })
            }
            startActivityForResult(intent, RINGTONE_REQUEST_CODE)
        }

        btnTestPrint.setOnClickListener {
            val pm = PrinterManager(requireContext())
            if (switchVendor.isChecked) {
                pm.printTestViaSystemPrint(
                    requireActivity(),
                    "Passo KDS",
                    "Test Print via Android Print Service"
                )
            } else {
                pm.print(PrintJob("TEST", "Passo KDS Test Print"))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_REQUEST_CODE && data != null) {
            val uri: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (uri != null) {
                prefs.edit().putString(KEY_SOUND_URI, uri.toString()).apply()
            } else {
                prefs.edit().remove(KEY_SOUND_URI).apply()
            }
            view?.findViewById<TextView>(R.id.txt_selected_sound)?.text = uri?.let { RingtoneManager.getRingtone(requireContext(), it)?.getTitle(requireContext()) } ?: ""
        }
    }
}






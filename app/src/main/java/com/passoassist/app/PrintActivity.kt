package com.passoassist.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

class PrintActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("PrintActivity", "PrintActivity onCreate called")
        
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val serial = intent.getStringExtra(EXTRA_SERIAL) ?: ""

        android.util.Log.d("PrintActivity", "Print job - Title: $title, Body: $body, Serial: $serial")

        if (serial.isBlank()) {
            android.util.Log.w("PrintActivity", "Invalid print job - serial is blank")
            Toast.makeText(this, "Invalid print job", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        android.util.Log.i("PrintActivity", "Starting print job for serial: $serial")
        
        try {
            PrinterManager(this).printTestViaSystemPrint(this, title, body)
            android.util.Log.i("PrintActivity", "Print job initiated successfully")
        } catch (e: Exception) {
            android.util.Log.e("PrintActivity", "Failed to initiate print job", e)
            Toast.makeText(this, "Print failed: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Send acknowledgment after print attempt
        sendAcknowledgment(serial, true)
        
        // Finish the activity after a longer delay to allow print dialog to appear
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.util.Log.d("PrintActivity", "Finishing PrintActivity after delay")
            finish()
        }, 15000) // Increased delay to 15 seconds
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("PrintActivity", "PrintActivity onDestroy called")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("PrintActivity", "PrintActivity onPause called")
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("PrintActivity", "PrintActivity onResume called")
    }

    private fun sendAcknowledgment(serial: String, success: Boolean) {
        val prefs = getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("base_url", null)
        
        if (baseUrl == null) {
            android.util.Log.w("PrintActivity", "Base URL not found in preferences")
            return
        }
        
        val ackUrl = HttpUrlHelper.join(baseUrl, "/kictchen/endpoints/update_kds_print.jsp")
        val params = mapOf(
            "action" to "update",
            "internalDispatchSerial" to serial
        )
        
        android.util.Log.i("PrintActivity", "Sending ack for serial: $serial, success: $success")
        android.util.Log.d("PrintActivity", "Ack URL: $ackUrl")
        android.util.Log.d("PrintActivity", "Ack params: $params")
        
        ApiClient.getInstance(this).postForm(ackUrl, params).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.w("PrintActivity", "Ack failed for serial $serial: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                android.util.Log.i("PrintActivity", "Ack response for serial $serial: ${response.code}")
                try {
                    val responseBody = response.body?.string() ?: ""
                    android.util.Log.d("PrintActivity", "Ack response body: $responseBody")
                } catch (e: Exception) {
                    android.util.Log.w("PrintActivity", "Failed to read response body", e)
                }
                response.close()
            }
        })
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_BODY = "extra_body"
        private const val EXTRA_SERIAL = "extra_serial"

        fun createIntent(context: Context, title: String, body: String, serial: String): Intent {
            return Intent(context, PrintActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_SERIAL, serial)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}







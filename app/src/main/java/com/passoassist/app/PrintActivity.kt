package com.passoassist.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

class PrintActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        PrinterManager(this).printTestViaSystemPrint(this, title, body)

        finish()
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_BODY = "extra_body"

        fun createIntent(context: Context, title: String, body: String): Intent {
            return Intent(context, PrintActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}







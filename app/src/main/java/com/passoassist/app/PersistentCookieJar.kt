package com.passoassist.app

import android.content.Context
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val serialized = cookies.joinToString("||") { cookie -> Base64.encodeToString(cookie.toString().toByteArray(), Base64.NO_WRAP) }
        prefs.edit().putString("cookies", serialized).apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val serialized = prefs.getString("cookies", null) ?: return emptyList()
        return serialized.split("||").mapNotNull {
            runCatching {
                val decoded = String(Base64.decode(it, Base64.NO_WRAP))
                Cookie.parse(url, decoded)
            }.getOrNull()
        }
    }
}












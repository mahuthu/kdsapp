package com.passoassist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicInteger

class PollingService : Service() {
    private lateinit var api: ApiClient
    private lateinit var printer: PrinterManager
    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()
    private var timer: Timer? = null
    private val failureStreak = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        api = ApiClient.getInstance(this)
        printer = PrinterManager(this)
        createNotificationChannel()
        startForeground(1, buildNotification("Waiting for jobs"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        schedulePolling()
        return START_STICKY
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun schedulePolling() {
        timer?.cancel()
        val intervalSeconds = prefs.getInt("polling_interval_seconds", 10).coerceIn(5, 300)
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() { pollOnce() }
            }, 0L, intervalSeconds * 1000L)
        }
    }

    private fun pollOnce() {
        val baseUrl = prefs.getString("base_url", null) ?: return
        val url = api.buildPollingUrl(baseUrl)
        api.get(url).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handleFailure()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        handleFailure(); return
                    }
                    val body = it.body?.string().orEmpty()
                    Log.i("PollingService", "poll ok, bytes=${body.length}")
                    if (body.isBlank()) return
                    try {
                        // Try structured OrderJob list, then single object, then simple PrintJob list
                        val ordersType = object : TypeToken<List<OrderJob>>() {}.type
                        val orders: List<OrderJob>? = runCatching { gson.fromJson<List<OrderJob>>(body, ordersType) }.getOrNull()
                        if (!orders.isNullOrEmpty()) {
                            Log.i("PollingService", "parsed OrderJob list size=${orders.size}")
                            notifyNewJobs(orders.size)
                            orders.forEach { o ->
                                val serial = o.InternalDispatchSerial ?: o.SalesOrderSerial ?: ""
                                val success = printer.printOrder(o)
                                if (serial.isNotBlank()) sendAck(baseUrl, serial, success)
                            }
                        } else if (body.trimStart().startsWith("{")) {
                            val single: OrderJob? = runCatching { gson.fromJson(body, OrderJob::class.java) }.getOrNull()
                            if (single != null) {
                                Log.i("PollingService", "parsed single OrderJob")
                                notifyNewJobs(1)
                                val serial = single.InternalDispatchSerial ?: single.SalesOrderSerial ?: ""
                                val success = printer.printOrder(single)
                                if (serial.isNotBlank()) sendAck(baseUrl, serial, success)
                            }
                        } else {
                            val listType = object : TypeToken<List<PrintJob>>() {}.type
                            val jobs: List<PrintJob> = gson.fromJson(body, listType) ?: emptyList()
                            Log.i("PollingService", "parsed simple jobs size=${jobs.size}")
                            if (jobs.isEmpty()) return
                            notifyNewJobs(jobs.size)
                            jobs.forEach { job ->
                                val success = printer.print(job)
                                sendAck(baseUrl, job.internaldispatchserial, success)
                            }
                        }
                        failureStreak.set(0)
                    } catch (_: Exception) { }
                }
            }
        })
    }

    private fun handleFailure() {
        val streak = failureStreak.incrementAndGet()
        if (streak % 5 == 0) {
            // attempt to recreate timer to resync
            schedulePolling()
        }
    }

    private fun sendAck(baseUrl: String, serial: String, success: Boolean) {
        val ackUrl = HttpUrlHelper.join(baseUrl, "/kictchen/endpoints/update_kds_print.jsp")
        val payload = AckResponse(if (success) "success" else "failure", serial)
        api.postJson(ackUrl, gson.toJson(payload)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun notifyNewJobs(count: Int) {
        if (!prefs.getBoolean("sound_enabled", true)) return
        val soundUri = prefs.getString("sound_uri", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString())
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification("$count new print job(s)", soundUri)
        nm.notify(2, notification)
    }

    private fun buildNotification(text: String, soundUri: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (soundUri != null) {
            builder.setSound(android.net.Uri.parse(soundUri))
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Passo KDS", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "passo_kds_polling"
    }
}

object HttpUrlHelper {
    fun join(baseUrl: String, path: String): String {
        val trimmedBase = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val trimmedPath = if (path.startsWith("/")) path else "/$path"
        return trimmedBase + trimmedPath
    }
}



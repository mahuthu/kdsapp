package com.passoassist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.net.Uri
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
    private val seenPrinted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val seenAcked: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        api = ApiClient.getInstance(this)
        printer = PrinterManager(this)
        loadSeenState()
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
                        // Try to parse the new JSON format with Dispatch keys first
                        val jsonObject = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                        val dispatches = mutableListOf<Pair<OrderJob, String>>()
                        
                        // Debug: Log the JSON structure
                        Log.d("PollingService", "JSON keys: ${jsonObject.keySet()}")
                        
                        // Extract all dispatch objects
                        jsonObject.entrySet().forEach { entry ->
                            if (entry.key.startsWith("Dispatch")) {
                                Log.d("PollingService", "Processing ${entry.key}, value type: ${entry.value.javaClass.simpleName}")
                                if (entry.value.isJsonObject) {
                                    val dispatchJson = entry.value.asJsonObject
                                    val orderJob = parseDispatchToOrderJob(dispatchJson)
                                    if (orderJob != null) {
                                        val bodyText = buildPrintBodyFromDispatch(dispatchJson, orderJob)
                                        dispatches.add(orderJob to bodyText)
                                    }
                                } else {
                                    Log.w("PollingService", "Dispatch ${entry.key} is not a JSON object: ${entry.value}")
                                }
                            }
                        }
                        
                        if (dispatches.isNotEmpty()) {
                            Log.i("PollingService", "parsed ${dispatches.size} dispatch jobs")
                            dispatches.forEach { (orderJob, bodyText) ->
                                val serial = orderJob.InternalDispatchSerial ?: orderJob.SalesOrderSerial ?: ""
                                if (serial.isBlank()) return@forEach
                                
                                // Only notify if we haven't seen this job before
                                if (!hasPrinted(serial)) {
                                    notifyNewJobs(1)
                                    postPrintActionNotification(orderJob, bodyText)
                                    markPrinted(serial)
                                }
                                // Note: Acknowledgment will be sent by PrintActivity after user prints
                            }
                        } else {
                            // Fallback to original parsing logic for backward compatibility
                            val ordersType = object : TypeToken<List<OrderJob>>() {}.type
                            val orders: List<OrderJob>? = runCatching { gson.fromJson<List<OrderJob>>(body, ordersType) }.getOrNull()
                            if (!orders.isNullOrEmpty()) {
                                Log.i("PollingService", "parsed OrderJob list size=${orders.size}")
                                orders.forEach { o ->
                                    val serial = o.InternalDispatchSerial ?: o.SalesOrderSerial ?: ""
                                    if (serial.isBlank()) return@forEach
                                    if (!hasPrinted(serial)) {
                                        notifyNewJobs(1)
                                        postPrintActionNotification(o)
                                        val success = printer.printOrder(o)
                                        markPrinted(serial)
                                    }
                                    if (!hasAcked(serial)) {
                                        sendAck(baseUrl, serial, true)
                                    }
                                }
                            } else if (body.trimStart().startsWith("{")) {
                                val single: OrderJob? = runCatching { gson.fromJson(body, OrderJob::class.java) }.getOrNull()
                                if (single != null) {
                                    Log.i("PollingService", "parsed single OrderJob")
                                    val serial = single.InternalDispatchSerial ?: single.SalesOrderSerial ?: ""
                                    if (serial.isBlank()) return@use
                                    if (!hasPrinted(serial)) {
                                        notifyNewJobs(1)
                                        postPrintActionNotification(single)
                                        val success = printer.printOrder(single)
                                        markPrinted(serial)
                                    }
                                    if (!hasAcked(serial)) {
                                        sendAck(baseUrl, serial, true)
                                    }
                                }
                            } else {
                                val listType = object : TypeToken<List<PrintJob>>() {}.type
                                val jobs: List<PrintJob> = gson.fromJson(body, listType) ?: emptyList()
                                Log.i("PollingService", "parsed simple jobs size=${jobs.size}")
                                if (jobs.isEmpty()) return
                                jobs.forEach { job ->
                                    val serial = job.internaldispatchserial
                                    if (!hasPrinted(serial)) {
                                        notifyNewJobs(1)
                                        postPrintActionNotification(
                                            OrderJob(
                                                SalesOrderSerial = serial,
                                                PersonnelName = null,
                                                InternalDispatchSerial = serial,
                                                SequenceNumber = null,
                                                AddedTime = null,
                                                BranchName = getString(R.string.app_name),
                                                ItemsCount = 1,
                                                StatusTitle = StatusTitle(null, job.content, null, 1, null, null)
                                            )
                                        )
                                        val success = printer.print(job)
                                        markPrinted(serial)
                                    }
                                    if (!hasAcked(serial)) {
                                        sendAck(baseUrl, serial, true)
                                    }
                                }
                            }
                        }
                        failureStreak.set(0)
                    } catch (e: Exception) {
                        Log.e("PollingService", "Error parsing jobs: ${e.message}", e)
                    }
                }
            }
        })
    }

    private fun parseDispatchToOrderJob(dispatchJson: com.google.gson.JsonObject): OrderJob? {
        return try {
            val salesOrderSerial = dispatchJson.get("SalesOrderSerial")?.asString
            val personnelName = dispatchJson.get("PersonnelName")?.asString
            val internalDispatchSerial = dispatchJson.get("InternalDispatchSerial")?.asString
            val sequenceNumber = dispatchJson.get("SequenceNumber")?.asString
            val addedTime = dispatchJson.get("AddedTime")?.asString
            val branchName = dispatchJson.get("BranchName")?.asString
            val itemsCount = dispatchJson.get("ItemsCount")?.asInt ?: 0
            val statusTitle = dispatchJson.get("StatusTitle")?.asString
            
            // Build item list from Item X entries
            val items = mutableListOf<StatusTitle>()
            dispatchJson.entrySet().forEach { entry ->
                if (entry.key.startsWith("Item")) {
                    // Check if the value is a JSON object before trying to parse it
                    if (entry.value.isJsonObject) {
                        val itemJson = entry.value.asJsonObject
                        val item = StatusTitle(
                            Description = itemJson.get("Description")?.asString,
                            ProductName = itemJson.get("ProductName")?.asString,
                            OptionalProducts = itemJson.get("OptionalProducts")?.asString,
                            Quantity = itemJson.get("Quantity")?.asInt ?: 1,
                            ProductDescription = itemJson.get("ProductDescription")?.asString,
                            OptionsQuantity = itemJson.get("OptionsQuantity")?.asString
                        )
                        items.add(item)
                    } else {
                        // Handle non-object values (like numbers) - skip them
                        Log.d("PollingService", "Skipping non-object Item entry: ${entry.key} = ${entry.value}")
                    }
                }
            }
            
            // Use first item as primary item for display
            val primaryItem = items.firstOrNull()
            
            OrderJob(
                SalesOrderSerial = salesOrderSerial,
                PersonnelName = personnelName,
                InternalDispatchSerial = internalDispatchSerial,
                SequenceNumber = sequenceNumber,
                AddedTime = addedTime,
                BranchName = branchName,
                ItemsCount = itemsCount,
                StatusTitle = primaryItem
            )
        } catch (e: Exception) {
            Log.e("PollingService", "Error parsing dispatch item: ${e.message}", e)
            null
        }
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
        val params = mapOf(
            "action" to "update",
            "internalDispatchSerial" to serial
        )
        Log.i("PollingService", "ack(form) -> $ackUrl params=$params")
        api.postForm(ackUrl, params).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("PollingService", "ack failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                Log.i("PollingService", "ack <- code=${response.code}")
                if (response.isSuccessful) {
                    markAcked(serial)
                }
                response.close()
            }
        })
    }

    
    private fun notifyNewJobs(count: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = prefs.getString("sound_uri", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString())
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getOrCreateAlertChannelId() else CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$count new print job(s)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (prefs.getBoolean("sound_enabled", true) && soundUri != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(Uri.parse(soundUri))
        }
        nm.notify(2, builder.build())
    }

    private fun postPrintActionNotification(order: OrderJob, bodyOverride: String? = null) {
        val title = (order.BranchName ?: getString(R.string.app_name)).ifBlank { getString(R.string.app_name) }
        val name = order.StatusTitle?.ProductName?.ifBlank { null } ?: "Order"
        val qty = order.StatusTitle?.Quantity ?: order.ItemsCount ?: 1
        val serial = order.InternalDispatchSerial ?: order.SalesOrderSerial ?: ""
        val body = bodyOverride ?: buildString {
            append(name).append(" x").append(qty)
            if (!serial.isBlank()) append("  (#").append(serial).append(")")
        }

        Log.d("PollingService", "Creating notification for serial: $serial, title: $title, body: $body")

        val intent = PrintActivity.createIntent(this, title, body, serial)
        val pi = PendingIntent.getActivity(this, serial.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getOrCreateAlertChannelId() else CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Tap to print: $body")
            .setContentIntent(pi) // Make the entire notification clickable
            .addAction(0, "Print", pi) // Keep the action button as well
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        val soundUri = prefs.getString("sound_uri", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString())
        if (prefs.getBoolean("sound_enabled", true) && soundUri != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(Uri.parse(soundUri))
        }
        nm.notify(serial.hashCode(), builder.build())
        
        Log.d("PollingService", "Notification posted with ID: ${serial.hashCode()}")
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Passo KDS Foreground", NotificationManager.IMPORTANCE_LOW)
            channel.enableLights(true)
            channel.enableVibration(true)
            channel.setSound(null, null) // Foreground channel should not play sounds
            nm.createNotificationChannel(channel)
            Log.d("PollingService", "Foreground notification channel ensured")
        }
    }

    private fun getOrCreateAlertChannelId(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_ID
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundEnabled = prefs.getBoolean("sound_enabled", true)
        val soundUriStr = prefs.getString("sound_uri", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString())
        val suffix = if (soundEnabled && soundUriStr != null) ("snd_" + soundUriStr.hashCode().toString()) else "silent"
        val alertChannelId = "${CHANNEL_ID}_alert_${suffix}"
        val existing = nm.getNotificationChannel(alertChannelId)
        if (existing == null) {
            val channel = NotificationChannel(alertChannelId, "Passo KDS Alerts", NotificationManager.IMPORTANCE_HIGH)
            channel.enableLights(true)
            channel.enableVibration(true)
            if (soundEnabled && soundUriStr != null) {
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                channel.setSound(Uri.parse(soundUriStr), audioAttrs)
            } else {
                channel.setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
        return alertChannelId
    }

    private fun buildPrintBodyFromDispatch(dispatchJson: com.google.gson.JsonObject, job: OrderJob): String {
        val header = buildString {
            val branch = job.BranchName ?: ""
            if (branch.isNotBlank()) append(branch).append('\n')
            val time = (job.AddedTime ?: "").replace('T', ' ').substringBefore('.')
            if (time.isNotBlank()) append("Time: ").append(time).append('\n')
            append("Order: ").append(job.SalesOrderSerial.orEmpty())
            append("   Seq: ").append(job.SequenceNumber.orEmpty())
            append("   Disp: ").append(job.InternalDispatchSerial.orEmpty())
        }

        val items = mutableListOf<String>()
        dispatchJson.entrySet().forEach { entry ->
            if (entry.key.startsWith("Item") && entry.value.isJsonObject) {
                val it = entry.value.asJsonObject
                val name = it.get("ProductName")?.asString?.trim().orEmpty()
                val qty = it.get("Quantity")?.asInt ?: 1
                if (name.isNotEmpty()) items.add("$name x$qty")
            }
        }
        val body = StringBuilder()
        body.append(header)
        if (items.isNotEmpty()) {
            body.append('\n')
            items.forEach { line -> body.append(line).append('\n') }
        }
        return body.toString().trimEnd()
    }

    private fun hasPrinted(serial: String): Boolean = seenPrinted.contains(serial)
    private fun hasAcked(serial: String): Boolean = seenAcked.contains(serial)

    private fun markPrinted(serial: String) {
        seenPrinted.add(serial)
        persistSeenState()
    }

    private fun markAcked(serial: String) {
        seenAcked.add(serial)
        persistSeenState()
    }

    private fun loadSeenState() {
        prefs.getString("seen_printed", null)?.let { csv ->
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seenPrinted.add(it) }
        }
        prefs.getString("seen_acked", null)?.let { csv ->
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seenAcked.add(it) }
        }
        trimSeen()
    }

    private fun persistSeenState() {
        trimSeen()
        prefs.edit()
            .putString("seen_printed", seenPrinted.joinToString(","))
            .putString("seen_acked", seenAcked.joinToString(","))
            .apply()
    }

    private fun trimSeen() {
        fun trim(set: MutableSet<String>) {
            if (set.size > 200) {
                val list = set.toList()
                val last = list.drop(list.size - 200)
                set.clear()
                set.addAll(last)
            }
        }
        trim(seenPrinted)
        trim(seenAcked)
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



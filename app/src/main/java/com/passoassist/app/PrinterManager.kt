package com.passoassist.app

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID

class PrinterManager(private val context: Context) {
    private fun useVendorPrinter(): Boolean {
        val prefs = context.getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("use_vendor_printer", true)
    }

    fun print(job: PrintJob): Boolean {
        if (useVendorPrinter()) {
            return printViaVendor(job.content)
        }
        val prefs = context.getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        val mac = prefs.getString("printer_mac", null)
        val paperWidth = prefs.getInt("paper_width_mm", 58)
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false

        val device: BluetoothDevice = when {
            mac?.isNotBlank() == true -> adapter.getRemoteDevice(mac)
            else -> adapter.bondedDevices?.firstOrNull() ?: return false
        }

        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        return try {
            adapter.cancelDiscovery()
            socket.connect()
            val out = socket.outputStream
            sendEscPosHeader(out)
            writeText(out, job.content, paperWidth)
            sendEscPosFooter(out)
            out.flush()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendEscPosHeader(out: OutputStream) {
        // Initialize printer
        out.write(byteArrayOf(0x1B, 0x40))
        // Align left
        out.write(byteArrayOf(0x1B, 0x61, 0x00))
        // Character codepage (PC437 default)
        out.write(byteArrayOf(0x1B, 0x74, 0x00))
    }

    private fun writeText(out: OutputStream, text: String, paperWidthMm: Int) {
        val charset = Charset.forName("UTF-8")
        val wrapped = wrapTextForWidth(text, paperWidthMm)
        out.write(wrapped.toByteArray(charset))
        // New line
        out.write(byteArrayOf(0x0A))
    }

    private fun sendEscPosFooter(out: OutputStream) {
        // Feed and cut (partial cut if supported)
        out.write(byteArrayOf(0x1B, 0x64, 0x03)) // feed 3 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x01)) // partial cut
    }

    private fun wrapTextForWidth(text: String, paperWidthMm: Int): String {
        // Approximate characters per line for 58mm: ~32, for 80mm: ~48
        val charsPerLine = if (paperWidthMm >= 72) 48 else 32
        val result = StringBuilder()
        var idx = 0
        while (idx < text.length) {
            val end = (idx + charsPerLine).coerceAtMost(text.length)
            result.append(text.substring(idx, end))
            result.append('\n')
            idx = end
        }
        return result.toString()
    }

    fun printOrder(job: OrderJob): Boolean {
        if (useVendorPrinter()) {
            val line = buildString {
                val branch = job.BranchName ?: ""
                if (branch.isNotEmpty()) append(branch).append('\n')
                append("Order: ")
                append(job.SalesOrderSerial.orEmpty())
                append("   Seq: ")
                append(job.SequenceNumber.orEmpty())
                append("   Disp: ")
                append(job.InternalDispatchSerial.orEmpty())
                append('\n')
                val time = (job.AddedTime ?: "").replace('T', ' ').substringBefore('.')
                if (time.isNotEmpty()) append("Time: ").append(time).append('\n')
                val name = job.StatusTitle?.ProductName?.ifBlank { null }
                val qty = job.StatusTitle?.Quantity ?: job.ItemsCount ?: 1
                if (name != null) append(name).append(" x").append(qty).append('\n')
                val desc = job.StatusTitle?.ProductDescription ?: ""
                if (desc.isNotBlank()) append(desc).append('\n')
                val opts = job.StatusTitle?.OptionalProducts ?: ""
                if (opts.isNotBlank()) append(opts).append('\n')
            }
            return printViaVendor(line)
        }
        val prefs = context.getSharedPreferences("PassoAssistPrefs", Context.MODE_PRIVATE)
        val mac = prefs.getString("printer_mac", null)
        val paperWidth = prefs.getInt("paper_width_mm", 58)
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false

        val device: BluetoothDevice = when {
            mac?.isNotBlank() == true -> adapter.getRemoteDevice(mac)
            else -> adapter.bondedDevices?.firstOrNull() ?: return false
        }

        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        return try {
            adapter.cancelDiscovery()
            socket.connect()
            val out = socket.outputStream
            sendEscPosHeader(out)

            // Center + bold BranchName
            setAlignCenter(out)
            setBold(out, true)
            val branch = (job.BranchName ?: "").ifBlank { "" }
            if (branch.isNotEmpty()) writeText(out, branch, paperWidth)
            setBold(out, false)

            // Order line
            setAlignLeft(out)
            val order = "Order: ${job.SalesOrderSerial.orEmpty()}   Seq: ${job.SequenceNumber.orEmpty()}   Disp: ${job.InternalDispatchSerial.orEmpty()}"
            writeText(out, order, paperWidth)

            // Time
            val time = (job.AddedTime ?: "").replace('T', ' ').substringBefore('.')
            if (time.isNotEmpty()) writeText(out, "Time: $time", paperWidth)

            out.write(byteArrayOf(0x0A))

            // Item
            val name = job.StatusTitle?.ProductName?.ifBlank { null }
            val qty = job.StatusTitle?.Quantity ?: job.ItemsCount ?: 1
            if (name != null) {
                setBold(out, true)
                writeText(out, "$name x$qty", paperWidth)
                setBold(out, false)
            }
            val desc: String = job.StatusTitle?.ProductDescription ?: ""
            if (desc.isNotBlank()) writeText(out, desc, paperWidth)
            val opts: String = job.StatusTitle?.OptionalProducts ?: ""
            if (opts.isNotBlank()) writeText(out, opts, paperWidth)

            sendEscPosFooter(out)
            out.flush()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    // Best-effort vendor print via implicit service intent
    private fun printViaVendor(text: String): Boolean {
        return try {
            // Try direct startService with action or component if available
            val i = Intent().apply {
                action = "com.incar.printerservice.IPrinterService"
                `package` = "com.incar.printerservice"
                putExtra("text", text)
            }
            val res = context.startService(i)
            res != null
        } catch (_: Exception) {
            false
        }
    }

    // PrintManager-based Test Print (UI flow). For testing with vendor PrintService plugin.
    fun printTestViaSystemPrint(activityContext: Context, title: String, body: String) {
        val printManager = activityContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            private var pdfDocument: PdfDocument? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: android.os.Bundle?
            ) {
                pdfDocument = PdfDocument()
                val info = PrintDocumentInfo.Builder("passo_kds_test.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: android.os.ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val pageInfo = PdfDocument.PageInfo.Builder(384, 600, 1).create()
                val page = pdfDocument!!.startPage(pageInfo)
                val canvas = page.canvas

                val paint = Paint().apply {
                    isAntiAlias = true
                    textSize = 18f
                }
                val bold = Paint(paint).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }

                var y = 28
                // Title centered
                drawCenteredText(canvas.width, title, bold, canvas, y)
                y += 28
                // Body wrapped
                y = drawWrappedText(canvas.width, body, paint, canvas, y, 6)

                pdfDocument!!.finishPage(page)
                destination?.let {
                    pdfDocument!!.writeTo(android.os.ParcelFileDescriptor.AutoCloseOutputStream(it))
                }
                pdfDocument!!.close()
                pdfDocument = null
                callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            }

            private fun drawCenteredText(width: Int, text: String, paint: Paint, canvas: android.graphics.Canvas, y: Int) {
                val bounds = Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                val x = (width - bounds.width()) / 2f
                canvas.drawText(text, x, y.toFloat(), paint)
            }

            private fun drawWrappedText(width: Int, text: String, paint: Paint, canvas: android.graphics.Canvas, startY: Int, lineSpacing: Int): Int {
                val words = text.split(" ")
                val sb = StringBuilder()
                var y = startY
                for (w in words) {
                    val next = if (sb.isEmpty()) w else sb.toString() + " " + w
                    if (paint.measureText(next) > width - 16) {
                        canvas.drawText(sb.toString(), 8f, y.toFloat(), paint)
                        y += (paint.textSize + lineSpacing).toInt()
                        sb.clear()
                        sb.append(w)
                    } else {
                        if (sb.isEmpty()) sb.append(w) else sb.append(' ').append(w)
                    }
                }
                if (sb.isNotEmpty()) {
                    canvas.drawText(sb.toString(), 8f, y.toFloat(), paint)
                    y += (paint.textSize + lineSpacing).toInt()
                }
                return y
            }
        }

        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print("Passo KDS Test", adapter, attrs)
    }

    private fun setAlignCenter(out: OutputStream) { out.write(byteArrayOf(0x1B, 0x61, 0x01)) }
    private fun setAlignLeft(out: OutputStream) { out.write(byteArrayOf(0x1B, 0x61, 0x00)) }
    private fun setBold(out: OutputStream, on: Boolean) { out.write(byteArrayOf(0x1B, 0x45, if (on) 0x01 else 0x00)) }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}



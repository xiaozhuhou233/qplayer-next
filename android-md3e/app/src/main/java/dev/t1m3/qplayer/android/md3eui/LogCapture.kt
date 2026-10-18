// Ⓜ 2026-10-05: the listener's log system（「在设置里添加一个日志开关，打开即可记录程序从
// 开始跑到退出的所有日志……要求日志记录所有报错和警告」）. v2 after the field test
// （「日志是空的」）: three sources feed one session file —
//   1. the in-memory Logger ring, flushed at start, so enabling the switch mid-run
//      still yields everything the app logged since process start;
//   2. a tee on the shared Logger, so every later line lands verbatim;
//   3. the process's own logcat stream (own pid — readable without READ_LOGS), so
//      anything logged OUTSIDE the shared logger (frameworks, media, System.err)
//      is captured too; lines from our own tag are skipped since the tee already
//      writes them with better formatting.
package dev.t1m3.qplayer.android.md3eui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import dev.t1m3.qplayer.util.Logger
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object LogCapture {

    private const val TS = "yyyyMMdd-HHmmss"
    private const val SOFT_CAP_BYTES = 16L * 1024 * 1024
    private var writer: BufferedWriter? = null
    private var sessionFile: File? = null
    private var priorHandler: Thread.UncaughtExceptionHandler? = null
    private var hookInstalled = false
    private var logcatProcess: java.lang.Process? = null
    private var logcatThread: Thread? = null
    private var written = 0L
    private var capped = false
    private val ownLogTag = Regex("(?:[VDIWEAF]/(?:musicplayer|QPlayerGlass)\\s*\\(\\s*\\d+\\)\\s*:|\\s(?:musicplayer|QPlayerGlass)\\s*:)")

    val active: Boolean get() = writer != null

    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        if (on == active) return
        if (on) start(context) else stop()
    }

    private fun start(context: Context) {
        val dir = File(context.filesDir, "logs").apply { if (!isDirectory) mkdirs() }
        val stamp = SimpleDateFormat(TS, Locale.US).format(Date())
        val f = File(dir, "session-$stamp.log")
        try {
            val w = BufferedWriter(FileWriter(f, true))
            writer = w
            sessionFile = f
            written = 0L
            capped = false
            lineTo(w, "=== qplayer session $stamp ===")
            // Source 1: everything the shared Logger kept before the switch was on.
            val history = Logger.snapshot()
            if (history.isNotEmpty()) {
                lineTo(w, "--- logger history (${history.size} lines from process start) ---")
                history.forEach { lineTo(w, it) }
                lineTo(w, "--- live capture begins ---")
            }
            Logger.setSink(TeeSink(Logger.getSink()))
            installCrashHook()
            Logger.warn("log capture started -> {}", f.absolutePath)
            startLogcat()
        } catch (failure: Exception) {
            Logger.warn("log capture could not start ({}): {}", f.absolutePath, failure.toString())
        }
    }

    private fun stop() {
        stopLogcat()
        try {
            Logger.warn("log capture stopped")
        } catch (_: Exception) {
        }
        Logger.setSink(null)
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        if (hookInstalled) {
            Thread.setDefaultUncaughtExceptionHandler(priorHandler)
            hookInstalled = false
            priorHandler = null
        }
    }

    private fun startLogcat() {
        try {
            val process = ProcessBuilder(
                "/system/bin/logcat", "-v", "time", "--pid=${Process.myPid()}"
            ).redirectErrorStream(true).start()
            logcatProcess = process
            val thread = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            // Our own tag already arrives through the tee, richer.
                            if (ownLogTag.containsMatchIn(raw)) return@forEach
                            line(raw)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            thread.isDaemon = true
            thread.start()
            logcatThread = thread
            Logger.warn("log capture is also streaming this process's logcat (pid {})",
                Process.myPid())
        } catch (failure: Exception) {
            Logger.warn("logcat stream unavailable ({}); the logger tee still captures",
                failure.toString())
        }
    }

    private fun stopLogcat() {
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        logcatThread = null
    }

    private fun installCrashHook() {
        if (hookInstalled) return
        priorHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            line("E|CRASH on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
            line(throwable.stackTraceToString())
            runCatching { writer?.flush() }
            priorHandler?.uncaughtException(thread, throwable)
        }
        hookInstalled = true
    }

    @Synchronized
    private fun line(text: String) {
        val w = writer ?: return
        lineTo(w, text)
    }

    private fun lineTo(w: BufferedWriter, text: String) {
        try {
            if (written >= SOFT_CAP_BYTES) {
                if (!capped) {
                    capped = true
                    w.write("(log truncated at ${SOFT_CAP_BYTES / (1024 * 1024)} MB)")
                    w.newLine()
                    w.flush()
                }
                return
            }
            w.write(text)
            w.newLine()
            written += text.length + 1
        } catch (_: Exception) {
        }
    }

    /** Copy the newest session file out. Returns where it landed, or null when
     *  there is nothing to export. */
    @Synchronized
    fun export(context: Context): String? {
        val src = newestSession(context) ?: return null
        runCatching { writer?.flush() }
        if (src.length() <= 0L) return null
        val stamp = SimpleDateFormat(TS, Locale.US).format(Date())
        val name = "qplayer-log-${src.nameWithoutExtension.takeLast(15)}-$stamp.log"
        // MediaStore first; the app-external folder as the documented fallback,
        // and the byte count is verified either way so a silent empty write can
        // never be reported as success.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val stream = context.contentResolver.openOutputStream(uri)
                    if (stream != null) {
                        stream.use { out -> src.inputStream().use { it.copyTo(out) } }
                        if (copiedOk(src, context, uri)) return "下载/$name"
                        context.contentResolver.delete(uri, null, null)
                    } else {
                        context.contentResolver.delete(uri, null, null)
                    }
                }
            } catch (failure: Exception) {
                Logger.warn("log export via MediaStore failed: {}", failure.toString())
            }
        }
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "logs")
            if (!dir.isDirectory) dir.mkdirs()
            val out = File(dir, name)
            src.copyTo(out, overwrite = true)
            if (out.length() != src.length()) null else out.absolutePath
        } catch (failure: Exception) {
            Logger.warn("log export failed: {}", failure.toString())
            null
        }
    }

    private fun copiedOk(src: File, context: Context, uri: android.net.Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } == src.length()
    } catch (_: Exception) {
        false
    }

    private fun newestSession(context: Context): File? =
        File(context.filesDir, "logs").listFiles { f -> f.name.startsWith("session-") }
            ?.maxByOrNull { it.lastModified() }

    /** Forwards to the backend installed before the capture and appends the same
     *  line to the session file. */
    private class TeeSink(private val prior: Logger.Sink) : Logger.Sink {
        private val stamp = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

        override fun log(level: String, message: String) {
            prior.log(level, message)
            line("${stamp.get().format(Date())} $level $message")
        }

        override fun exception(message: String, t: Throwable) {
            prior.exception(message, t)
            line("${stamp.get().format(Date())} E $message")
            line(t.stackTraceToString())
        }
    }
}

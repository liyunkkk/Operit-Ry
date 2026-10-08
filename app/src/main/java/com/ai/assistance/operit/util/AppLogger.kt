package com.ai.assistance.operit.util

import android.content.Context
import android.util.Log
import android.os.Looper
import com.ai.assistance.operit.core.application.OperitApplication
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * App-wide logger with an API closely mirroring [com.ai.assistance.operit.util.AppLogger].
 *
 * It forwards all logs to the system Log and also persists them to
 * an internal file so that the app can export logs for debugging.
 */
object AppLogger {

    // Mirror com.ai.assistance.operit.util.AppLogger priority constants
    const val VERBOSE: Int = Log.VERBOSE
    const val DEBUG: Int = Log.DEBUG
    const val INFO: Int = Log.INFO
    const val WARN: Int = Log.WARN
    const val ERROR: Int = Log.ERROR
    const val ASSERT: Int = Log.ASSERT

    // Log file configuration
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "operit.log"
    private const val PACKAGE_LOG_DIR_NAME = "packageLogs"
    private const val TOOLPKG_LOG_TAG = "ToolPkg"
    private const val MAX_LOG_MESSAGE_CHARS = 12_000
    private const val MAX_LOG_THROWABLE_CHARS = 24_000

    // Simple date formatter for log lines
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val startupFileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val packageIdRegexes = listOf(
        Pattern.compile("""\btoolPkgId=([A-Za-z0-9._:-]+)\b"""),
        Pattern.compile("""\bpackage(?:/subpackage)?=([A-Za-z0-9._:-]+)\b"""),
        Pattern.compile("""\bcontainer=([A-Za-z0-9._:-]+)\b"""),
        Pattern.compile("""\btarget=([A-Za-z0-9._:-]+)\b""")
    )
    private val scriptRegexes = listOf(
        Pattern.compile("""\bscript=([^\s,]+)"""),
        Pattern.compile("""\bpath=([^\s,]+)"""),
        Pattern.compile("""\bscreen=([^\s,]+)"""),
        Pattern.compile("""\bfunction=([A-Za-z0-9_.$:-]+)\b""")
    )
    private val pluginRegexes = listOf(
        Pattern.compile("""\bplugin=([A-Za-z0-9._:-]+)\b"""),
        Pattern.compile("""\bpluginId=([A-Za-z0-9._:-]+)\b"""),
        Pattern.compile("""\bhookId=([A-Za-z0-9._:-]+)\b""")
    )

    /**
     * Optional external switch to completely disable file logging if needed.
     * System AppLogger.* calls will still be performed.
     */
    @Volatile
    var enableFileLogging: Boolean = true

    /**
     * 请求体转储开关，默认开启；同时门控本地 llama.cpp 的完整 prompt。
     *
     * 转储占导出日志的约 90%（实测一次 27 MB 的会话里占 24 MB），但排障经常需要原文，所以
     * 这里保留完整内容、不截断字段；体积在导出环节解决——导出日志会把文本压缩成 zip
     * （同一份日志 27 MB 压到约 8 MB，无损，见 LogcatExportHelper）。确实不需要转储时把
     * 这处置 false，日志里就不会再出现请求体。
     *
     * 只覆盖各家 LLM 的请求体与本地 llama.cpp 的完整 prompt；语音合成文本、最终模型输出等
     * 不受它控制。
     */
    @Volatile
    var logRequestBodies: Boolean = true

    @Volatile
    private var logFile: File? = null
    @Volatile
    private var packageLogFile: File? = null

    @Volatile
    private var boundContext: Context? = null
    private data class Record(
        val priority: Int = INFO, val tag: String = "", val message: String = "",
        val throwable: String? = null, val timestamp: Long = System.currentTimeMillis(),
        val reset: Boolean = false,
    )
    private val fileQueue = BatchLogQueue<Record>(sink = ::writeBatch)
    private var batchLogWriter: java.io.BufferedWriter? = null
    private var batchPackageWriter: java.io.BufferedWriter? = null
    private var batchFailed = false

    @JvmStatic
    fun bindContext(context: Context) {
        if (boundContext == null) {
            boundContext = context.applicationContext
        }
    }

    private fun resolveLogFile(): File? {
        val existing = logFile
        if (existing != null) return existing

        return try {
            val appContext: Context = try {
                OperitApplication.instance.applicationContext
            } catch (_: Throwable) {
                boundContext ?: return null
            }
            val dir = File(appContext.filesDir, LOG_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, LOG_FILE_NAME).also { file ->
                logFile = file
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun resolvePackageLogFile(): File? {
        val existing = packageLogFile
        if (existing != null) return existing

        return try {
            val appContext: Context = try {
                OperitApplication.instance.applicationContext
            } catch (_: Throwable) {
                boundContext ?: return null
            }
            val dir = File(OperitPaths.operitRootDir(), PACKAGE_LOG_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val startupMs = OperitApplication.appStartupTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            val fileName = startupFileDateFormat.format(Date(startupMs)) + ".log"
            File(dir, fileName).also { file ->
                packageLogFile = file
            }
        } catch (_: Throwable) {
            null
        }
    }

    // --- Public API mirroring com.ai.assistance.operit.util.AppLogger ---

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        writeToFile(VERBOSE, tag, msg, null)
        return Log.v(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(VERBOSE, tag, msg, tr)
        return Log.v(tag, msg, tr)
    }

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        writeToFile(DEBUG, tag, msg, null)
        return Log.d(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(DEBUG, tag, msg, tr)
        return Log.d(tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        writeToFile(INFO, tag, msg, null)
        return Log.i(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(INFO, tag, msg, tr)
        return Log.i(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        writeToFile(WARN, tag, msg, null)
        return Log.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(WARN, tag, msg, tr)
        return Log.w(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable): Int {
        writeToFile(WARN, tag, "", tr)
        return Log.w(tag, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        writeToFile(ERROR, tag, msg, null)
        return Log.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(ERROR, tag, msg, tr)
        return Log.e(tag, msg, tr)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String): Int {
        writeToFile(ASSERT, tag, msg, null)
        return Log.wtf(tag, msg)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String, tr: Throwable): Int {
        writeToFile(ASSERT, tag, msg, tr)
        return Log.wtf(tag, msg, tr)
    }

    @JvmStatic
    fun wtf(tag: String, tr: Throwable): Int {
        writeToFile(ASSERT, tag, "", tr)
        return Log.wtf(tag, tr)
    }

    @JvmStatic
    fun isLoggable(tag: String, level: Int): Boolean {
        return Log.isLoggable(tag, level)
    }

    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int {
        writeToFile(priority, tag, msg, null)
        return Log.println(priority, tag, msg)
    }

    @JvmStatic
    fun getStackTraceString(tr: Throwable): String {
        return ThrowableTextFormatter.format(tr, MAX_LOG_THROWABLE_CHARS)
    }

    /**
     * Returns the current log file (if available) so that callers can export it.
     */
    @JvmStatic
    fun getLogFile(): File? = resolveLogFile()

    @JvmStatic
    fun resetLogFile() {
        fileQueue.reset(Record(reset = true))
    }

    /** Call from a background export path, never block the UI waiting for storage. */
    fun flushFileLogs(timeoutMs: Long = 5000): Boolean = fileQueue.flush(timeoutMs)

    private fun resetLogFileSync() {
        try {
            val appContext: Context = OperitApplication.instance.applicationContext
            val dir = File(appContext.filesDir, LOG_DIR_NAME)
            val file = File(dir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
            logFile = null
            packageLogFile = null
        } catch (e: Throwable) {
            // Ignore errors during reset to avoid crashing on startup
        }
    }

    // --- Internal helpers ---

    private fun writeToFile(priority: Int, tag: String, msg: String, tr: Throwable?) {
        if (!enableFileLogging) return
        // Bound retained memory before enqueueing; never retain a Throwable's object graph.
        val record = Record(priority, tag.take(256), normalizeLogMessage(msg),
            tr?.let { ThrowableTextFormatter.format(it, MAX_LOG_THROWABLE_CHARS) })
        val accepted = fileQueue.submit(record, Looper.myLooper() != Looper.getMainLooper())
        if (!accepted) {
            Log.w("OperitAppLogger", "File log queue overloaded; UI record omitted (count will be saved)")
        }
    }

    private fun writeBatch(records: List<Record>, dropped: Long): Boolean {
        batchFailed = false
        fun closeWriters() {
            try { batchLogWriter?.close() } catch (_: IOException) { batchFailed = true }
            try { batchPackageWriter?.close() } catch (_: IOException) { batchFailed = true }
            batchLogWriter = null
            batchPackageWriter = null
        }
        try {
            records.forEach { record ->
                if (record.reset) {
                    closeWriters()
                    resetLogFileSync()
                } else writeToFileSync(record)
            }
            if (dropped > 0) writeToFileSync(Record(WARN, "OperitAppLogger",
                "File log queue overload: $dropped UI/interrupted records omitted; background producers use backpressure."))
        } finally { closeWriters() }
        return !batchFailed
    }

    private fun writeToFileSync(record: Record) {
        if (!enableFileLogging) return
        val file = resolveLogFile() ?: run { batchFailed = true; return }
        val (priority, tag, normalizedMessage, throwableText) = record

        val time = dateFormat.format(Date(record.timestamp))
        val levelChar = when (priority) {
            VERBOSE -> 'V'
            DEBUG -> 'D'
            INFO -> 'I'
            WARN -> 'W'
            ERROR -> 'E'
            ASSERT -> 'A'
            else -> '?'
        }

        val builder = StringBuilder()
        builder.append(time)
            .append(" ")
            .append(levelChar)
            .append("/")
            .append(tag)
            .append(": ")
            .append(normalizedMessage)

        if (throwableText != null) {
            builder.append("\n").append(throwableText)
        }

        builder.append('\n')

        try {
            val writer = batchLogWriter ?: FileWriter(file, true).buffered().also { batchLogWriter = it }
            writer.write(builder.toString())
        } catch (e: IOException) {
            batchFailed = true
        }

        writeToPackageLogIfNeeded(
            tag = tag,
            msg = normalizedMessage,
            throwableText = throwableText,
            time = time,
            levelChar = levelChar
        )
    }

    private fun writeToPackageLogIfNeeded(
        tag: String,
        msg: String,
        throwableText: String?,
        time: String,
        levelChar: Char
    ) {
        if (!shouldMirrorToPackageLog(tag, msg)) {
            return
        }
        val file = resolvePackageLogFile() ?: return
        val packageId = extractFirstMatch(msg, packageIdRegexes)
        val scriptId = extractFirstMatch(msg, scriptRegexes)
        val pluginId = extractFirstMatch(msg, pluginRegexes)

        val builder = StringBuilder()
        builder.append(time)
            .append(" ")
            .append(levelChar)
            .append("/")
            .append(TOOLPKG_LOG_TAG)
            .append(" ")

        if (!packageId.isNullOrBlank()) {
            builder.append("[PKG:")
                .append(packageId)
                .append("]")
        }
        if (!scriptId.isNullOrBlank()) {
            builder.append("[SCRIPT:")
                .append(scriptId)
                .append("]")
        }
        if (!pluginId.isNullOrBlank()) {
            builder.append("[PLUGIN:")
                .append(pluginId)
                .append("]")
        }
        if (builder.isNotEmpty() && builder[builder.length - 1] != ' ') {
            builder.append(" ")
        }
        builder
            .append(msg)

        if (throwableText != null) {
            builder.append("\n").append(throwableText)
        }
        builder.append('\n')

        try {
            val writer = batchPackageWriter ?: FileWriter(file, true).buffered().also { batchPackageWriter = it }
            writer.write(builder.toString())
        } catch (_: IOException) {
            batchFailed = true
        }
    }

    private fun shouldMirrorToPackageLog(tag: String, msg: String): Boolean {
        if (!tag.equals(TOOLPKG_LOG_TAG, ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun normalizeLogMessage(msg: String): String {
        return ThrowableTextFormatter.truncateText(msg, MAX_LOG_MESSAGE_CHARS)
    }

    private fun extractFirstMatch(text: String, patterns: List<Pattern>): String? {
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val value = matcher.group(1)?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    return value
                }
            }
        }
        return null
    }
}

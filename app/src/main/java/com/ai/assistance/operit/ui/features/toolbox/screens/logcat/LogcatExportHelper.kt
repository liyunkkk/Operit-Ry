package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.MemoryDiagnostics
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogcatExportResult(
    val message: String,
    val success: Boolean
)

object LogcatExportHelper {

    suspend fun exportLogs(context: Context): LogcatExportResult = withContext(Dispatchers.IO) {
        try {
            val fullyFlushed = AppLogger.flushFileLogs()
            val logFile = AppLogger.getLogFile()?.takeIf { it.isFile && it.length() > 0 }
            val logLineCount = logFile?.let(::countExportableLogLines) ?: 0L
            if (logLineCount == 0L && !MemoryDiagnostics.hasRecords(context)) {
                return@withContext LogcatExportResult(
                    message = context.getString(R.string.logcat_no_logs_to_save),
                    success = false
                )
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // 日志约九成是请求体转储，压成 zip 后只有原来的三成左右，所以导出统一压缩，
            // 既保留完整正文，又不用来回传几十 MB 的文本文件。
            val entryName = "operit_log_$timestamp.txt"
            val fileName = "$entryName.zip"
            val filePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveUsingMediaStore(context, fileName, entryName, logFile, logLineCount)
            } else {
                saveUsingFileSystem(context, fileName, entryName, logFile, logLineCount)
            }

            LogcatExportResult(
                message = if (fullyFlushed) context.getString(R.string.logcat_saved_to, filePath)
                    else context.getString(R.string.logcat_flush_incomplete, filePath),
                success = true
            )
        } catch (e: Exception) {
            LogcatExportResult(
                message = context.getString(
                    R.string.logcat_save_failed,
                    e.message ?: context.getString(R.string.logcat_unknown_error)
                ),
                success = false
            )
        }
    }

    private fun countExportableLogLines(logFile: File): Long {
        var count = 0L
        logFile.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    count++
                }
            }
        }
        return count
    }

    private fun writeLogContent(
        context: Context,
        writer: Writer,
        logFile: File?,
        logLineCount: Long
    ) {
        val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        writer.appendLine(context.getString(R.string.logcat_header))
        writer.appendLine(context.getString(R.string.logcat_date, exportTime))
        writer.appendLine(context.getString(R.string.logcat_total_count, logLineCount))
        writer.appendLine("===================================")
        writer.appendLine()

        logFile?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    writer.appendLine(line)
                }
            }
        }
    }

    /**
     * 把日志写进 zip 中的文本条目。
     *
     * 条目内容与原来的纯文本日志完全一致，只是整体做了一次 deflate 压缩：请求体转储这类
     * 高度重复的 JSON 文本压缩比约 3:1（实测 27 MB 的日志压到约 8 MB，约三成），解压后按原样
     * 阅读和检索。
     */
    private fun writeZipLog(
        context: Context,
        outputStream: OutputStream,
        entryName: String,
        logFile: File?,
        logLineCount: Long
    ) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            writeLogContent(context, writer, logFile, logLineCount)
            writer.flush()
            zip.closeEntry()
            MemoryDiagnostics.exportTo(context, zip)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveUsingMediaStore(
        context: Context,
        fileName: String,
        entryName: String,
        logFile: File?,
        logLineCount: Long
    ): String {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/operit")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw Exception(context.getString(R.string.logcat_cannot_create_file))

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeZipLog(context, outputStream, entryName, logFile, logLineCount)
            } ?: throw Exception(context.getString(R.string.logcat_cannot_open_output_stream))

            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return "${downloadsDir.absolutePath}/operit/$fileName"
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_mediestore_save_failed, e.message ?: ""))
        }
    }

    private fun saveUsingFileSystem(
        context: Context,
        fileName: String,
        entryName: String,
        logFile: File?,
        logLineCount: Long
    ): String {
        try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null || !downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_download_dir))
            }
            val operitDir = File(downloadsDir, "operit")
            if (!operitDir.exists() && !operitDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_operit_dir))
            }
            val file = File(operitDir, fileName)
            FileOutputStream(file).use { outputStream ->
                writeZipLog(context, outputStream, entryName, logFile, logLineCount)
            }
            if (!file.exists() || file.length() == 0L) {
                throw Exception(context.getString(R.string.logcat_file_create_failed))
            }
            return file.absolutePath
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_filesystem_save_failed, e.message ?: ""))
        }
    }
}

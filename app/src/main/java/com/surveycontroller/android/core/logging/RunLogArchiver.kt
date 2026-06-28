package com.surveycontroller.android.core.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志归档器。对齐桌面端“自动保存日志 + 保留最近 N 份”能力：
 * 任务结束后把本次运行日志写入应用私有 logs 目录，并仅保留最近 N 份历史文件。
 */
object RunLogArchiver {

    private const val DIR_NAME = "run_logs"
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** 日志目录（应用私有 files/run_logs）。 */
    fun logsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 保存一次运行日志。
     * @param header 概要信息行（标题、成功/失败份数等）。
     * @param lines 日志正文行。
     * @param retentionCount 仅保留最近多少份历史文件。
     * @return 写入的文件，失败返回 null。
     */
    fun save(
        context: Context,
        header: List<String>,
        lines: List<String>,
        retentionCount: Int,
    ): File? = runCatching {
        val dir = logsDir(context)
        val file = File(dir, "run_${stamp.format(Date())}.log")
        file.bufferedWriter().use { w ->
            header.forEach { w.appendLine(it) }
            if (header.isNotEmpty()) w.appendLine("----------------------------------------")
            lines.forEach { w.appendLine(it) }
        }
        prune(dir, retentionCount)
        file
    }.getOrNull()

    /** 仅保留最近 retentionCount 份日志文件，多余的按时间从旧到新删除。 */
    private fun prune(dir: File, retentionCount: Int) {
        val keep = retentionCount.coerceAtLeast(1)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("run_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }

    /** 当前已归档日志文件列表（最新在前）。 */
    fun listFiles(context: Context): List<File> =
        logsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 已归档日志文件数量。 */
    fun count(context: Context): Int = listFiles(context).size

    /** 清空全部已归档日志。返回删除数量。 */
    fun clear(context: Context): Int {
        val files = listFiles(context)
        var n = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) n++ }
        return n
    }
}

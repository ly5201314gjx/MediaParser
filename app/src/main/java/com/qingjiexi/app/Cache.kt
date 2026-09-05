package com.qingjiexi.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

/**
 * 视频预览本地缓存统一入口：
 * - 默认开启，SharedPreferences 持久化开关；
 * - URL 哈希命名落盘到 cacheDir/preview_cache，元数据入库（DB cache 表）；
 * - 提供缓存大小统计与按时间范围（近1天/近7天/全部）清除。
 */
object VideoCache {

    private const val PREFS = "app_prefs"
    private const val KEY_ENABLED = "video_cache_enabled"
    private const val DIR_NAME = "preview_cache"

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 自动缓存开关（默认开启） */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 缓存目录（应用私有 cacheDir，系统可自动清理，无需存储权限） */
    fun dir(c: Context): File =
        File(c.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 由 URL 唯一确定缓存文件（md5(URL) + 扩展名），天然去重 */
    fun fileFor(c: Context, url: String): File {
        val name = md5(url)
        return File(dir(c), name + extOf(url))
    }

    private fun extOf(url: String): String {
        runCatching {
            val path = url.substringBefore('?')
            val dot = path.lastIndexOf('.')
            if (dot >= 0) {
                val e = path.substring(dot)
                if (e.length in 2..6 && e.drop(1).all { it.isLetter() }) return e
            }
        }
        return ".mp4"
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** 命中本地缓存 → 返回绝对路径；未命中 → null（顺带清理脏记录） */
    fun localFile(c: Context, url: String): String? {
        val f = fileFor(c, url)
        if (f.exists() && f.length() > 0) return f.absolutePath
        DB.deleteCacheByPath(f.absolutePath)
        return null
    }

    /** 缓存总占用字节数（DB 汇总） */
    fun totalBytes(): Long = DB.cacheSize()

    fun entryCount(): Int = DB.cacheCount()

    /**
     * 清除缓存：thresholdSeconds 之前缓存的文件与记录全部删除；
     * thresholdSeconds = 0 表示全量。返回已清除的文件数。
     */
    fun clearOlderThan(c: Context, thresholdSeconds: Long): Int {
        val before = System.currentTimeMillis() / 1000 - thresholdSeconds

        // 1) DB 记录优先：删除文件 + 记录
        var n = 0
        for (e in DB.cachesOlderThan(before)) {
            runCatching { File(e.path).delete() }
            DB.deleteCacheById(e.id)
            n++
        }

        // 2) 兜底扫描目录：清掉崩溃残留 / 未入库的孤儿文件（按文件修改时间）
        runCatching {
            dir(c).listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() / 1000 < before) {
                    runCatching { f.delete() }
                    n++
                }
            }
        }
        return n
    }
}
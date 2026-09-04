package com.qingjiexi.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentValues
import android.content.ContentResolver
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 前台保活服务：负责后台解析 + 断点下载（可暂停/继续/取消），
 * 通知栏实时显示解析与下载进度。临时退出 App 也不会中断。
 */
class MediaService : Service() {

    companion object {
        const val ACTION_PARSE = "com.qingjiexi.app.PARSE"
        const val ACTION_PARSE_CANCEL = "com.qingjiexi.app.PARSE_CANCEL"
        const val ACTION_DOWNLOAD = "com.qingjiexi.app.DOWNLOAD"
        const val ACTION_PAUSE = "com.qingjiexi.app.PAUSE"
        const val ACTION_RESUME = "com.qingjiexi.app.RESUME"
        const val ACTION_CANCEL = "com.qingjiexi.app.CANCEL"

        const val BROADCAST = "com.qingjiexi.app.BROADCAST"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DL_ID = "dl_id"
        const val EXTRA_DL_STATUS = "dl_status"
        const val EXTRA_DL_PROGRESS = "dl_progress"
        const val EXTRA_DL_TOTAL = "dl_total"
        const val EXTRA_DL_FILE = "dl_file"
        const val EXTRA_PARSE_OK = "parse_ok"
        const val EXTRA_PARSE_ERROR = "parse_error"
        const val EXTRA_HISTORY_ID = "history_id"

        private const val CH_SERVICE = "svc"
        private const val CH_PARSE = "parse"
        private const val CH_DL = "dl"
        private const val NOTIFY_SERVICE = 10
        private const val NOTIFY_PARSE = 11
        private const val NOTIFY_DL_BASE = 20

        const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        fun parse(service: Context, text: String) {
            startCompat(service, Intent(service, MediaService::class.java)
                .setAction(ACTION_PARSE).putExtra(EXTRA_TEXT, text))
        }
        fun download(service: Context, url: String, fileName: String, kind: String, historyId: Long) {
            val i = Intent(service, MediaService::class.java).setAction(ACTION_DOWNLOAD)
            i.putExtra("url", url).putExtra("file", fileName).putExtra("kind", kind)
            i.putExtra("hid", historyId)
            startCompat(service, i)
        }
        fun pause(service: Context, id: Long) = cmd(service, ACTION_PAUSE, id)
        fun resume(service: Context, id: Long) = cmd(service, ACTION_RESUME, id)
        fun cancel(service: Context, id: Long) = cmd(service, ACTION_CANCEL, id)
        private fun cmd(service: Context, action: String, id: Long) {
            startCompat(service, Intent(service, MediaService::class.java).setAction(action).putExtra(EXTRA_DL_ID, id))
        }

        /** Android 8+ 一律用 startForegroundService，避免应用退到后台时广播/任务丢失 */
        private fun startCompat(ctx: Context, i: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {}
        }
    }

    private lateinit var nm: NotificationManager
    private val tasks = HashMap<Long, DownloadTask>()
    private val ui = Handler(Looper.getMainLooper())

    // ---------- 分片并行下载参数 ----------
    private val MAX_CHUNK_THREADS = 12
    private val MIN_CHUNK_SIZE = 4L * 1024 * 1024
    private val MIN_CHUNKED_DOWNLOAD = 6L * 1024 * 1024

    // ---------- lifecycle ----------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
        sweepStaleDownloads()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_SERVICE, buildServiceNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_SERVICE, buildServiceNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PARSE -> doParse(intent.getStringExtra(EXTRA_TEXT) ?: "")
            ACTION_PARSE_CANCEL -> NativeLib.stopParse()
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra("url") ?: ""; if (url.isEmpty()) return START_STICKY
                val file = intent.getStringExtra("file") ?: "media.mp4"
                val kind = intent.getStringExtra("kind") ?: "video"
                startDownload(url, file, kind, intent.getLongExtra("hid", 0), 0)
            }
            ACTION_PAUSE -> tasks[intent.getLongExtra(EXTRA_DL_ID, -1)]?.paused = true
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_DL_ID, -1)
                val dl = DB.downloadById(id)
                if (dl != null && dl.status != DB.DL_DONE) {
                    resumeDownload(id)
                }
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_DL_ID, -1)
                tasks[id]?.cancelled = true
                cancelNotify(id)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tasks.clear()
        super.onDestroy()
    }

    /**
     * 服务进程（重新）创建时，把数据库中残留的"下载中/等待中"任务标记为失败。
     * 这些状态只可能属于活着的任务线程；进程一死就永远是虚假的"下载中"，
     * 此前正是它导致界面一直显示"下载中"且无法进入详情页。
     */
    private fun sweepStaleDownloads() {
        try {
            for (d in DB.allDownloads()) {
                if (d.status == DB.DL_DOWNLOADING || d.status == DB.DL_WAITING) {
                    DB.updateDownloadStatus(d.id, DB.DL_FAILED)
                    broadcast { it.putExtra(EXTRA_DL_ID, d.id).putExtra(EXTRA_DL_STATUS, DB.DL_FAILED) }
                }
            }
        } catch (_: Exception) {}
    }

    // ---------- 通知基础 ----------
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "后台服务", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_PARSE, "解析进度", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DL, "下载进度", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildServiceNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_SERVICE)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        n.setContentTitle("轻解析 · 后台服务运行中")
                .setContentText("解析与下载任务在后台持续进行")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(open)
        return n.build()
    }

    private fun parseNotify(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_PARSE)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true).setContentIntent(open)
        try { nm.notify(NOTIFY_PARSE, n.build()) } catch (_: Exception) {}
    }

    private fun dlNotify(id: Long, fileName: String, progress: Int = -1, done: Boolean = false) {
        if (id <= 0) return
        // 点击通知 → 打开对应媒体的详情页
        val hid = DB.downloadById(id)?.historyId ?: 0L
        val view = Intent(this, MainActivity::class.java)
            .setAction("VIEW_MEDIA").putExtra("media_id", hid)
        val pending = PendingIntent.getActivity(this, (NOTIFY_DL_BASE + id).toInt(), view,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_DL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setContentTitle(if (done) "下载完成 · $fileName" else "下载中 · $fileName")
                .setContentText(if (done) "已保存至文件管理 → 点击查看" else if (progress >= 0) "$progress%" else "准备中…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setContentIntent(pending)
        if (progress >= 0) n.setProgress(100, progress.coerceIn(0, 100), false)
        try { nm.notify(NOTIFY_DL_BASE + id.toInt(), n.build()) } catch (_: Exception) {}
    }

    private fun cancelNotify(id: Long) {
        try { nm.cancel(NOTIFY_DL_BASE + id.toInt()) } catch (_: Exception) {}
    }

    // ---------- 解析 ----------
    private fun doParse(text: String) {
        if (text.isBlank()) return
        parseNotify("正在解析", "正在识别链接并获取媒体信息…")
        Thread {
            val json = try { NativeLib.parseText(text) } catch (e: Throwable) {
                "{\"ok\":false,\"error\":\"native error: ${e.message}\"}"
            }
            var historyId = -1L
            var ok = false
            var errMsg = ""
            try {
                val j = JSONObject(json)
                ok = j.optBoolean("ok", false)
                if (ok) {
                    val data = j.optJSONObject("data")
                    if (data != null) {
                        val h = MediaBean().apply {
                            platform = data.optString("platform", "")
                            platformName = data.optString("platform_name", "")
                            title = data.optString("title", "")
                            author = data.optString("author", "")
                            cover = data.optString("cover", "")
                            sourceUrl = data.optString("source_url", "")
                            parsedAt = data.optLong("timestamp", 0)
                            val videos = data.optJSONArray("videos") ?: JSONArray()
                            if (videos.length() > 0) {
                                val first = videos.optJSONObject(0)
                                videoUrl = first?.optString("url", "") ?: ""
                                quality = first?.optString("quality", "") ?: ""
                            }
                            imageUrls = data.optJSONArray("images") ?: JSONArray()
                            audioUrls = data.optJSONArray("audios") ?: JSONArray()
                        }
                        historyId = DB.addHistory(h)
                    }
                } else {
                    errMsg = j.optString("error", "未知错误")
                }
            } catch (e: Exception) {
                errMsg = e.message ?: "解析异常"
            }
            val finalOk = ok; val finalId = historyId; val finalErr = errMsg
            val cancelled = errMsg == "解析已取消"
            ui.post {
                try {
                    updateServiceNotify()
                    if (!cancelled) {
                        if (finalOk && finalId >= 0) {
                            val h = DB.historyById(finalId)
                            parseNotify("解析成功 · " + (h?.platformName ?: ""),
                                (h?.title ?: "").ifEmpty { "已保存到历史记录" })
                        } else {
                            parseNotify("解析失败", finalErr)
                        }
                    } else {
                        updateServiceNotify()
                    }
                } finally {
                    // 无论成功失败/是否异常，都必须把结果广播给界面，避免首页一直停留在"正在解析"
                    broadcast { it.putExtra(EXTRA_PARSE_OK, finalOk)
                        .putExtra(EXTRA_HISTORY_ID, finalId)
                        .putExtra(EXTRA_PARSE_ERROR, finalErr) }
                }
            }
        }.start()
    }

    // ---------- 断点下载 ----------
    private inner class DownloadTask(val id: Long) {
        @Volatile var paused = false
        @Volatile var cancelled = false
        var thread: Thread? = null
    }

    private fun startDownload(url: String, fileName: String, kind: String, historyId: Long, taskId: Long) {
        val dl = if (taskId > 0) DB.downloadById(taskId) else
            DB.addDownload(DownloadBean(historyId = historyId, url = url, fileName = fileName, kind = kind, status = DB.DL_WAITING)).let { DB.downloadById(it) }
        if (dl == null) return
        if (tasks[dl.id]?.thread?.isAlive == true) return
        val task = DownloadTask(dl.id)
        tasks[dl.id] = task
        DB.updateDownloadStatus(dl.id, DB.DL_DOWNLOADING)
        dlNotify(dl.id, dl.fileName)
        broadcast { it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_DOWNLOADING) }

        task.thread = Thread {
            var ok = false
            try {
                ok = downloadLoop(dl, task)
            } catch (e: Exception) {
                if (!task.cancelled) DB.updateDownloadStatus(dl.id, DB.DL_FAILED)
            }
            ui.post {
                tasks.remove(dl.id)
                cancelNotify(dl.id)
                updateServiceNotify()
                if (task.cancelled) {
                    DB.updateDownloadStatus(dl.id, DB.DL_FAILED)
                } else if (ok) {
                    DB.updateDownloadStatus(dl.id, DB.DL_DONE)
                    dlNotify(dl.id, dl.fileName, done = true)
                    broadcast { it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_DONE) }
                } else if (task.paused) {
                    DB.updateDownloadStatus(dl.id, DB.DL_PAUSED)
                    broadcast { it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_PAUSED) }
                } else {
                    DB.updateDownloadStatus(dl.id, DB.DL_FAILED)
                    broadcast { it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_FAILED) }
                }
            }
        }
        task.thread!!.start()
    }

    private fun resumeDownload(id: Long) {
        val dl = DB.downloadById(id) ?: return
        startDownload(dl.url, dl.fileName, dl.kind, dl.historyId, dl.id)
    }

    /** 断点续传主循环：优先多线程分片加速，否则回退单线程流式；返回 true = 完整下载完成 */
    private fun downloadLoop(dl: DownloadBean, task: DownloadTask): Boolean {
        // 先下载到应用私有缓存（无需任何权限），完成后发布到公共目录（相册/文件管理）
        val tmpDir = File(cacheDir, "dl_tmp").apply { if (!exists()) mkdirs() }
        val file = File(tmpDir, "dl_${dl.id}_" + saneName(dl.fileName))

        // 探测：文件总大小 + 是否支持 Range
        val probe = probeFile(dl)

        // 上次已完整下载（中断在发布阶段）→ 直接收尾
        if (probe.total > 0 && file.length() >= probe.total) {
            updateDone(dl, probe.total, probe.total)
            runCatching { publishToMedia(dl, file) }
            runCatching { file.delete() }
            return true
        }

        // 大文件 + 服务器支持 Range → 分片多线程并行下载（提速关键）；
        // 有历史分片元数据 / 全新文件才走分片，旧版单线程断点文件继续走流式续传
        val meta = File(tmpDir, file.name + ".meta")
        if (probe.total >= MIN_CHUNKED_DOWNLOAD && probe.range &&
            (meta.exists() || !file.exists() || file.length() == 0L)) {
            val ok = runCatching { chunkedDownload(dl, task, file, meta, probe.total) }.getOrDefault(false)
            if (ok) return true
            if (task.cancelled || task.paused) return false
            // 分片失败：清理残片后退回单线程整流重下
            runCatching { meta.delete() }
            runCatching { file.delete() }
        }
        return streamDownload(dl, task, file)
    }

    /** 单线程流式断点下载（兼容不支持 Range / 小文件 / 分片失败兜底） */
    private fun streamDownload(dl: DownloadBean, task: DownloadTask, file: File): Boolean {
        var offset = if (file.exists()) file.length() else 0L
        var lastBroadcast = 0L
        var finished = false
        var total = 0L
        // 连续失败上限：链接失效 / 网络中断不再无限重试，快速落为"失败"状态，
        // 让界面能立刻点击进入详情做断点续传
        var failCount = 0
        val MAX_FAIL = 5

        while (!task.cancelled && !finished) {
            if (task.paused) { DB.updateDownloadStatus(dl.id, DB.DL_PAUSED); return false }
            var conn: HttpURLConnection? = null
            val bytesBefore = offset
            try {
                conn = URL(dl.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", UA_PC)
                applyReferer(conn, dl.url)
                if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
                conn.instanceFollowRedirects = true
                conn.connect()
                val code = conn.responseCode
                if (code == 416) { updateDone(dl, 0, 0); finished = true; break }
                if (code !in 200..299) {
                    if (++failCount >= MAX_FAIL) return false
                    Thread.sleep(1500); continue
                }
                total = if (code == 206) offset + (conn.contentLength) else conn.contentLength.toLong()
                val input = conn.inputStream
                if (input == null) {
                    if (++failCount >= MAX_FAIL) return false
                    Thread.sleep(1500); continue
                }
                val raf = RandomAccessFile(file, "rw")
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                val now = System.currentTimeMillis()
                while (!task.cancelled && !task.paused) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    offset += n
                    if (xmod(offset, 786432L)) {
                        DB.updateDownload(DownloadBean(id = dl.id, historyId = dl.historyId, url = dl.url,
                            fileName = dl.fileName, kind = dl.kind, totalSize = total, downloadedSize = offset, status = DB.DL_DOWNLOADING))
                        val progress = if (total > 0) ((offset * 100) / total).toInt() else -1
                        if (now - lastBroadcast > 400) {
                            lastBroadcast = now
                            ui.post {
                                dlNotify(dl.id, dl.fileName, progress)
                                broadcast {
                                    it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_DOWNLOADING)
                                        .putExtra(EXTRA_DL_PROGRESS, progress).putExtra(EXTRA_DL_TOTAL, total)
                                        .putExtra(EXTRA_DL_FILE, file.absolutePath)
                                }
                            }
                        }
                    }
                }
                input.close(); raf.close()
                if (task.cancelled) return false
                if (task.paused) {
                    DB.updateDownload(DownloadBean(id = dl.id, historyId = dl.historyId, url = dl.url,
                        fileName = dl.fileName, kind = dl.kind, totalSize = total, downloadedSize = offset, status = DB.DL_PAUSED))
                    return false
                }
                // 本次连接没有读到任何新字节（服务端空响应/数据异常），按一次失败处理，避免死循环
                if (offset == bytesBefore) {
                    if (++failCount >= MAX_FAIL) return false
                    Thread.sleep(1500); continue
                }
                failCount = 0
                // 完整下载判定（total 已知 / 未知 / 已下载完毕）
                val done = (total > 0 && offset >= total) || (total == 0L && offset > 0) || total < 0 || total == 0L
                if (done && offset > 0) {
                    updateDone(dl, total, offset)
                    finished = true
                    break
                }
                Thread.sleep(800)
            } catch (e: Exception) {
                try { conn?.disconnect() } catch (_: Exception) {}
                if (task.cancelled || task.paused) return false
                if (++failCount >= MAX_FAIL) return false
                Thread.sleep(1500)
            }
        }

        // 完整下载完成：把临时文件发布到相册 / 文件管理
        if (finished && file.exists() && file.length() > 0) {
            runCatching { publishToMedia(dl, file) }
        }
        // 只有真正完成时才删除临时文件；失败/暂停/取消都保留断点，支持下一次续传
        if (finished) runCatching { file.delete() }
        return finished
    }

    // ---------- 多线程分片下载 ----------

    private class Probe(val total: Long, val range: Boolean)

    private fun urlConn(dl: DownloadBean): HttpURLConnection {
        val c = URL(dl.url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.useCaches = false
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA_PC)
        applyReferer(c, dl.url)
        return c
    }

    /** 探测文件总大小与是否支持 Range：GET + 小范围请求（100 字节），开销可忽略 */
    private fun probeFile(dl: DownloadBean): Probe {
        var conn: HttpURLConnection? = null
        try {
            conn = urlConn(dl)
            conn.setRequestProperty("Range", "bytes=0-101")
            conn.connect()
            val code = conn.responseCode
            if (code == 206) {
                val total = getRangeTotal(conn.getHeaderField("Content-Range"))
                if (total != null && total > 0) return Probe(total, true)
                return Probe(-1, false)
            }
            if (code == 200) return Probe(conn.contentLength.toLong(), false)
        } catch (_: Exception) {}
        finally { runCatching { conn?.disconnect() } }
        return Probe(-1, false)
    }

    /** 解析 Content-Range: "bytes 0-1023/102400" 中的总大小 */
    private fun getRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val i = header.lastIndexOf('/')
        if (i < 0) return null
        return header.substring(i + 1).trim().toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * 多线程分片下载：每个分片线程独立连接、直写文件对应扇区（区域互不重叠），
     * 全部完成即整文件就绪；分片进度持久化到 .meta 文件支持断点续传。
     */
    private fun chunkedDownload(dl: DownloadBean, task: DownloadTask,
                                file: File, meta: File, total: Long): Boolean {
        val threads = (total / MIN_CHUNK_SIZE).toInt().coerceIn(2, MAX_CHUNK_THREADS)
        val block = total / threads
        val starts = LongArray(threads); val ends = LongArray(threads)
        var s = 0L
        for (i in 0 until threads) { starts[i] = s; s += if (i == threads - 1) total - s else block; ends[i] = s }

        // 断点续传：读取上次各分片已下载字节数
        val chunkDone = LongArray(threads)
        loadChunkMeta(meta, chunkDone)
        for (i in 0 until threads) chunkDone[i] = chunkDone[i].coerceIn(0, ends[i] - starts[i])

        val downloaded = AtomicLong(chunkDone.sum())
        val failed = AtomicInteger(0)
        val lastReport = AtomicLong(0)
        val lastMetaSave = AtomicLong(0)
        val latch = CountDownLatch(threads)

        for (i in 0 until threads) {
            val start = starts[i]; val end = ends[i]
            Thread {
                try {
                    var from = start + chunkDone[i]
                    if (from >= end) return@Thread
                    var conn: HttpURLConnection? = null
                    val raf = RandomAccessFile(file, "rw")
                    try {
                        raf.seek(from)
                        val buf = ByteArray(128 * 1024)
                        var attempts = 0
                        while (from < end && !task.cancelled && !task.paused) {
                            var connBytes = 0L
                            var ok = false
                            try {
                                conn = urlConn(dl)
                                conn.setRequestProperty("Range", "bytes=$from-${end - 1}")
                                conn.connect()
                                val code = conn.responseCode
                                if (code != 206 && code != 200) throw java.io.IOException("HTTP $code")
                                conn.inputStream.use { ins ->
                                    while (from < end && !task.cancelled && !task.paused) {
                                        val want = minOf(end - from, Int.MAX_VALUE.toLong()).toInt().coerceAtMost(buf.size)
                                        val n = ins.read(buf, 0, want)
                                        if (n < 0) break
                                        raf.write(buf, 0, n)
                                        from += n; connBytes += n
                                        downloaded.addAndGet(n.toLong())
                                        // 每写满 ~512KB 记录一次分片进度（周期落盘，支持断点）
                                        if (connBytes % (512 * 1024L) < 128 * 1024) {
                                            chunkDone[i] = from - start
                                            val now = System.currentTimeMillis()
                                            if (now - lastMetaSave.get() > 800 &&
                                                lastMetaSave.compareAndSet(lastMetaSave.get(), now)) {
                                                saveChunkMeta(meta, chunkDone)
                                            }
                                        }
                                    }
                                }
                                // 连接没有读到任何新字节（空响应/异常断开）→ 重试
                                if (from < end && connBytes == 0L) throw java.io.IOException("short stream")
                                ok = true
                            } catch (e: Exception) {
                                runCatching { conn?.disconnect() }
                                if (task.cancelled || task.paused) break
                                if (++attempts >= 5) { failed.incrementAndGet(); break }
                                Thread.sleep(1500)
                            }
                            if (!ok) break
                        }
                        chunkDone[i] = from - start
                    } finally {
                        runCatching { raf.close() }
                        runCatching { conn?.disconnect() }
                        runCatching { saveChunkMeta(meta, chunkDone) }
                    }
                } finally {
                    reportProgress(dl, task, file, downloaded, total, lastReport)
                    latch.countDown()
                }
            }.apply { name = "dl-chunk$i"; start() }
        }
        latch.await()

        if (task.cancelled) return false
        if (task.paused) {
            DB.updateDownload(DownloadBean(id = dl.id, historyId = dl.historyId, url = dl.url,
                fileName = dl.fileName, kind = dl.kind, totalSize = total,
                downloadedSize = downloaded.get(), status = DB.DL_PAUSED))
            return false
        }
        reportProgress(dl, task, file, downloaded, total, lastReport)
        if (failed.get() > 0) return false
        if (downloaded.get() < total || file.length() < total) return false

        updateDone(dl, total, total)
        runCatching { meta.delete() }
        runCatching { publishToMedia(dl, file) }
        runCatching { file.delete() }
        return true
    }

    /** 节流的进度上报：DB + 通知栏 + UI 广播（每 400ms 至多一次，避免高频写库） */
    private fun reportProgress(dl: DownloadBean, task: DownloadTask, file: File,
                               downloaded: AtomicLong, total: Long, lastReport: AtomicLong) {
        val now = System.currentTimeMillis()
        if (lastReport.get() == 0L) lastReport.set(now)
        else {
            if (now - lastReport.get() < 400) return
            if (!lastReport.compareAndSet(lastReport.get(), now)) return
        }
        val d = downloaded.get()
        val progress = if (total > 0) ((d * 100) / total).toInt() else -1
        DB.updateDownload(DownloadBean(id = dl.id, historyId = dl.historyId, url = dl.url,
            fileName = dl.fileName, kind = dl.kind, totalSize = total, downloadedSize = d, status = DB.DL_DOWNLOADING))
        ui.post {
            dlNotify(dl.id, dl.fileName, progress)
            broadcast {
                it.putExtra(EXTRA_DL_ID, dl.id).putExtra(EXTRA_DL_STATUS, DB.DL_DOWNLOADING)
                    .putExtra(EXTRA_DL_PROGRESS, progress).putExtra(EXTRA_DL_TOTAL, total)
                    .putExtra(EXTRA_DL_FILE, file.absolutePath)
            }
        }
    }

    private fun loadChunkMeta(meta: File, out: LongArray) {
        if (!meta.exists()) return
        try {
            meta.forEachLine { line ->
                val sp = line.split(' ')
                if (sp.size == 2) {
                    val idx = sp[0].toIntOrNull() ?: return@forEachLine
                    val v = sp[1].toLongOrNull() ?: return@forEachLine
                    if (idx in out.indices) out[idx] = v
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveChunkMeta(meta: File, chunkDone: LongArray) {
        try {
            val sb = StringBuilder(64)
            for (i in chunkDone.indices) sb.append(i).append(' ').append(chunkDone[i]).append('\n')
            val tmp = File(meta.parentFile, meta.name + ".tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(meta) && meta.exists()) meta.delete()
        } catch (_: Exception) {}
    }

    private fun updateDone(dl: DownloadBean, total: Long, sized: Long) {
        DB.updateDownload(DownloadBean(id = dl.id, historyId = dl.historyId, url = dl.url,
            fileName = dl.fileName, kind = dl.kind, totalSize = total, downloadedSize = sized, status = DB.DL_DONE))
    }

    // ---------- 发布到公共目录（相册 / 文件管理器可见） ----------

    private fun publishToMedia(dl: DownloadBean, src: File): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            publishViaMediaStore(dl, src)
        } else {
            publishLegacy(dl, src)
        }
    }

    private fun mimeOf(dl: DownloadBean): String {
        val f = dl.fileName.lowercase()
        return when {
            dl.kind == "audio" || f.endsWith(".mp3") -> "audio/mpeg"
            f.endsWith(".jpg") || f.endsWith(".jpeg") -> "image/jpeg"
            f.endsWith(".png") -> "image/png"
            f.endsWith(".webp") -> "image/webp"
            else -> "video/mp4"
        }
    }

    /** Android 10+：通过 MediaStore 写入公共目录，无需任何权限（调用方已按 SDK 分级） */
    @SuppressLint("NewApi")
    private fun publishViaMediaStore(dl: DownloadBean, src: File): Boolean {
        return try {
            val baseDir = when (dl.kind) {
                "audio" -> Environment.DIRECTORY_MUSIC + "/轻解析"
                else -> {
                    val f = dl.fileName.lowercase()
                    if (f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".png") || f.endsWith(".webp"))
                        Environment.DIRECTORY_PICTURES + "/轻解析"
                    else
                        Environment.DIRECTORY_DOWNLOADS + "/轻解析"
                }
            }
            val collection = when (dl.kind) {
                "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> {
                    val f = dl.fileName.lowercase()
                    if (f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".png") || f.endsWith(".webp"))
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, dl.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(dl))
                put(MediaStore.MediaColumns.RELATIVE_PATH, baseDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return false
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Android 9-：直接写公共 Downloads 目录 + 扫描（依赖 WRITE_EXTERNAL_STORAGE 权限） */
    private fun publishLegacy(dl: DownloadBean, src: File): Boolean {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "轻解析")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, saneName(dl.fileName))
            src.inputStream().use { input ->
                FileOutputStream(dst).use { input.copyTo(it) }
            }
            MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf(mimeOf(dl)), null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyReferer(conn: HttpURLConnection, url: String) {
        val u = url.lowercase()
        conn.setRequestProperty("Accept", "*/*")
        when {
            u.contains("bilibili") || u.contains("mcdn") || u.contains("mountaintoys") || u.contains("upos") ->
                conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            u.contains("douyinvod") || u.contains("douyinstatic") || u.contains("iesdouyin") ->
                conn.setRequestProperty("Referer", "https://www.douyin.com/")
            u.contains("byte") || u.contains("ibyte") || u.contains("ibytedtos") ->
                conn.setRequestProperty("Referer", "https://www.tiktok.com/")
        }
    }

    private fun saneName(name: String): String {
        val n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return n.ifBlank { "media.mp4" }
    }

    private fun xmod(v: Long, m: Long): Boolean = v % m < 64 * 1024

    private fun updateServiceNotify() {
        val dlCount = DB.allDownloads().count { it.status == DB.DL_DOWNLOADING || it.status == DB.DL_WAITING }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_SERVICE)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setContentTitle("轻解析 · 后台服务运行中")
                .setContentText(if (dlCount > 0) "正在下载 $dlCount 个任务" else "下载与解析任务实时同步")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
        try { nm.notify(NOTIFY_SERVICE, n.build()) } catch (_: Exception) {}
    }

    private fun broadcast(block: (Intent) -> Unit) {
        val i = Intent(BROADCAST)
        block(i)
        i.setPackage(packageName)
        sendBroadcast(i)
    }
}
package com.qingjiexi.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/**
 * 本地数据库：历史记录 / 收藏 / 分类 / 下载任务 / 回收站
 * 纯平台 SQLite，无额外依赖。
 */
object DB {
    // ---- 下载状态 ----
    const val DL_NONE = 0
    const val DL_WAITING = 1
    const val DL_DOWNLOADING = 2
    const val DL_PAUSED = 3
    const val DL_DONE = 4
    const val DL_FAILED = 5

    private lateinit var helper: DBHelper
    fun init(c: Context) { helper = DBHelper(c.applicationContext) }
    private val db get() = helper.writableDatabase

    // ================= history =================
    fun addHistory(h: MediaBean): Long {
        // 同一 source_url 去重：已存在则更新时间并返回原 id
        val old = findHistoryBySource(h.sourceUrl)
        if (old != null) {
            updateHistory(old.id, h, parsedAt = System.currentTimeMillis() / 1000)
            return old.id
        }
        val cv = ContentValues().apply {
            put("title", h.title)
            put("platform", h.platform)
            put("platform_name", h.platformName)
            put("cover", h.cover)
            put("author", h.author)
            put("source_url", h.sourceUrl)
            put("video_url", h.videoUrl)
            put("quality", h.quality)
            put("image_urls", h.imageUrls.toString())
            put("audio_urls", h.audioUrls.toString())
            put("parsed_at", System.currentTimeMillis() / 1000)
        }
        return db.insert("history", null, cv)
    }

    fun findHistoryBySource(source: String): MediaBean? {
        db.query("history", null, "source_url=? AND is_recycled=0", arrayOf(source), null, null, null).use { c ->
            if (c.moveToFirst()) return fromHistoryCursor(c)
        }
        return null
    }

    fun updateHistory(id: Long, h: MediaBean, plus: Int = 0, parsedAt: Long, fromDb: Boolean = false) {
        val cv = ContentValues()
        if (!fromDb) {
            cv.put("title", h.title); cv.put("platform", h.platform); cv.put("platform_name", h.platformName)
            cv.put("cover", h.cover); cv.put("author", h.author); cv.put("source_url", h.sourceUrl)
            cv.put("video_url", h.videoUrl); cv.put("quality", h.quality)
            cv.put("image_urls", h.imageUrls.toString()); cv.put("audio_urls", h.audioUrls.toString())
        }
        cv.put("parsed_at", parsedAt)
        if (plus > 0) cv.put("download_status", plus)
        db.update("history", cv, "id=?", arrayOf(id.toString()))
    }

    fun setHistoryFlag(id: Long, isFavorite: Boolean) {
        db.execSQL("UPDATE history SET is_favorite=? WHERE id=?", arrayOf(if (isFavorite) 1 else 0, id))
    }

    fun allHistory(): List<MediaBean> {
        val out = ArrayList<MediaBean>()
        db.query("history", null, "is_recycled=0", null, null, null, "parsed_at DESC").use { c ->
            while (c.moveToNext()) out.add(fromHistoryCursor(c))
        }
        return out
    }

    fun historyById(id: Long): MediaBean? {
        db.query("history", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return fromHistoryCursor(c)
        }
        return null
    }

    /** 软删除 → 回收站，返回 true 表示应保留历史（有下载任务） */
    fun softDeleteHistory(id: Long) {
        val h = historyById(id) ?: return
        // 有下载文件的记录保留历史（下载的二级分类需要），仅标记回收
        db.execSQL("UPDATE history SET is_recycled=1 WHERE id=?", arrayOf(id.toString()))
        putRecycle("history", h.title, h.toJson())
    }

    fun purgeHistory(id: Long) {
        db.execSQL("DELETE FROM history WHERE id=?", arrayOf(id.toString()))
    }

    fun restoreHistory(payload: String): Long {
        val h = MediaBean.fromJson(payload)
        // 重新插入（避免 id 冲突，直接插新记录）
        return if (h.sourceUrl.isNotEmpty()) addHistory(h) else -1L
    }

    // ================= favorites =================
    fun addFavorite(historyId: Long, categoryId: Long): Long {
        val cv = ContentValues().apply {
            put("history_id", historyId)
            put("category_id", categoryId)
            put("created_at", System.currentTimeMillis() / 1000)
        }
        return db.insert("favorites", null, cv)
    }

    fun removeFavorite(historyId: Long) {
        db.execSQL("DELETE FROM favorites WHERE history_id=?", arrayOf(historyId.toString()))
        setHistoryFlag(historyId, false)
    }

    fun favorites(categoryId: Long = -1): List<FavoriteBean> {
        val out = ArrayList<FavoriteBean>()
        val sql = if (categoryId >= 0)
            "SELECT f.*, h.title, h.platform_name, h.platform, h.cover, h.author, h.source_url, h.video_url, h.quality, h.image_urls, h.audio_urls, h.parsed_at " +
            "FROM favorites f JOIN history h ON f.history_id=h.id WHERE f.category_id=? ORDER BY f.created_at DESC"
        else
            "SELECT f.*, h.title, h.platform_name, h.platform, h.cover, h.author, h.source_url, h.video_url, h.quality, h.image_urls, h.audio_urls, h.parsed_at " +
            "FROM favorites f JOIN history h ON f.history_id=h.id ORDER BY f.created_at DESC"
        db.rawQuery(sql, if (categoryId >= 0) arrayOf(categoryId.toString()) else null).use { c ->
            while (c.moveToNext()) {
                out.add(FavoriteBean(
                    id = c.getLong(0), historyId = c.getLong(1), categoryId = c.getLong(2), createdAt = c.getLong(3),
                    bean = MediaBean(
                        id = c.getLong(1), // ← 关键：收藏 JOIN 必须带上真实 history id，
                        // 否则收藏页长按面板/多选里的删除、取消收藏、下载等操作拿到的 bean.id 恒为 0，全部静默失效
                        title = c.getStringOr(4), platform = c.getStringOr(6), platformName = c.getStringOr(5),
                        cover = c.getStringOr(7), author = c.getStringOr(8), sourceUrl = c.getStringOr(9),
                        videoUrl = c.getStringOr(10), quality = c.getStringOr(11),
                        imageUrls = c.parseArr(12), audioUrls = c.parseArr(13), parsedAt = c.getLong(14)
                    )
                ))
            }
        }
        return out
    }

    fun isFavorite(historyId: Long): Boolean {
        db.query("favorites", arrayOf("id"), "history_id=?", arrayOf(historyId.toString()), null, null, null).use {
            return it.count > 0
        }
    }

    // ================= categories =================
    fun addCategory(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis() / 1000)
        }
        return db.insert("categories", null, cv)
    }

    fun renameCategory(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name) }
        db.update("categories", cv, "id=?", arrayOf(id.toString()))
    }

    fun categories(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        db.query("categories", null, null, null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    fun deleteCategory(id: Long) {
        db.execSQL("UPDATE favorites SET category_id=0 WHERE category_id=?", arrayOf(id.toString()))
        db.execSQL("DELETE FROM categories WHERE id=?", arrayOf(id.toString()))
    }

    fun moveFavorites(ids: List<Long>, categoryId: Long) {
        for (id in ids) {
            val cv = ContentValues().apply { put("category_id", categoryId) }
            db.update("favorites", cv, "id=?", arrayOf(id.toString()))
        }
    }

    // ================= downloads =================
    fun addDownload(d: DownloadBean): Long {
        val cv = ContentValues().apply {
            put("history_id", d.historyId)
            put("url", d.url)
            put("file_name", d.fileName)
            put("kind", d.kind)
            put("total_size", d.totalSize)
            put("downloaded_size", d.downloadedSize)
            put("status", d.status)
            put("created_at", System.currentTimeMillis() / 1000)
        }
        return db.insert("downloads", null, cv)
    }

    fun allDownloads(): List<DownloadBean> {
        val out = ArrayList<DownloadBean>()
        db.query("downloads", null, null, null, null, null, "created_at DESC").use { c ->
            while (c.moveToNext()) out.add(fromDownloadCursor(c))
        }
        return out
    }

    fun downloadById(id: Long): DownloadBean? {
        db.query("downloads", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) return fromDownloadCursor(c)
        }
        return null
    }

    fun updateDownload(d: DownloadBean) {
        val cv = ContentValues().apply {
            put("total_size", d.totalSize)
            put("downloaded_size", d.downloadedSize)
            put("status", d.status)
            put("file_name", d.fileName)
        }
        db.update("downloads", cv, "id=?", arrayOf(d.id.toString()))
    }

    fun updateDownloadStatus(id: Long, status: Int) {
        val cv = ContentValues().apply { put("status", status) }
        db.update("downloads", cv, "id=?", arrayOf(id.toString()))
    }

    fun hardDeleteDownload(id: Long) {
        db.execSQL("DELETE FROM downloads WHERE id=?", arrayOf(id.toString()))
    }

    // ================= recycle =================
    private fun putRecycle(kind: String, title: String, payload: String) {
        val cv = ContentValues().apply {
            put("kind", kind)
            put("title", title)
            put("payload", payload)
            put("deleted_at", System.currentTimeMillis() / 1000)
        }
        db.insert("recycle", null, cv)
    }

    fun putRecycleDownload(d: DownloadBean) {
        val json = StringBuilder("{\"kind\":\"download\"")
        json.append(",\"id\":").append(d.id)
        json.append(",\"historyId\":").append(d.historyId)
        json.append(",\"url\":\"").append(d.url.replace("\"", "\\\""))
        json.append("\",\"fileName\":\"").append(d.fileName.replace("\"", "\\\""))
        json.append("\",\"kind\":\"").append(d.kind)
        json.append("\",\"status\":").append(d.status)
        json.append("}")
        putRecycle("download", d.fileName, json.toString())
    }

    fun recycled(): List<RecycleBean> {
        val out = ArrayList<RecycleBean>()
        db.query("recycle", null, null, null, null, null, "deleted_at DESC").use { c ->
            while (c.moveToNext()) {
                out.add(RecycleBean(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
            }
        }
        return out
    }

    /** 从回收站恢复：history 重新入库 / download 恢复任务 */
    fun restoreRecycle(id: Long) {
        val r = recycled().find { it.id == id } ?: return
        when (r.kind) {
            "history" -> {
                restoreHistory(r.payload)
            }
            "download" -> {
                try {
                    val j = org.json.JSONObject(r.payload)
                    val d = DownloadBean(
                        id = 0, historyId = j.optLong("historyId", 0), url = j.optString("url"),
                        fileName = j.optString("fileName"), kind = j.optString("kind", "video"),
                        totalSize = 0, downloadedSize = 0, status = DL_WAITING
                    )
                    addDownload(d)
                } catch (e: Exception) { }
            }
        }
        db.execSQL("DELETE FROM recycle WHERE id=?", arrayOf(id.toString()))
    }

    fun purgeRecycle(id: Long) {
        db.execSQL("DELETE FROM recycle WHERE id=?", arrayOf(id.toString()))
    }

    fun clearRecycle() {
        db.execSQL("DELETE FROM recycle")
    }

    // ================= helpers =================
    private fun fromHistoryCursor(c: Cursor): MediaBean = MediaBean(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getStringOr("title"), platform = c.getStringOr("platform"),
        platformName = c.getStringOr("platform_name"), cover = c.getStringOr("cover"),
        author = c.getStringOr("author"), sourceUrl = c.getStringOr("source_url"),
        videoUrl = c.getStringOr("video_url"), quality = c.getStringOr("quality"),
        imageUrls = c.parseArr("image_urls"), audioUrls = c.parseArr("audio_urls"),
        parsedAt = c.getLong(c.getColumnIndexOrThrow("parsed_at")),
        downloadStatus = c.getInt(c.getColumnIndexOrThrow("download_status")),
        isFavorite = c.getInt(c.getColumnIndexOrThrow("is_favorite")) == 1
    )

    private fun fromDownloadCursor(c: Cursor): DownloadBean = DownloadBean(
        id = c.getLong(0), historyId = c.getLong(1), url = c.getString(2), fileName = c.getString(3),
        kind = c.getString(4), totalSize = c.getLong(5), downloadedSize = c.getLong(6),
        status = c.getInt(7)
    )

    private fun Cursor.getStringOr(name: String): String {
        val i = getColumnIndex(name)
        return if (i >= 0) getString(i) ?: "" else ""
    }
    private fun Cursor.getStringOr(idx: Int): String = if (idx >= 0) getString(idx) ?: "" else ""
    private fun Cursor.parseArr(idx: Int): JSONArray {
        return try { JSONArray(getStringOr(idx)) } catch (e: Exception) { JSONArray() }
    }
    private fun Cursor.parseArr(name: String): JSONArray {
        return try { JSONArray(getStringOr(name)) } catch (e: Exception) { JSONArray() }
    }
}

// ================= 数据 bean =================
class MediaBean(
    var id: Long = 0,
    var title: String = "",
    var platform: String = "",
    var platformName: String = "",
    var cover: String = "",
    var author: String = "",
    var sourceUrl: String = "",
    var videoUrl: String = "",
    var quality: String = "",
    var imageUrls: JSONArray = JSONArray(),
    var audioUrls: JSONArray = JSONArray(),
    var parsedAt: Long = 0,
    var downloadStatus: Int = DB.DL_NONE,
    var isFavorite: Boolean = false
) {
    fun firstVideoUrl(): String {
        if (videoUrl.isNotEmpty()) return videoUrl
        for (i in 0 until imageUrls.length()) {
            val o = imageUrls.optJSONObject(i)
            if (o != null) {
                val u = o.optString("url", "")
                if (u.isNotEmpty()) return u
            }
        }
        return ""
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"title\":\"").append(jsonEscape(title))
        sb.append("\",\"platform\":\"").append(jsonEscape(platform))
        sb.append("\",\"platformName\":\"").append(jsonEscape(platformName))
        sb.append("\",\"cover\":\"").append(jsonEscape(cover))
        sb.append("\",\"author\":\"").append(jsonEscape(author))
        sb.append("\",\"sourceUrl\":\"").append(jsonEscape(sourceUrl))
        sb.append("\",\"videoUrl\":\"").append(jsonEscape(videoUrl))
        sb.append("\",\"quality\":\"").append(jsonEscape(quality))
        sb.append("\",\"imageUrls\":").append(imageUrls.toString())
        sb.append(",\"audioUrls\":").append(audioUrls.toString())
        sb.append(",\"parsedAt\":").append(parsedAt).append("}")
        return sb.toString()
    }

    companion object {
        private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        fun fromJson(json: String): MediaBean {
            val b = MediaBean()
            try {
                val j = org.json.JSONObject(json)
                b.title = j.optString("title"); b.platform = j.optString("platform")
                b.platformName = j.optString("platformName"); b.cover = j.optString("cover")
                b.author = j.optString("author"); b.sourceUrl = j.optString("sourceUrl")
                b.videoUrl = j.optString("videoUrl"); b.quality = j.optString("quality")
                b.imageUrls = j.optJSONArray("imageUrls") ?: JSONArray()
                b.audioUrls = j.optJSONArray("audioUrls") ?: JSONArray()
                b.parsedAt = j.optLong("parsedAt")
            } catch (e: Exception) { }
            return b
        }
    }
}

class FavoriteBean(
    var id: Long = 0,
    var historyId: Long = 0,
    var categoryId: Long = 0,
    var createdAt: Long = 0,
    var bean: MediaBean
)

class DownloadBean(
    var id: Long = 0,
    var historyId: Long = 0,
    var url: String = "",
    var fileName: String = "",
    var kind: String = "video",
    var totalSize: Long = 0,
    var downloadedSize: Long = 0,
    var status: Int = DB.DL_NONE
)

class RecycleBean(
    var id: Long = 0,
    var kind: String = "",
    var title: String = "",
    var payload: String = "",
    var deletedAt: Long = 0
)

class DBHelper(context: Context) : SQLiteOpenHelper(context, "media_parser.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE history(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT, platform TEXT, platform_name TEXT, cover TEXT, author TEXT," +
            "source_url TEXT, video_url TEXT, quality TEXT, image_urls TEXT, audio_urls TEXT," +
            "parsed_at INTEGER, download_status INTEGER DEFAULT 0," +
            "is_favorite INTEGER DEFAULT 0, is_recycled INTEGER DEFAULT 0)")
        d.execSQL("CREATE TABLE favorites(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "history_id INTEGER, category_id INTEGER DEFAULT 0, created_at INTEGER)")
        d.execSQL("CREATE TABLE categories(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, created_at INTEGER)")
        d.execSQL("CREATE TABLE downloads(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "history_id INTEGER DEFAULT 0, url TEXT, file_name TEXT, kind TEXT," +
            "total_size INTEGER DEFAULT 0, downloaded_size INTEGER DEFAULT 0," +
            "status INTEGER DEFAULT 0, created_at INTEGER)")
        d.execSQL("CREATE TABLE recycle(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT, payload TEXT, deleted_at INTEGER)")
    }

    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
}
package com.qingjiexi.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.io.IOException

/** 通用浏览器 UA */
const val GS_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/** 根据媒体地址推导需要的 Referer（对照 Rust 侧下载策略） */
fun chooseReferer(url: String): String {
    val u = url.lowercase()
    return when {
        u.contains("bilibili") || u.contains("mcdn") || u.contains("mountaintoys") || u.contains("upos") ->
            "https://www.bilibili.com/"
        u.contains("douyinvod") || u.contains("douyinstatic") || u.contains("iesdouyin") ->
            "https://www.douyin.com/"
        u.contains("byte") || u.contains("ibyte") || u.contains("ibytedtos") ->
            "https://www.tiktok.com/"
        u.contains("gifshow") || u.contains("yximgs") || u.contains("kuaishou") ->
            "https://www.kuaishou.com/"
        u.contains("twitter") || u.contains("twimg") || u.contains("x.com") ->
            "https://twitter.com/"
        else -> ""
    }
}

/**
 * 支持自定义请求头（UA / Referer）的视频播放器。
 * 抖音 / B站 / 快手等直链都要求携带请求头，否则 403/416 导致
 * 预览黑屏 —— 这是原生 VideoView 无法解决的，因此自绘 SurfaceView + MediaPlayer。
 *
 * 增强：
 * 1. 支持传入多个候选直链，播放失败自动切换到下一个（直链常有短时效）
 * 2. 内置加载中 / 播放失败覆盖层，失败可点击重试，不再只有黑屏
 * 3. onRetry 回调可让调用方在全部失效时引导用户重新解析
 */
class HeaderVideoView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: FrameLayout
    private lateinit var spin: ProgressBar
    private lateinit var ovIcon: GlyphView
    private lateinit var ovText: TextView
    private lateinit var controlsBar: LinearLayout
    private lateinit var playBtn: GlyphView
    private lateinit var speedBtn: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeTxt: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var seekDragging = false
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgressUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun fmtTime(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    private var mp: MediaPlayer? = null
    private var urls: List<String> = emptyList()
    private var attemptIndex = 0
    private var surfaceReady = false
    private var released = false
    private var opened = false

    /** 解析到的视频原始尺寸（准备完成后有效），用于按原始比例摆放预览框 */
    var videoW = 0
    var videoH = 0
    /** 当前倍速（0.5 / 1 / 1.5 / 2） */
    var speed = 1f
        private set

    /** 是否在准备好后自动播放 */
    var autoPlay: Boolean = true

    /** 播放错误回调（返回 true 表示已处理） */
    var onError: ((String) -> Boolean)? = null

    /** 全部候选 URL 都播放失败时回调（可引导重新解析） */
    var onRetry: (() -> Unit)? = null

    /** 视频原始尺寸回调（vw × vh），调用方据此等比摆放控件，避免拉伸变形 */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    /** 下载回调（控制条下载按钮） */
    var onDownload: (() -> Unit)? = null

    /** 全屏切换回调（点击视频画面） */
    var onToggleFullscreen: (() -> Unit)? = null

    private var prepared = false
    /** 用户期望的播放状态（暂停按钮会置 false；切回表面后据此恢复播放） */
    private var userWantsPlay = false

    init {
        setBackgroundColor(0xFF000000.toInt())
        surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(this)
        addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = FrameLayout(context).apply { setBackgroundColor(0x55000000) }
        spin = ProgressBar(context).apply { isIndeterminate = true }
        ovIcon = GlyphView(context).apply { icon = "refresh"; tint = 0xFFEDEDED.toInt(); strokeW = 1.8f }
        ovText = TextView(context).apply {
            text = "视频缓冲中…"; setTextColor(0xFFEDEDED.toInt()); textSize = 12.5f
            includeFontPadding = false
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { retry() }
        }
        col.addView(spin, LinearLayout.LayoutParams(dp(26), dp(26)))
        col.addView(ovIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        col.addView(ovText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        overlay.visibility = View.GONE
        addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 底部控制条：播放/暂停 · 倍速 · 下载（迷你化 iOS 悬浮胶囊，白图标衬深色底，
        // 避免线条图标漂浮在亮色上显出"空心白框"）
        controlsBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
            isClickable = true
            background = rndBkg(0x8C000000.toInt(), 14)
        }
        playBtn = GlyphView(context).apply {
            icon = "pause"; tint = 0xFFFFFFFF.toInt(); strokeW = 1.7f
            setOnClickListener { togglePlay() }
        }
        controlsBar.addView(playBtn, LinearLayout.LayoutParams(dp(26), dp(26)))
        speedBtn = TextView(context).apply {
            text = "1x"; textSize = 11f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { cycleSpeed() }
        }
        speedBtn.setPadding(dp(8), dp(3), dp(8), dp(3))
        controlsBar.addView(speedBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        // 进度条：可拖动跳转（iOS 极简风——细轨道 + 圆点滑块）
        seekBar = SeekBar(context).apply {
            max = 1000
            progressDrawable = context.resources.getDrawable(R.drawable.seekbar_progress)
            thumb = context.resources.getDrawable(R.drawable.seekbar_thumb)
            setPaddingRelative(dp(2), 0, dp(2), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val p = mp ?: return
                    if (!prepared) return
                    val dur = runCatching { p.duration }.getOrElse { 0 }
                    if (dur > 0) runCatching { p.seekTo((dur.toLong() * progress / 1000).toInt()) }
                    updateTimeText(runCatching { p.currentPosition }.getOrElse { 0 }.toLong(), dur.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seekDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seekDragging = false
                    updateProgressUi()
                }
            })
        }
        controlsBar.addView(seekBar, LinearLayout.LayoutParams(0, dp(26), 1f).apply { leftMargin = dp(8) })
        timeTxt = TextView(context).apply {
            text = "00:00 / 00:00"
            textSize = 10.5f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        controlsBar.addView(timeTxt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        val dlBtn = GlyphView(context).apply {
            icon = "download"; tint = 0xFFFFFFFF.toInt(); strokeW = 1.7f
            setOnClickListener { onDownload?.invoke() }
        }
        controlsBar.addView(dlBtn, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(8) })
        val barLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        barLp.setMargins(dp(10), 0, dp(10), dp(10))
        addView(controlsBar, barLp)

        // 点击画面 → 全屏 / 退出全屏
        setOnClickListener { onToggleFullscreen?.invoke() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 圆角背景（用于播放器悬浮控件） */
    private fun rndBkg(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (urls.isEmpty()) return
        if (!opened) {
            openInternal()
        } else {
            // View 被重新挂载（进出全屏时父容器变化），把播放器重新绑定到新的 Surface
            val p = mp
            if (p != null) {
                runCatching { p.setSurface(holder.surface) }
                if (prepared && userWantsPlay) runCatching { p.start() }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // 只暂停播放，保留 userWantsPlay，进出全屏导致 Surface 重建后可自动续播
        runCatching { if (mp?.isPlaying == true) mp?.pause() }
    }

    /** 加载并自动播放；传入多个候选地址，失败自动切换 */
    fun load(urls: List<String>) {
        this.urls = urls.filter { it.isNotBlank() }
        attemptIndex = 0
        opened = false
        prepared = false
        userWantsPlay = false
        showLoading()
        if (surfaceReady) openInternal()
    }

    /** 释放全部资源（View 移除时必须调用，避免泄漏） */
    fun release() {
        released = true
        uiHandler.removeCallbacks(progressTick)
        runCatching { mp?.release() }
        mp = null
        prepared = false
    }

    fun pause() {
        userWantsPlay = false
        runCatching { if (mp?.isPlaying == true) mp?.pause() }
        updatePlayBtn()
    }

    fun resume() {
        if (prepared) { userWantsPlay = true; runCatching { mp?.start() }; updatePlayBtn() }
    }

    fun isPlaying(): Boolean = mp?.isPlaying == true

    /** 播放/暂停切换（控制条按钮） */
    fun togglePlay() {
        val p = mp
        if (p == null || !prepared) return
        if (p.isPlaying) { pause() } else { userWantsPlay = true; runCatching { p.start() }; updatePlayBtn() }
    }

    /** 循环切换倍速：1x → 1.5x → 2x → 0.5x → 1x */
    fun cycleSpeed() {
        speed = when (speed) { 0.5f -> 1f; 1f -> 1.5f; 1.5f -> 2f; else -> 0.5f }
        speedBtn.text = if (speed == 1f) "1x" else "${speed}x"
        if (prepared) runCatching {
            val p = mp ?: return@runCatching
            p.playbackParams = (p.playbackParams ?: android.media.PlaybackParams()).setSpeed(speed)
        }
    }

    private fun updatePlayBtn() {
        playBtn.icon = if (prepared && mp?.isPlaying == true) "pause" else "play"
    }

    /** 周期刷新进度条与时间文本（播放中每 500ms 一次） */
    private fun updateProgressUi() {
        val p = mp ?: return
        if (!prepared || seekDragging) return
        val dur = runCatching { p.duration }.getOrElse { 0 }
        val pos = runCatching { p.currentPosition }.getOrElse { 0 }.toLong()
        if (dur > 0) {
            seekBar.max = 1000
            seekBar.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
        }
        updateTimeText(pos, dur.toLong())
    }

    private fun updateTimeText(pos: Long, dur: Long) {
        if (::timeTxt.isInitialized) timeTxt.text = "${fmtTime(pos)} / ${fmtTime(dur)}"
    }

    private fun retry() {
        if (urls.isEmpty()) return
        if (mp != null) { runCatching { mp?.release() }; mp = null }
        attemptIndex = 0
        prepared = false
        showLoading()
        if (surfaceReady) openInternal()
    }

    private fun showLoading() {
        overlay.visibility = View.VISIBLE
        spin.visibility = View.VISIBLE
        ovIcon.visibility = View.GONE
        ovText.text = "视频缓冲中…"
        controlsBar.visibility = View.GONE
    }

    private fun showError(msg: String) {
        overlay.visibility = View.VISIBLE
        spin.visibility = View.GONE
        ovIcon.visibility = View.VISIBLE
        ovText.text = msg
        controlsBar.visibility = View.GONE
        onError?.invoke(msg)
    }

    private fun openInternal() {
        if (surfaceReady == false) { opened = false; return }
        if (attemptIndex >= urls.size) {
            showError("视频暂无法预览\n点击重试或直接下载")
            return
        }
        opened = true
        val url = urls[attemptIndex]
        try {
            runCatching { mp?.release() }
            val p = MediaPlayer()
            mp = p
            p.setSurface(surfaceView.holder.surface)
            val referer = chooseReferer(url)
            val headers = HashMap<String, String>().apply {
                put("User-Agent", GS_UA)
                if (referer.isNotEmpty()) put("Referer", referer)
            }
            p.setDataSource(context, Uri.parse(url), headers)
            p.setOnPreparedListener {
                prepared = true
                val vw = p.videoWidth; val vh = p.videoHeight
                if (vw > 0 && vh > 0 && (vw != videoW || vh != videoH)) {
                    videoW = vw; videoH = vh
                    onVideoSize?.invoke(vw, vh)
                }
                overlay.visibility = View.GONE
                controlsBar.visibility = View.VISIBLE
                updatePlayBtn()
                updateProgressUi()
                uiHandler.removeCallbacks(progressTick)
                uiHandler.post(progressTick)
                if (autoPlay && !userWantsPlay) {
                    userWantsPlay = true
                    p.start()
                    updatePlayBtn()
                } else if (userWantsPlay) {
                    p.start()
                    updatePlayBtn()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                prepared = false
                runCatching { p.release() }
                if (mp === p) mp = null
                attemptIndex++
                if (attemptIndex < urls.size) {
                    showLoading()
                    openInternal()
                } else {
                    showError("视频暂无法预览\n点击重试或直接下载")
                    onRetry?.invoke()
                }
                true
            }
            p.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
            if (speed != 1f) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
            p.prepareAsync()
        } catch (e: IOException) {
            attemptIndex++; openInternal()
        } catch (e: Exception) {
            showError("播放器初始化失败")
        }
    }
}

/**
 * 极简线性矢量图标（X / iOS 风格），纯 Canvas 绘制，
 * 用于替代所有 emoji / 字符占位图标。
 *
 * 坐标系：24x24 网格，stroke 线宽约 1.8。
 */
class GlyphView(context: Context) : View(context) {
    var icon: String = "home"
        set(value) { field = value; invalidate() }
    var tint: Int = 0xFF0A84FF.toInt()
        set(value) { field = value; invalidate() }
    var strokeW: Float = 1.8f
        set(value) { field = value; invalidate() }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width.toFloat(), height.toFloat())
        strokePaint.strokeWidth = s / 24f * strokeW
        strokePaint.color = tint
        fillPaint.color = tint
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 24f, s / 24f)
        tmp.reset()
        drawIcon(canvas, strokePaint, fillPaint, tmp)
        canvas.restore()
    }

    private fun drawIcon(c: Canvas, p: Paint, fp: Paint, path: Path) {
        fun seg(vararg pts: Float) {
            path.let { it.reset(); it.moveTo(pts[0], pts[1]); var i = 2
                while (i < pts.size) { it.lineTo(pts[i], pts[i + 1]); i += 2 } }
            c.drawPath(path, p)
        }
        var x1: Float; var y1: Float; var x2: Float; var y2: Float; var r: Float
        when (icon) {
            "home" -> {
                path.reset(); path.moveTo(3f, 10.8f); path.lineTo(12f, 3.4f); path.lineTo(21f, 10.8f)
                c.drawPath(path, p)
                seg(5.2f, 9.6f, 5.2f, 20.4f, 18.8f, 20.4f, 18.8f, 9.6f)
                seg(9.6f, 20.4f, 9.6f, 15.2f, 14.4f, 15.2f, 14.4f, 20.4f)
            }
            "clock" -> {
                c.drawCircle(12f, 12f, 8.4f, p)
                seg(12f, 7.4f, 12f, 12.2f, 15.4f, 14.3f)
            }
            "star" -> {
                path.reset(); path.moveTo(12f, 3.2f); path.lineTo(14.7f, 8.8f); path.lineTo(20.8f, 9.6f)
                path.lineTo(16.4f, 13.9f); path.lineTo(17.4f, 20f); path.lineTo(12f, 17.2f)
                path.lineTo(6.6f, 20f); path.lineTo(7.6f, 13.9f); path.lineTo(3.2f, 9.6f)
                path.lineTo(9.3f, 8.8f); path.close()
                c.drawPath(path, fp)
            }
            "starline" -> {
                path.reset(); path.moveTo(12f, 3.2f); path.lineTo(14.7f, 8.8f); path.lineTo(20.8f, 9.6f)
                path.lineTo(16.4f, 13.9f); path.lineTo(17.4f, 20f); path.lineTo(12f, 17.2f)
                path.lineTo(6.6f, 20f); path.lineTo(7.6f, 13.9f); path.lineTo(3.2f, 9.6f)
                path.lineTo(9.3f, 8.8f); path.close()
                c.drawPath(path, p)
            }
            "user" -> {
                c.drawCircle(12f, 8.4f, 3.6f, p)
                path.reset(); path.moveTo(4.8f, 20.2f)
                path.cubicTo(4.8f, 15.6f, 8.2f, 13.4f, 12f, 13.4f)
                path.cubicTo(15.8f, 13.4f, 19.2f, 15.6f, 19.2f, 20.2f)
                c.drawPath(path, p)
            }
            "play" -> {
                path.reset(); path.moveTo(9.2f, 6.6f); path.lineTo(17.4f, 12f); path.lineTo(9.2f, 17.4f); path.close()
                c.drawPath(path, fp)
            }
            "download" -> {
                seg(12f, 4f, 12f, 14.6f)
                seg(7.2f, 10.2f, 12f, 15f, 16.8f, 10.2f)
                seg(4.4f, 19.4f, 19.6f, 19.4f)
            }
            "music" -> {
                path.reset(); path.moveTo(9.2f, 17.6f); path.lineTo(9.2f, 6.4f)
                path.lineTo(18.8f, 4.6f); path.lineTo(18.8f, 15.6f)
                c.drawPath(path, p)
                c.drawCircle(7.2f, 17.6f, 2.1f, p)
                c.drawCircle(16.8f, 15.6f, 2.1f, p)
            }
            "trash" -> {
                seg(4.4f, 7.2f, 19.6f, 7.2f)
                seg(9.4f, 7.2f, 9.4f, 4.8f, 14.6f, 4.8f, 14.6f, 7.2f)
                seg(6.2f, 7.2f, 7f, 20.2f, 17f, 20.2f, 17.8f, 7.2f)
                seg(10.3f, 10.6f, 10.3f, 16.8f)
                seg(13.7f, 10.6f, 13.7f, 16.8f)
            }
            "bell" -> {
                path.reset(); path.moveTo(12f, 3.8f)
                path.cubicTo(8.6f, 3.8f, 6.6f, 6.2f, 6.6f, 9.6f)
                path.cubicTo(6.6f, 12.4f, 5.4f, 13.8f, 4.6f, 15.2f)
                path.cubicTo(4.3f, 15.8f, 4.8f, 16.6f, 5.6f, 16.6f)
                path.lineTo(18.4f, 16.6f)
                path.cubicTo(19.2f, 16.6f, 19.7f, 15.8f, 19.4f, 15.2f)
                path.cubicTo(18.6f, 13.8f, 17.4f, 12.4f, 17.4f, 9.6f)
                path.cubicTo(17.4f, 6.2f, 15.4f, 3.8f, 12f, 3.8f)
                c.drawPath(path, p)
                seg(10.6f, 19.6f, 11.2f, 20.4f, 12.8f, 20.4f, 13.4f, 19.6f)
            }
            "bolt" -> {
                path.reset(); path.moveTo(13.6f, 3.2f); path.lineTo(6.4f, 13.4f); path.lineTo(11.4f, 13.4f)
                path.lineTo(10.4f, 20.8f); path.lineTo(17.6f, 10.6f); path.lineTo(12.6f, 10.6f); path.close()
                c.drawPath(path, fp)
            }
            "chevron" -> {
                path.reset(); path.moveTo(9.4f, 5.2f); path.lineTo(15.6f, 12f); path.lineTo(9.4f, 18.8f)
                c.drawPath(path, p)
            }
            "back" -> {
                path.reset(); path.moveTo(14.6f, 5.2f); path.lineTo(8.4f, 12f); path.lineTo(14.6f, 18.8f)
                c.drawPath(path, p)
            }
            "check" -> {
                path.reset(); path.moveTo(5.2f, 12.6f); path.lineTo(9.8f, 17.2f); path.lineTo(18.8f, 7.4f)
                c.drawPath(path, p)
            }
            "x" -> {
                seg(6.4f, 6.4f, 17.6f, 17.6f)
                seg(17.6f, 6.4f, 6.4f, 17.6f)
            }
            "refresh" -> {
                path.reset(); path.addArc(6f, 5f, 18f, 17f, 40f, 300f)
                c.drawPath(path, p)
                seg(18.4f, 12.6f, 17.4f, 6.8f, 12.6f, 8.4f)
            }
            "more" -> {
                c.drawCircle(5.4f, 12f, 1.5f, fp)
                c.drawCircle(12f, 12f, 1.5f, fp)
                c.drawCircle(18.6f, 12f, 1.5f, fp)
            }
            "share" -> {
                seg(12f, 4.2f, 12f, 14.2f)
                seg(7.8f, 8.6f, 12f, 4.2f, 16.2f, 8.6f)
                seg(5f, 11.6f, 5f, 19.6f, 19f, 19.6f, 19f, 11.6f)
            }
            "image" -> {
                path.reset(); path.addRect(4.4f, 6f, 19.6f, 18f, Path.Direction.CW)
                c.drawPath(path, p)
                c.drawCircle(9.2f, 10.2f, 1.5f, p)
                path.reset(); path.moveTo(5.4f, 17f); path.lineTo(10.2f, 11.8f)
                path.lineTo(13.8f, 15.2f); path.lineTo(16f, 13f); path.lineTo(18.6f, 17f)
                c.drawPath(path, p)
            }
            "heart" -> {
                path.reset()
                path.moveTo(12f, 19.8f)
                path.cubicTo(7.6f, 16.4f, 3.8f, 13.2f, 3.8f, 9.4f)
                path.cubicTo(3.8f, 6.6f, 5.9f, 4.6f, 8.5f, 4.6f)
                path.cubicTo(10f, 4.6f, 11.3f, 5.3f, 12f, 6.4f)
                path.cubicTo(12.7f, 5.3f, 14f, 4.6f, 15.5f, 4.6f)
                path.cubicTo(18.1f, 4.6f, 20.2f, 6.6f, 20.2f, 9.4f)
                path.cubicTo(20.2f, 13.2f, 16.4f, 16.4f, 12f, 19.8f)
                path.close()
                c.drawPath(path, fp)
            }
            "pause" -> {
                seg(8.4f, 6.8f, 8.4f, 17.2f)
                seg(15.6f, 6.8f, 15.6f, 17.2f)
            }
            "stop" -> {
                path.reset(); path.addRect(7f, 7f, 17f, 17f, Path.Direction.CW)
                c.drawPath(path, fp)
            }
            "search" -> {
                c.drawCircle(11f, 11f, 5.8f, p)
                seg(15.4f, 15.4f, 20.2f, 20.2f)
            }
            "doc" -> {
                path.reset(); path.moveTo(6.2f, 3.6f); path.lineTo(14.6f, 3.6f); path.lineTo(18.4f, 7.4f)
                path.lineTo(18.4f, 20.4f); path.lineTo(6.2f, 20.4f); path.close()
                c.drawPath(path, p)
                seg(9.2f, 10.8f, 15f, 10.8f)
                seg(9.2f, 14f, 15f, 14f)
                seg(9.2f, 17.2f, 12.4f, 17.2f)
            }
            "plus" -> {
                seg(12f, 5.4f, 12f, 18.6f)
                seg(5.4f, 12f, 18.6f, 12f)
            }
            "bin" -> {
                seg(4.6f, 6.2f, 19.4f, 6.2f)
                seg(9.2f, 6.2f, 9.2f, 8.2f, 14.8f, 8.2f, 14.8f, 6.2f)
                seg(6.4f, 8.2f, 7.2f, 20.2f, 16.8f, 20.2f, 17.6f, 8.2f)
            }
            "gallery" -> {
                path.reset(); path.addRect(3.8f, 5.2f, 20.2f, 18.8f, Path.Direction.CW)
                c.drawPath(path, p)
                c.drawCircle(8.6f, 9.6f, 1.4f, p)
                path.reset(); path.moveTo(5.2f, 17.8f); path.lineTo(10f, 12.6f)
                path.lineTo(13.4f, 15.8f); path.lineTo(15.6f, 13.6f); path.lineTo(18.8f, 17.8f)
                c.drawPath(path, p)
            }
            "headset" -> {
                path.reset(); path.moveTo(4.6f, 12f)
                path.cubicTo(4.6f, 7.4f, 7.9f, 3.8f, 12f, 3.8f)
                path.cubicTo(16.1f, 3.8f, 19.4f, 7.4f, 19.4f, 12f)
                c.drawPath(path, p)
                path.reset(); path.addRoundRect(3.2f, 11.4f, 6f, 18.2f, 2f, 2f, Path.Direction.CW)
                c.drawPath(path, p)
                path.reset(); path.addRoundRect(18f, 11.4f, 20.8f, 18.2f, 2f, 2f, Path.Direction.CW)
                c.drawPath(path, p)
            }
            "send" -> {
                path.reset(); path.moveTo(4f, 4f); path.lineTo(20f, 12f); path.lineTo(4f, 20f)
                path.lineTo(7f, 12f); path.close()
                c.drawPath(path, fp)
                seg(7f, 12f, 20f, 12f)
            }
            "link" -> {
                path.reset(); path.moveTo(9.4f, 14.6f)
                path.lineTo(14.6f, 9.4f)
                c.drawPath(path, p)
                path.reset(); path.moveTo(7.4f, 12f); path.lineTo(5.6f, 13.8f)
                path.cubicTo(4.4f, 15f, 4.4f, 16.9f, 5.6f, 18.1f)
                path.cubicTo(6.8f, 19.3f, 8.7f, 19.3f, 9.9f, 18.1f)
                path.lineTo(13f, 15f)
                c.drawPath(path, p)
                path.reset(); path.moveTo(16.6f, 12f); path.lineTo(18.4f, 10.2f)
                path.cubicTo(19.6f, 9f, 19.6f, 7.1f, 18.4f, 5.9f)
                path.cubicTo(17.2f, 4.7f, 15.3f, 4.7f, 14.1f, 5.9f)
                path.lineTo(11f, 9f)
                c.drawPath(path, p)
            }
            "sliders" -> {
                // iOS 设置风格滑块：三条横杆，长短递增 + 手柄圆点
                seg(3.6f, 7.4f, 11.4f, 7.4f)
                c.drawCircle(13.8f, 7.4f, 1.8f, fp)
                seg(15.6f, 7.4f, 20.4f, 7.4f)
                seg(3.6f, 12f, 7.4f, 12f)
                c.drawCircle(10f, 12f, 1.8f, fp)
                seg(11.8f, 12f, 20.4f, 12f)
                seg(3.6f, 16.6f, 15.4f, 16.6f)
                c.drawCircle(18f, 16.6f, 1.8f, fp)
                seg(19.8f, 16.6f, 20.4f, 16.6f)
            }
        }
    }
}

/** 水平 1px 细分隔线（iOS 分组列表样式） */
fun hairline(context: Context, color: Int = 0xFFE5E5EA.toInt()): View =
    View(context).apply {
        setBackgroundColor(color)
        val d = context.resources.displayMetrics.density
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (d).toInt())
    }

/**
 * 全屏图片查看器用的可缩放 ImageView：
 * - 单指拖动平移（仅放大后）
 * - 双指捏合缩放（1x ~ 4x）
 * - 双击在 1x / 2x 之间切换（带弹性动画）
 * 未放大时不消费触摸事件，交给外层横向翻页容器处理滑动。
 */
class ZoomableImageView(context: Context) : ImageView(context) {

    private var curScale = 1f
    private val minScale = 1f
    private val maxScale = 4f
    private var viewW = 0
    private var viewH = 0
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var doubleTapped = false

    private val sgd = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (curScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            applyZoom(next)
            return true
        }
    })

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapped = true
            applyZoom(if (curScale > 1f) 1f else 2f)
            return true
        }
    })

    init {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnTouchListener { _, ev ->
            // 手势识别始终参与；是否“消费”由当前交互态决定，
            // 未放大时不消费 DOWN/MOVE，让外层横向翻页容器正常滑动
            sgd.onTouchEvent(ev)
            gd.onTouchEvent(ev)
            if (doubleTapped) { doubleTapped = false; return@setOnTouchListener true }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x; lastY = ev.y
                    panning = curScale > 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (panning && !sgd.isInProgress) {
                        val dx = ev.x - lastX; val dy = ev.y - lastY
                        translationX = (translationX + dx).coerceIn(-spanX(), spanX())
                        translationY = (translationY + dy).coerceIn(-spanY(), spanY())
                        lastX = ev.x; lastY = ev.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
            }
            curScale > 1f || sgd.isInProgress
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w; viewH = h
    }

    private fun spanX(): Float = viewW * (curScale - 1f) / 2f
    private fun spanY(): Float = viewH * (curScale - 1f) / 2f

    /** 缩放并纠正平移边界；回弹到 1x 时归位 */
    private fun applyZoom(next: Float) {
        curScale = next.coerceIn(minScale, maxScale)
        if (curScale <= 1f) {
            translationX = 0f; translationY = 0f
        } else {
            translationX = translationX.coerceIn(-spanX(), spanX())
            translationY = translationY.coerceIn(-spanY(), spanY())
        }
        animate().scaleX(curScale).scaleY(curScale)
            .setDuration(if (curScale == 1f) 220 else 0)
            .start()
    }

    /** 查看器切换页面前复位缩放 */
    fun resetZoom() {
        curScale = 1f
        animate().cancel()
        scaleX = 1f; scaleY = 1f
        translationX = 0f; translationY = 0f
    }
}
package com.qingjiexi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fx —— 液态玻璃 + 阻尼弹簧动效引擎（View 系统原生实现，零第三方依赖）。
 *
 * 移植自 legado-with-MD3（HapeLee/legado-with-MD3）的液态玻璃与 DampedDragAnimation 设计语言：
 * - GlassBlurView：真背景模糊（Android 12+ RenderEffect），把背后内容缩小采样后
 *   放大并整体高斯模糊，形成"内容从玻璃下面透出来"的真液态玻璃；低版本自动退化为半透明。
 * - Springy：基于阻尼弹簧物理（k / damping ratio）的动画器，对应 legado 的
 *   spring(stiffness, dampingRatio)。默认接近临界阻尼 → 丝滑"顺过去"，无生硬弹跳。
 */
class GlassBlurView(context: Context) : View(context) {

    private var snap: Bitmap? = null
    private var target: View? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val useBlur: Boolean

    /** 采样降采样倍率：越小性能越好；画面经 downSample 拉伸后由 RenderEffect 在"最终输出"上做高斯模糊
     *  （模糊半径 = dp(24f) 直接在输出像素空间生效，≈ legado haze 的 24dp，绝不能乘 downSample，
     *    否则半径将超过栏高 → 整条糊成雾面，这正是之前"不像真液态玻璃"的根因） */
    private val downSample = 3

    init {
        var ok = false
        try {
            ok = applyBlurCompat()
            setLayerType(LAYER_TYPE_HARDWARE, null)
        } catch (_: Throwable) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        useBlur = ok
        // 低版本（<12）或设备不支持：绘制空内容，只靠上层半透明白色蒙层呈现"玻璃"观感
        if (!ok) alpha = 0f
    }

    /** API 31+：反射创建高斯模糊 RenderEffect 并挂到本 View（兼容任意 compileSdk） */
    private fun applyBlurCompat(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val effectCls = Class.forName("android.graphics.RenderEffect")
            val create = effectCls.getMethod(
                "createBlurEffect",
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Shader.TileMode::class.java
            )
            val radius = dp(24f)
            val effect = create.invoke(null, radius, radius, Shader.TileMode.CLAMP)
            View::class.java.getMethod("setRenderEffect", effectCls).invoke(this, effect)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 绑定"背后要被透出来的内容 View" */
    fun setTarget(v: View?) {
        if (target !== v) { target = v; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!useBlur) return
        val t = target ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val dw = (w / downSample).coerceAtLeast(1)
        val dh = (h / downSample).coerceAtLeast(1)
        var bmp = snap
        if (bmp == null || bmp.width != dw || bmp.height != dh) {
            if (bmp != null) bmp.recycle()
            bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            snap = bmp
        }
        val c = Canvas(bmp)
        val sx = dw.toFloat() / w
        val sy = dh.toFloat() / h
        c.translate(-left * sx, -top * sy)
        c.scale(sx, sy)
        t.draw(c)
        canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snap?.recycle()
        snap = null
    }
}

/**
 * 阻尼弹簧动画器：每一帧按 半隐式欧拉 积分 弹簧力学
 *   a = -k·(x - target) - c·v ，c = 2·dampingRatio·sqrt(k)
 * 由全局 Choreographer 驱动，可多实例共用一个帧回调。
 */
class SpringScaler(private val get: () -> Float, private val set: (Float) -> Unit) {

    private var x = 0f
    private var v = 0f
    private var target = 0f
    private var running = false
    private var lastFrameNanos = 0L

    var stiffness = 950f
    var dampingRatio = 0.85f      // 0.85 ≈ 轻微欠阻尼：顺滑到位、几乎无过冲
    var tolerance = 0.0006f

    private val damping get() = 2 * dampingRatio * sqrt(stiffness)

    fun to(value: Float) {
        x = get()
        v = if (running) v else 0f
        target = value
        if (abs(x - target) < tolerance) { set(target); v = 0f; running = false; return }
        running = true
        lastFrameNanos = 0L
        Ticker.add(this)
    }

    fun stop() {
        running = false
        Ticker.remove(this)
    }

    internal fun step(nanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 1f / 120f
        else ((nanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 1f / 30f)
        lastFrameNanos = nanos
        // 半隐式欧拉
        val a = -stiffness * (x - target) - damping * v
        v += a * dt
        x += v * dt
        set(x)
        if (abs(x - target) < tolerance && abs(v) < 0.4f) {
            set(target); v = 0f; running = false
            Ticker.remove(this)
        }
    }
}

/** 全局弹簧帧驱动：所有在跑的 SpringScaler 共享同一个 Choreographer 回调 */
object Ticker {
    private val active = LinkedHashSet<SpringScaler>()
    private var posted = false
    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            posted = false
            if (active.isEmpty()) return
            for (s in active.toList()) s.step(frameTimeNanos)
            if (active.isNotEmpty() && !posted) {
                posted = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun add(s: SpringScaler) {
        active.add(s)
        if (!posted) {
            posted = true
            Choreographer.getInstance().postFrameCallback(cb)
        }
    }

    fun remove(s: SpringScaler) {
        active.remove(s)
    }
}

/** 简化的 View 级弹簧工具：给 View 做按压缩放（按下轻压 / 松手弹簧回弹） */
object Springy {

    private class Pair(var sx: SpringScaler, var sy: SpringScaler)

    private val map = HashMap<View, Pair>()

    private fun pair(v: View): Pair {
        var p = map[v]
        if (p == null) {
            val sx = SpringScaler({ v.scaleX }) { v.scaleX = it }
            val sy = SpringScaler({ v.scaleY }) { v.scaleY = it }
            p = Pair(sx, sy)
            map[v] = p
        }
        return p
    }

    /** 按压反馈缩放（阻尼比略低 → 有微妙的"果冻"回弹，仍算顺滑） */
    fun press(v: View) {
        val p = pair(v)
        p.sx.dampingRatio = 0.72f; p.sy.dampingRatio = 0.72f
        p.sx.to(0.94f); p.sy.to(0.94f)
    }

    /** 松手回弹到原始大小（接近临界阻尼 → 流畅顺回去，不弹跳） */
    fun release(v: View) {
        val p = pair(v)
        p.sx.dampingRatio = 0.86f; p.sy.dampingRatio = 0.86f
        p.sx.to(1f); p.sy.to(1f)
    }

    /** 通用弹性缩放（选中态动画等） */
    fun scaleTo(v: View, sx: Float, sy: Float, damping: Float = 0.78f, stiffness: Float = 820f) {
        val p = pair(v)
        p.sx.stiffness = stiffness; p.sx.dampingRatio = damping
        p.sy.stiffness = stiffness; p.sy.dampingRatio = damping
        p.sx.to(sx); p.sy.to(sy)
    }
}
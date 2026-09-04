package com.qingjiexi.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 轻解析 —— iOS 极简质感风视频解析器
 *
 * Tab：首页 / 历史 / 收藏 / 我的
 * 能力：粘贴解析、历史记录（按平台分类+下载二级分类）、收藏（自定义分类）、
 *      断点下载、回收站、通知栏实时进度、后台保活。
 */
class MainActivity : Activity() {

    // ---------- 配色（iOS 质感、丰富但克制） ----------
    companion object {
        val BG = 0xFFF2F2F7.toInt()
        val CARD = 0xFFFFFFFF.toInt()
        val TXT = 0xFF1D1D1F.toInt()
        val TXT3 = 0xFF8E8E93.toInt()
        val BLUE = 0xFF0A84FF.toInt()
        val PURPLE = 0xFFAF52DE.toInt()
        val CORAL = 0xFFFF6B57.toInt()
        val GREEN = 0xFF34C759.toInt()
        val RED = 0xFFFF3B30.toInt()
        val ORANGE = 0xFFFF9500.toInt()
        val SEP = 0xFFE5E5EA.toInt()
        val GOLD = 0xFFFFCC00.toInt()
        val TEAL = 0xFF00B8AE.toInt()
        val PINK = 0xFFFB7299.toInt()

        /** 开源仓库主页（“我的”页脚与“关于轻解析”面板共用） */
        const val GITHUB_URL = "https://github.com/ly5201314gjx/MediaParser"

        fun platformColor(p: String): Int = when (p) {
            "douyin" -> 0xFFFE2C55.toInt()
            "kuaishou" -> 0xFFFF4906.toInt()
            "twitter" -> 0xFF1D9BF0.toInt()
            "bilibili" -> PINK
            "tiktok" -> TEAL
            else -> BLUE
        }
    }

    // ---------- 状态 ----------
    private var currentTab = 0
    private var selectMode = false
    private var selectKind = ""          // history / favorites / recycle / downloaded
    private val selected = LinkedHashSet<Long>()
    /** 多选圆圈注册表：id → 当前页面上的选中圆圈 View（局部更新免整页重建 → 不再闪动/回顶） */
    private val selectChecks = HashMap<Long, View>()
    private var favCategoryFilter = -1L   // -1 全部
    private var historySegment = 0        // 0 全部 1 下载中 2 已下载
    private var pvMode = ""               // 当前可见子页面
    private var favCatDialog: AlertDialog? = null

    // ---- tab1 历史筛选：平台 + 时间 ----
    private var currentPlatform = "全部"   // 平台筛选（"全部" 表示不限）
    private var timeEntry = "全部"        // 时间筛选项（近一天/近7天/全部/自定义）
    private var timeFrom = 0L              // 毫秒；0 = 全部（不限时间）
    private var timeLabel = "全部"         // 用于自定义时间的展示文案

    private lateinit var imgLayer: FrameLayout
    private var imgPager: HorizontalScrollView? = null
    private var imgIndexTxt: TextView? = null
    private var imgPages = 0
    /** 图片查看器是否因全屏临时隐藏了底部玻璃栏（关闭后需还原） */
    private var viewerHidTabBar = false

    private lateinit var content: FrameLayout
    private lateinit var sheetLayer: FrameLayout
    private lateinit var fsLayer: FrameLayout
    private lateinit var tabBar: FrameLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var glassBlur: GlassBlurView
    private val tabPills = ArrayList<View>()
    private val tabIcons = ArrayList<GlyphView>()
    private val tabLabels = ArrayList<TextView>()
    private lateinit var serviceStarted: java.util.concurrent.atomic.AtomicBoolean
    private var lastRender = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaService.BROADCAST -> {
                    if (intent.hasExtra(MediaService.EXTRA_PARSE_OK)) {
                        val ok = intent.getBooleanExtra(MediaService.EXTRA_PARSE_OK, false)
                        val hid = intent.getLongExtra(MediaService.EXTRA_HISTORY_ID, -1)
                        val err = intent.getStringExtra(MediaService.EXTRA_PARSE_ERROR) ?: ""
                        onParseDone(ok, hid, err)
                    } else if (intent.hasExtra(MediaService.EXTRA_DL_ID)) {
                        val st = intent.getIntExtra(MediaService.EXTRA_DL_STATUS, -1)
                        val did = intent.getLongExtra(MediaService.EXTRA_DL_ID, -1)
                        // 收藏页：只局部刷新对应条目的下载状态（实时进度且不闪动/不回顶）
                        if (pvMode == "favorites" && !selectMode) {
                            if (did > 0) {
                                val dl = DB.downloadById(did)
                                if (dl != null) { updateFavDlCell(dl.historyId); return }
                            }
                            renderCurrent()
                        } else if (pvMode == "detail" && st != DB.DL_DOWNLOADING) {
                            // 全屏 / 查看器 / 弹层打开时不重建详情页，避免打断播放与破坏全屏上下文
                            if (fsHost == null && imgLayer.visibility != View.VISIBLE && !sheetShowing) renderCurrent()
                        } else refreshAllAfterDownload()
                    }
                }
            }
        }
    }

    // ---------- 生命周期 ----------
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        DB.init(this)
        serviceStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        buildRoot()
        if (Build.VERSION.SDK_INT >= 26) {
            registerReceiver(receiver, IntentFilter(MediaService.BROADCAST), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, IntentFilter(MediaService.BROADCAST))
        }

        // 进入立即请求：通知权限 + 电池后台长时运行权限
        requestPermissionsAtLaunch()
        switchTo(0)
        handleExternalIntent(intent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        for (v in liveVideos) v.release()
        liveVideos.clear()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 全屏 / 图集查看器 / 弹层打开时不重建页面：避免打断播放、破坏全屏上下文
        if (fsHost == null && imgLayer.visibility != View.VISIBLE && !sheetShowing) renderCurrent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
    }
    override fun onBackPressed() {
        if (imgLayer.visibility == View.VISIBLE) { closeImageViewer(); return }
        if (sheetShowing) { dismissSheet(); return }
        if (fsHost != null) { exitFullscreen(); return }
        if (selectMode) { exitSelect(); return }
        when (pvMode) {
            "recycle" -> { pvMode = "mine"; renderMine(); return }
            "detail" -> {
                // 返回进入详情前的 Tab（从收藏进入 → 回到收藏，而不是跳回 tab1）
                removeImmersive()
                currentTab = tabBeforeDetail
                pvMode = when (tabBeforeDetail) { 0 -> "home"; 1 -> "favorites"; else -> "mine" }
                renderTabBar(); renderCurrent(); showTabBar(); return
            }
        }
        super.onBackPressed()
    }

    // ---------- 权限 ----------
    private fun requestPermissionsAtLaunch() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) req.add(Manifest.permission.POST_NOTIFICATIONS)
        // Android 9 及以下：保存到相册/文件管理需要写外部存储权限
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) req.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (req.isNotEmpty()) requestPermissions(req.toTypedArray(), 200)

        // 电池后台长时间运行权限（白名单）
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        } catch (_: Exception) {}
    }

    // ---------- 布局骨架 ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 阻尼按压缩放反馈：按下弹簧轻压，松手弹簧回弹（iOS 触感风格，丝滑不弹跳） */
    private fun press(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> Springy.press(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Springy.release(view)
            }
            false
        }
    }

    private fun buildRoot() {
        // root 用 FrameLayout：body 全高铺满，底部栏叠加其上 → 内容可以滚动"透"进玻璃栏
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }
        // body 层级：内容层 + 全屏遮罩层（ActionSheet 等 iOS 风格弹层用）+ 视频全屏层，三者相互独立
        val body = FrameLayout(this)
        content = FrameLayout(this)
        sheetLayer = FrameLayout(this)
        sheetLayer.visibility = View.GONE
        fsLayer = FrameLayout(this)
        fsLayer.setBackgroundColor(0xFF000000.toInt())
        fsLayer.visibility = View.GONE
        imgLayer = FrameLayout(this)
        imgLayer.setBackgroundColor(0xFF000000.toInt())
        imgLayer.visibility = View.GONE
        body.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(sheetLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(imgLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(fsLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 底部操作栏（多选模式）：悬浮在内容之上
        actionBar = buildActionBar()
        actionBar.visibility = View.GONE
        root.addView(actionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))

        // 底部 TabBar（液态玻璃）：悬浮在内容之上，内容从玻璃底下透出
        tabBar = buildTabBar()
        root.addView(tabBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))

        setContentView(root)

        // 状态栏浅色已由主题处理
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = BG
        }
    }

    // ---------- 通用构造 ----------
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            this.gravity = gravity
            includeFontPadding = false
        }

    private fun rnd(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun chip(text: String, color: Int, on: Boolean, block: (View) -> Unit): TextView {
        val t = tv(text, 13f, if (on) 0xFFFFFFFF.toInt() else color, true, Gravity.CENTER)
        t.background = rnd(if (on) color else 0x22FFFFFF.toInt(), 15)
        val lto = LinearLayout.LayoutParams(dp(8), dp(30))
        lto.setMargins(0, 0, dp(8), 0)
        t.layoutParams = lto
        t.setPadding(dp(12), 0, dp(12), 0)
        t.setOnClickListener(block)
        press(t)
        return t
    }

    // ---------- X 极简风格通用组件 ----------
    private fun glyph(icon: String, tint: Int, sw: Float = 1.8f, size: Int = 20): GlyphView =
        GlyphView(this).apply { this.icon = icon; this.tint = tint; this.strokeW = sw }

    /** 小圆点（平台色 / 状态色） */
    private fun dot(color: Int, size: Int = 8): View = View(this).apply {
        background = rnd(color, size / 2)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun chevron(): GlyphView = glyph("chevron", 0xFFC7C7CC.toInt(), 2f, 16)

    /** 多选圆圈：选中时显示对勾 */
    private fun selCheck(sel: Boolean, color: Int): FrameLayout {
        val f = FrameLayout(this).apply {
            background = rnd(if (sel) color else 0xFFE5E5EA.toInt(), 12)
        }
        if (sel) f.addView(glyph("check", Color.WHITE, 2.2f, 13),
            FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        return f
    }

    /** 局部刷新选中圆圈（多选时只更新该圆点，不重建整页 → 列表不闪动、不回到置顶） */
    private fun applySelCheck(v: View, sel: Boolean, color: Int) {
        val f = v as? FrameLayout ?: return
        (f.background as? GradientDrawable)?.setColor(if (sel) color else 0xFFE5E5EA.toInt())
        f.removeAllViews()
        if (sel) f.addView(glyph("check", Color.WHITE, 2.2f, 13),
            FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
    }

    /** 当前多选模式的圆圈主题色 */
    private fun selectColor(): Int = when (selectKind) {
        "favorites" -> CORAL
        "history", "downloaded" -> BLUE
        else -> RED
    }

    // ---------- 底部 TabBar（真液态玻璃，对照 legado-with-MD3 AppNavigationBar 配方） ----------
    // legado：hazeEffect(style=HazeLegado.ultraThin) = 24dp 背景模糊 + 扁平 surface 色调（非渐变）
    //        容器色完全透明；指示器仅选中时弹簧浮现、未选中 scale=0 —— 不显示任何"椭圆灰点"
    private fun buildTabBar(): FrameLayout {
        val bar = FrameLayout(this)

        // 1) 真模糊玻璃层：背后内容缩小采样 → 拉伸 → RenderEffect 高斯模糊（≈24dp，同 legado haze）
        val glass = GlassBlurView(this)
        glassBlur = glass
        bar.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 2) 扁平玻璃色调（HazeTint = surface @ lightAlpha，恒透明，非渐变）：
        //    只做轻微提亮的"玻璃膜"，模糊透出的内容才是主角
        val veil = View(this).apply {
            background = GradientDrawable().apply { setColor(0x45F2F2F7.toInt()) }
        }
        bar.addView(veil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 3) 三个 Tab（Icon + 指示器 + Label）：与 legado 一致，无顶部分隔线、无多余描边
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        val tabs = listOf("解析" to BLUE, "收藏" to CORAL, "我的" to GREEN)
        tabPills.clear(); tabIcons.clear(); tabLabels.clear()
        tabs.forEachIndexed { i, (name, color) ->
            val item = FrameLayout(this).apply {
                setOnClickListener { if (!selectMode) switchTo(i) }
            }
            press(item)

            // 选中指示器（MD3 圆角方胶囊：仅选中时弹簧浮现，未选中完全隐藏）
            val pill = View(this).apply {
                background = rnd(0x00000000, 12)
                scaleX = 0f; scaleY = 0f
            }
            item.addView(pill, FrameLayout.LayoutParams(dp(54), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(13) })

            // 图标 + 文字列
            val icon = GlyphView(this).apply {
                this.icon = when (i) { 0 -> "home"; 1 -> "starline"; else -> "user" }
                tint = 0xFF8E8E93.toInt(); strokeW = 1.6f
            }
            val label = tv(name, 10.5f, 0xFF8E8E93.toInt(), false, Gravity.CENTER)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
            col.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) })
            item.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER))

            row.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
            tabPills.add(pill); tabIcons.add(icon); tabLabels.add(label)
        }
        bar.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        return bar
    }

    private fun renderTabBar() {
        val colors = listOf(BLUE, CORAL, GREEN)
        for (i in 0 until 3) {
            val on = currentTab == i && !selectMode
            val c = if (on) colors[i] else 0xFF8E8E93.toInt()
            tabIcons[i].tint = c
            tabLabels[i].setTextColor(c)
            // 图标：选中轻微放大（阻尼弹簧；M3 图标保持小幅度变化）
            if (on) Springy.scaleTo(tabIcons[i], 1.08f, 1.08f, damping = 0.62f, stiffness = 1000f)
            else Springy.scaleTo(tabIcons[i], 1f, 1f, damping = 0.8f, stiffness = 900f)
            // 指示器：仅选中时弹簧浮现（真阻尼：damping≈0.55 → 轻轻回弹一下再落定）；
            // 取消选中即缩回 0，绝不再留 0.45 灰点
            val pill = tabPills[i]
            (pill.background as GradientDrawable).setColor(
                if (on) (colors[i] and 0x00FFFFFF) or (0x4D shl 24)
                else 0x00000000)
            if (on) Springy.scaleTo(pill, 1f, 1f, damping = 0.55f, stiffness = 760f)
            else Springy.scaleTo(pill, 0f, 0f, damping = 0.85f, stiffness = 900f)
        }
    }

    // ---------- 多选操作栏 ----------
    private fun buildActionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(6).toFloat()
            setPadding(dp(12), 0, dp(12), 0)
        }
        bar.addView(GlyphView(this).apply {
            icon = "x"; tint = TXT; strokeW = 2f
            setOnClickListener { exitSelect() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(actions, LinearLayout.LayoutParams(0, dp(64), 1f))
        bar.tag = "actionBar"
        actions.tag = "actionActions"
        return bar
    }

    private fun renderActionBar() {
        actionBar.tag?.let {} // noop
        if (!selectMode) { actionBar.visibility = View.GONE; return }
        actionBar.visibility = View.VISIBLE
        val actions = actionBar.findViewWithTag<LinearLayout>("actionActions")
        actions.removeAllViews()
        // 标题
        actions.addView(tv(when (selectKind) {
            "history" -> "历史 · 已选 ${selected.size}"
            "favorites" -> "收藏 · 已选 ${selected.size}"
            "recycle" -> "回收站 · 已选 ${selected.size}"
            "downloaded" -> "已下载 · 已选 ${selected.size}"
            else -> "已选 ${selected.size}"
        }, 13f, TXT3, true, Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, dp(60), 1f))

        fun rem(key: String) = actions.findViewWithTag<LinearLayout>(key)
        // 操作项按模式排列
        fun app(label: String, color: Int, block: () -> Unit) {
            val t = tv(label, 12.5f, color, true, Gravity.CENTER)
            t.background = rnd(0xFFF2F2F7.toInt(), 12)
            t.setPadding(dp(12), dp(6), dp(12), dp(6))
            t.setOnClickListener { block() }
            actions.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            })
        }
        when (selectKind) {
            "history" -> {
                app("删除", RED) { multiDelete() }
                app("收藏", CORAL) { multiFavorite() }
                app("下载", BLUE) { multiDownload() }
                app("分享", GREEN) { multiShare() }
            }
            "favorites" -> {
                app("删除", RED) { multiUnfavorite() }
                app("移入分类", PURPLE) { multiMoveCategory() }
                app("下载", BLUE) { multiDownload() }
                app("分享", GREEN) { multiShare() }
            }
            "downloaded" -> {
                app("删除记录", RED) { multiDeleteDownloaded() }
                app("分享", GREEN) { multiShare() }
            }
            "recycle" -> {
                app("恢复", GREEN) { multiRestore() }
                app("彻底删除", RED) { multiPurge() }
            }
        }
    }

    private fun enterSelect(kind: String, id: Long) {
        selectMode = true; selectKind = kind; selected.clear(); selected.add(id)
        tabBar.visibility = View.GONE; renderActionBar(); renderCurrent()
    }
    private fun exitSelect() {
        selectMode = false; selected.clear(); selectKind = ""
        tabBar.visibility = View.VISIBLE
        actionBar.visibility = View.GONE
        renderTabBar(); renderCurrent()
    }
    private fun toggleSelect(id: Long) {
        // 只局部刷新：更新圆圈选中态 + 操作栏计数，不重建整页 → 列表不再闪动/回到置顶
        if (!selected.remove(id)) selected.add(id)
        selectChecks[id]?.let { applySelCheck(it, selected.contains(id), selectColor()) }
        renderActionBar()
    }

    // ---------- 页面切换 ----------
    private fun switchTo(i: Int) {
        currentTab = i
        pvMode = when (i) { 0 -> "home"; 1 -> "favorites"; else -> "mine" }
        if (pvMode == "home") showTabBar()
        renderTabBar(); renderCurrent()
    }

    private fun currentPage(): View = when (pvMode) {
        "favorites" -> buildFavPage()
        "mine" -> buildMinePage()
        "recycle" -> buildRecyclePage()
        "detail" -> buildDetailPage()
        else -> buildHomePage()
    }

    private fun renderCurrent() {
        val now = System.currentTimeMillis()
        lastRender = now
        selectChecks.clear()   // 页面重建后旧圆圈注册失效，由新构建的 cell 重新注册
        // 页面重建前释放旧页内视频播放器（全屏中的 vv 由 fsHost 单独持有，此处重建流程保证 fsHost == null）
        for (v in liveVideos) v.release()
        liveVideos.clear()
        content.removeAllViews()
        val page = currentPage()
        // 底部悬浮栏（TabBar / 多选操作栏）覆盖在内容之上：
        // 页面根部留出底部空间，让滚动内容能"透"到玻璃底下
        val overlay = if (tabBar.visibility == View.VISIBLE || actionBar.visibility == View.VISIBLE) dp(64) else 0
        if (overlay > 0) page.setPadding(0, 0, 0, overlay)
        content.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 页面切入动画：真实阻尼弹簧（semi-隐式欧拉积分），快而顺地"弹"进画面，
        // 取代固定时长的补间 —— 对应 legado 的 spring(stiffness, dampingRatio) 手感
        page.alpha = 0f
        page.translationY = dp(24).toFloat()
        val ty = SpringScaler({ page.translationY }, { v -> page.translationY = v })
        ty.stiffness = 640f; ty.dampingRatio = 0.9f; ty.to(0f)
        val al = SpringScaler({ page.alpha }, { v -> page.alpha = v })
        al.stiffness = 640f; al.dampingRatio = 0.95f; al.to(1f)
        // 把"背后内容"绑定给液态玻璃层：页面滚动时实时刷新模糊
        if (::glassBlur.isInitialized) {
            glassBlur.setTarget(content)
            content.post { hookScrollToGlass(page) }
        }
    }

    /** 页面里的 ScrollView 滚动时，刷新底部玻璃层的模糊采样（tag 防止重复挂钩） */
    private fun hookScrollToGlass(root: View?) {
        root ?: return
        if (root is ScrollView || root is HorizontalScrollView) {
            if (root.tag != "glassHooked") {
                root.tag = "glassHooked"
                root.setOnScrollChangeListener { _, _, _, _, _ ->
                    // postInvalidateOnAnimation：与垂直同步对齐，逐帧只重绘一次，
                    // 玻璃采样随滚动实时刷新且不造成掉帧（滑动顺畅的关键）
                    if (::glassBlur.isInitialized) glassBlur.postInvalidateOnAnimation()
                }
            }
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) hookScrollToGlass(root.getChildAt(i))
    }

    /** 通知栏 / 外部 Intent 跳转到指定媒体详情 */
    private fun handleExternalIntent(intent: Intent?) {
        if (intent?.action == "VIEW_MEDIA") {
            val id = intent.getLongExtra("media_id", -1)
            if (id > 0) {
                val b = DB.historyById(id)
                if (b != null) {
                    currentTab = 0
                    openDetail(b)
                }
            }
        }
    }

    private fun scrolled(inner: () -> LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(inner(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // =====================================================================
    // 首页
    // =====================================================================
    private var detailBean: MediaBean? = null
    private var homeResult: MediaBean? = null
    private var parseStatusText = "粘贴分享内容，自动识别平台并解析"

    private fun buildHomePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 输入区：白色圆角卡（iOS 极简卡片式框体，棱角圆润但不走纯圆/纯矩形）
        val inputCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rnd(CARD, 14); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val input = EditText(this).apply {
            hint = "粘贴链接或分享口令…"
            setTextColor(TXT); setHintTextColor(0xFFC7C7CC.toInt())
            background = null; textSize = 15f
            gravity = Gravity.TOP or Gravity.START; minHeight = dp(88)
            setPadding(0, dp(8), 0, dp(6)); setSingleLine(false)
            setTextIsSelectable(true)
            setSelectAllOnFocus(false)
        }
        inputCard.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        inputCard.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        val pasteBtn = TextView(this).apply {
            text = "粘贴"; textSize = 14f; setTextColor(BLUE); setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(10), dp(10), dp(10))
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val t = clip.getItemAt(0).coerceToText(this@MainActivity).toString()
                    if (t.isNotBlank()) { input.setText(t); statusText = "已粘贴，点击解析" }
                    else toast("剪贴板为空")
                } else toast("剪贴板为空")
            }
        }
        val clearBtn = TextView(this).apply {
            text = "清空"; textSize = 14f; setTextColor(TXT3)
            setPadding(dp(6), dp(10), dp(10), dp(10))
            setOnClickListener { input.setText("") }
        }
        val parseBtn = TextView(this).apply {
            val busy = parseBusy
            text = if (busy) "解析中…" else "解析"
            textSize = 15f; setTextColor(if (busy) 0xFFCFD0DA.toInt() else Color.WHITE); setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rnd(if (busy) 0xFF5A5A66.toInt() else BLUE, 13)
            isEnabled = !busy
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("请先粘贴分享内容"); return@setOnClickListener }
                hideKeyboard()
                // 新解析开始 → 清空旧结果，让界面实时展示新内容
                homeResult = null
                parseCanceled = false
                parseBusy = true
                statusText = "正在解析，请稍候…"
                statusLoading = true
                parseStartTs = System.currentTimeMillis()
                updateParseButton()
                startService()
                MediaService.parse(this@MainActivity, text)
                renderCurrent()
            }
        }
        press(parseBtn)
        parseBtnRef = parseBtn

        // 停止按钮（解析进行中才显示，点击强行停止）
        val stopBtn = GlyphView(this).apply {
            icon = "x"
            tint = RED
            strokeW = 2f
            background = rnd(0xFFF2F2F7.toInt(), 16)
            visibility = if (parseBusy) View.VISIBLE else View.GONE
            setOnClickListener { stopParse() }
        }
        stopBtnRef = stopBtn
        btns.addView(pasteBtn)
        btns.addView(clearBtn)
        btns.addView(parseBtn, LinearLayout.LayoutParams(dp(76), dp(38)).apply { leftMargin = dp(4); topMargin = dp(6); bottomMargin = dp(4) })
        btns.addView(stopBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(6) })
        inputCard.addView(btns)
        col.addView(inputCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // 状态行（简洁，无卡片）
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val spin = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)) }
        val statusLabel = tv(parseStatusText, 12.5f, 0xFF8E8E93.toInt(), false)
        statusRow.addView(spin)
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        statusRow.tag = "statusCard"; spin.tag = "statusSpin"; statusLabel.tag = "statusLabel"
        applyStatus(statusRow, spin, statusLabel)
        col.addView(statusRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); leftMargin = dp(4) })

        // 首页内联解析结果（实时预览与操作）
        homeResult?.let { b ->
            col.addView(buildHomeResultCard(b), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        // 历史记录：筛选卡 + 分段栏 + 分组列表（与上部内容收紧间距）
        col.addView(homeHistorySection(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // 支持平台（纯文字，无框体）
        col.addView(tv("支持 抖音 / 快手 / 哔哩哔哩 / TikTok / 推特（X）", 12f, 0xFFAEAEB2.toInt()),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        col.addView(TextView(this).apply { text = " "; textSize = 4f })
        return scrolled { col }
    }

    /** 首页内联结果卡：封面信息 + 视频实时预览 + 下载/音频操作（统一白卡框体） */
    private fun buildHomeResultCard(b: MediaBean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openDetail(b) }
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rnd(0xFFF2F2F7.toInt(), 10) }
        ImageLoader.load(b.cover, cover)
        top.addView(cover, LinearLayout.LayoutParams(dp(58), dp(58)))
        val tCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pc = platformColor(b.platform)
        tCol.addView(tv(b.title.ifEmpty { "未命名作品" }.let { if (it.length > 30) it.take(30) + "…" else it }, 14.5f, TXT, true))
        tCol.addView(tv("${platformName(b.platform)} · ${b.author.ifEmpty { "未知作者" }}", 12f, pc),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        top.addView(tCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        top.addView(GlyphView(this).apply { icon = "chevron"; tint = 0xFFC7C7CC.toInt(); strokeW = 2f }, LinearLayout.LayoutParams(dp(18), dp(18)))
        card.addView(top)

        val vUrl = b.firstVideoUrl()
        if (vUrl.isNotEmpty()) {
            card.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val vv = HeaderVideoView(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                onError = { true }
                onRetry = { toast("链接可能已过期，可回到输入框重新解析") }
                onVideoSize = { w, h -> fitVideoBox(this, w, h) }
                onDownload = { downloadFirst(b) }
                onToggleFullscreen = { enterOrExitFullscreen(this) }
            }
            box.addView(vv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
            liveVideos.add(vv)
            card.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            vv.load(videoPreviewUrls(b))

            val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val dlV = opBtn("download", "下载视频", BLUE) { downloadFirst(b) }
            ops.addView(dlV, LinearLayout.LayoutParams(0, dp(44), 1.2f))
            if (b.audioUrls.length() > 0) {
                val dlA = opBtn("music", "下载音频", PURPLE) { downloadAudio(b) }
                ops.addView(dlA, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })
            }
            card.addView(ops, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        // 音频试听区
        if (b.audioUrls.length() > 0) {
            card.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) })
            audiosOf(b).forEachIndexed { i, u ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { openDetail(b) }
                }
                row.addView(GlyphView(this).apply { icon = "music"; tint = PURPLE; strokeW = 1.8f }, LinearLayout.LayoutParams(dp(20), dp(20)))
                row.addView(tv("原声音乐", 13.5f, TXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                val listen = tv(if (playingUrl == u && audioPlayer?.isPlaying == true) "停止" else "试听", 13f, PURPLE, true, Gravity.CENTER).apply {
                    setOnClickListener { toggleAudio(u, this) }
                }
                row.addView(listen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(8) })
                card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
                if (i == 0) return@forEachIndexed
            }
        }
        return card
    }

    private fun opBtn(icon: String, label: String, color: Int, block: () -> Unit): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rnd(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)), 12)
            setOnClickListener { block() }
        }
        press(l)
        l.addView(GlyphView(this).apply { this.icon = icon; tint = color; strokeW = 1.8f }, LinearLayout.LayoutParams(dp(17), dp(17)))
        l.addView(tv(label, 13.5f, color, true, Gravity.CENTER), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        return l
    }

    private fun audiosOf(b: MediaBean): List<String> {
        val out = ArrayList<String>()
        if (b.audioUrls.length() > 0) {
            val o = b.audioUrls.optJSONObject(0)
            val u = o?.optString("url", "") ?: ""
            if (u.isNotEmpty()) out.add(u)
        }
        return out
    }

    // ---------- 视频等比摆放 / 全屏 ----------
    private var fsHost: HeaderVideoView? = null
    private var fsSavedParent: ViewGroup? = null
    private var fsSavedLp: ViewGroup.LayoutParams? = null
    private var fsCloseBtn: View? = null
    private var fsControlsVisible = true
    private var fsPrevTabVis = View.GONE
    private var fsPrevActionVis = View.GONE
    /** 当前页面内全部视频预览实例：页面重建 / Activity 销毁前统一 release，避免 MediaPlayer 资源残留 */
    private val liveVideos = ArrayList<HeaderVideoView>()

    /** 按视频原始比例摆放预览框：过长/过宽缩成迷你版（等比不拉伸），桌面宽幅居中。
  *  若播放器此刻在全屏层内，则等比铺满屏幕并居中。 */
    private fun fitVideoBox(v: HeaderVideoView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        if (v.layoutParams is FrameLayout.LayoutParams) {
            scaleVideoToScreen(v)
            return
        }
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
        val maxW = resources.displayMetrics.widthPixels - dp(40)
        val maxH = dp(300)   // 迷你版上限：过高视频压缩为竖版小卡
        val scale = minOf(maxW.toFloat() / vw, maxH.toFloat() / vh)
        lp.width = (vw * scale).toInt().coerceAtLeast(dp(80))
        lp.height = (vh * scale).toInt()
        lp.gravity = Gravity.CENTER_HORIZONTAL
        v.layoutParams = lp
    }

    /** 点击视频画面：迷你态 → 进入全屏；全屏态 → 显示/隐藏控件（不再直接退出全屏） */
    private fun enterOrExitFullscreen(v: HeaderVideoView) {
        if (fsHost === v) toggleFsControls() else enterFullscreen(v)
    }

    private fun setFsControls(on: Boolean) {
        val v = fsHost ?: return
        fsControlsVisible = on
        v.setControlsVisible(on)
        fsCloseBtn?.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** 全屏播放时点击画面：切换控件显示/隐藏（iOS 极简——点一下交互控件浮现，再点收起来） */
    private fun toggleFsControls() { setFsControls(!fsControlsVisible) }

    /** 进入全屏：把播放器视图移入全屏层，等比缩放居中，隐藏系统栏与玻璃栏 */
    private fun enterFullscreen(v: HeaderVideoView) {
        if (fsHost != null && fsHost !== v) exitFullscreen()
        fsHost = v
        fsSavedParent = v.parent as? ViewGroup
        fsSavedLp = v.layoutParams
        fsPrevTabVis = tabBar.visibility
        fsPrevActionVis = actionBar.visibility
        (v.parent as? ViewGroup)?.removeView(v)
        fsLayer.removeAllViews()
        v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        fsLayer.addView(v)
        // 全屏右上角退出按钮（iOS 极简：深蓝灰半透明小圆钮 + 细白叉）
        val close = GlyphView(this).apply {
            icon = "x"; tint = 0xFFFFFFFF.toInt(); strokeW = 1.8f
            background = rnd(0x7A111318.toInt(), 16)
            setOnClickListener { if (fsHost != null) exitFullscreen() }
        }
        fsCloseBtn = close
        fsLayer.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(14), dp(12), 0) })
        // 点击画面只切换控件显隐；退出全屏仅通过右上角按钮或系统返回键
        fsLayer.setOnClickListener { toggleFsControls() }
        fsLayer.visibility = View.VISIBLE
        tabBar.visibility = View.GONE
        actionBar.visibility = View.GONE
        addImmersive()
        setFsControls(true)
        // 等比缩放适配屏幕，四周留黑边
        fsLayer.post { scaleVideoToScreen(v) }
    }

    private fun scaleVideoToScreen(v: HeaderVideoView) {
        val vw = v.videoW; val vh = v.videoH
        if (vw <= 0 || vh <= 0) return
        val w = fsLayer.width; val h = fsLayer.height
        if (w <= 0 || h <= 0) {
            // 全屏层尚未完成首次布局：延后重试，待宽度/高度可用再等比缩放
            fsLayer.post { if (fsHost === v) scaleVideoToScreen(v) }
            return
        }
        val scale = minOf(w.toFloat() / vw, h.toFloat() / vh)
        v.layoutParams = FrameLayout.LayoutParams((vw * scale).toInt(), (vh * scale).toInt(), Gravity.CENTER)
        v.requestLayout()
    }

    /** 退出全屏：把播放器移回原位置并恢复等比尺寸，同时恢复之前的底栏状态 */
    private fun exitFullscreen() {
        val v = fsHost ?: return
        fsLayer.removeAllViews()
        fsLayer.visibility = View.GONE
        fsLayer.setOnClickListener(null)
        removeImmersive()
        // 按进入全屏前的状态恢复：详情页下回到详情（玻璃栏保持隐藏），其他场景按原样还原
        tabBar.visibility = fsPrevTabVis
        actionBar.visibility = fsPrevActionVis
        val parent = fsSavedParent
        if (parent != null) {
            v.layoutParams = fsSavedLp
            parent.addView(v)
            fitVideoBox(v, v.videoW, v.videoH)
        }
        fsHost = null; fsSavedParent = null; fsSavedLp = null
        fsCloseBtn = null
        v.setControlsVisible(true)
    }

    // ---------- 音频试听 / 下载 ----------
    private var audioPlayer: android.media.MediaPlayer? = null
    private var playingUrl = ""

    private fun downloadAudio(b: MediaBean) {
        val u = b.audioUrls.optJSONObject(0)?.optString("url", "") ?: ""
        if (u.isEmpty()) { toast("无可用音频"); return }
        startDownload(u, "${platformName(b.platform)}_${b.title.let { if (it.length > 14) it.take(14) else it }}.mp3", "audio", b.id)
    }

    private fun toggleAudio(u: String, label: TextView) {
        val player = audioPlayer
        if (playingUrl == u && player?.isPlaying == true) {
            player.pause(); playingUrl = ""; label.text = "试听"
            return
        }
        runCatching { player?.release() }
        audioPlayer = android.media.MediaPlayer().apply {
            try {
                setDataSource(u)
                setOnPreparedListener { it.start(); playingUrl = u; label.text = "停止" }
                setOnCompletionListener { playingUrl = ""; label.text = "试听" }
                setOnErrorListener { _, _, _ -> playingUrl = ""; label.text = "试听"; true }
                prepareAsync()
            } catch (e: Exception) { toast("音频暂无法播放") }
        }
    }

    private var statusText = ""
    private var statusLoading = false
    private var parseBusy = false
    private var parseStartTs = 0L
    // 解析状态：是否用户主动停止（停止后忽略迟到的服务广播结果）
    private var parseCanceled = false
    // 解析按钮 / 停止按钮引用：解析完成时直接原地更新，不依赖页面重建
    private var parseBtnRef: TextView? = null
    private var stopBtnRef: View? = null

    /** 原地更新解析按钮与停止按钮的状态（loading 时的置灰/禁用与恢复） */
    private fun updateParseButton() {
        val busy = parseBusy
        parseBtnRef?.apply {
            text = if (busy) "解析中…" else "解析"
            setTextColor(if (busy) 0xFFCFD0DA.toInt() else Color.WHITE)
            background = rnd(if (busy) 0xFF5A5A66.toInt() else BLUE, 13)
            isEnabled = !busy
        }
        stopBtnRef?.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** 强行停止解析：即刻复位界面 + 通知服务/底层取消进行中的重试 */
    private fun stopParse() {
        parseCanceled = true
        parseBusy = false
        statusLoading = false
        parseStartTs = 0
        statusText = "已停止解析"
        try {
            sendBroadcast(Intent(this, MediaService::class.java).setAction(MediaService.ACTION_PARSE_CANCEL))
        } catch (_: Exception) {}
        updateParseButton()
        renderCurrent()
    }
    private fun applyStatus(card: LinearLayout, spin: ProgressBar, label: TextView) {
        // 兜底：若超过 95s 仍未收到解析结果（服务被系统杀死等极端情况），不再显示"正在解析"
        if (statusLoading && parseStartTs > 0 && System.currentTimeMillis() - parseStartTs > 95_000) {
            statusLoading = false
            parseBusy = false
            statusText = "解析超时，请检查网络后重试"
            updateParseButton()
        }
        if (statusLoading) spin.visibility = View.VISIBLE else spin.visibility = View.GONE
        label.text = if (statusLoading) "正在解析，请稍候…" else statusText
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(content.windowToken, 0)
    }

    // ---------- 解析完成 ----------
    private fun onParseDone(ok: Boolean, hid: Long, err: String) {
        // 用户已手动停止：忽略迟到的结果，保持"已停止解析"状态
        if (parseCanceled) return
        statusLoading = false
        parseBusy = false
        parseStartTs = 0L
        if (ok && hid > 0) {
            statusText = "解析成功，可在下方直接预览与下载"
            detailBean = DB.historyById(hid)
            homeResult = detailBean
            pvMode = "home"
            currentTab = 0
            renderTabBar()
            renderCurrent()
        } else {
            statusText = "解析失败：${err.ifBlank { "未知错误" }}"
            renderCurrent()
        }
    }

    private fun startService() {
        try {
            val i = Intent(this, MediaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i)
            else startService(i)
        } catch (_: Exception) {}
    }

    // =====================================================================
    // 历史记录（合并进首页：解析成功后即时出现在输入区下方）
    // =====================================================================
    /** 首页内嵌历史记录区：标题 + 分段筛选（全部/下载中/已下载）+ 分组列表 */
    private fun homeHistorySection(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.tag = "historySection"

        // 筛选卡：平台滑动条 + 时间筛选（统一白色圆角卡，风格与分段栏一致）
        wrap.addView(buildFilterCard(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var segRef: FrameLayout? = null
        segRef = segment(listOf("全部", "下载中", "已下载"), historySegment) { sel ->
            historySegment = sel
            // 稍等滑块动画播完再刷新列表，视觉连贯
            segRef?.postDelayed({ renderCurrent() }, 240)
        }
        wrap.addView(segRef!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        wrap.addView(buildHistoryContent(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return wrap
    }

    // =====================================================================
    // tab1 筛选：平台（左右滑动） + 时间（近一天 / 近7天 / 全部 / 自定义）
    // =====================================================================

    /** 平台中文名 → 平台 key（与解析结果 platform 字段对应） */
    private fun platformKeyOf(name: String): String = when (name) {
        "抖音" -> "douyin"
        "快手" -> "kuaishou"
        "B站" -> "bilibili"
        "推特" -> "twitter"
        "TikTok" -> "tiktok"
        else -> "douyin"
    }

    /** 筛选卡：两行（平台 / 时间），统一胶囊控件样式 */
    private fun buildFilterCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(10), dp(7), dp(10), dp(8))
        }

        // 行1：平台（可左右滑动）
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(tv("平台", 12f, TXT3, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("全部", "抖音", "快手", "B站", "推特", "TikTok").forEach { name ->
            chips.addView(filterChip(name, "platform", name == currentPlatform) {
                if (currentPlatform != name) { currentPlatform = name; renderCurrent() }
            })
        }
        hsv.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        row1.addView(hsv, LinearLayout.LayoutParams(0, dp(28), 1f).apply { leftMargin = dp(8) })
        card.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(7) })

        // 行2：时间（近一天 / 近7天 / 全部 / 自定义）+ 清空历史
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(tv("时间", 12f, TXT3, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("近一天", "近7天", "全部", "自定义").forEachIndexed { i, name ->
            timeRow.addView(filterChip(name, "time", name == timeEntry) { onTimeFilterPick(i) })
        }
        row2.addView(timeRow, LinearLayout.LayoutParams(0, dp(28), 1f).apply { leftMargin = dp(8) })
        if (!selectMode) {
            val clearT = TextView(this).apply {
                text = "清空"
                textSize = 11.5f
                setTextColor(if (DB.allHistory().isEmpty()) 0xFFC7C7CC.toInt() else 0xFF8E8E93.toInt())
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), 0, dp(2), 0)
                setOnClickListener { confirmClearHistory() }
            }
            press(clearT)
            row2.addView(clearT, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        }
        card.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        return card
    }

    /** 筛选胶囊（与收藏分类 chip 同规格，选中态统一 iOS 蓝） */
    private fun filterChip(text: String, group: String, on: Boolean, block: () -> Unit): TextView {
        val t = tv(text, 11.5f, if (on) Color.WHITE else TXT3, on, Gravity.CENTER)
        t.tag = "$group|$text"
        t.background = rnd(if (on) BLUE else 0xFFF2F2F7.toInt(), 11)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        lp.setMargins(0, 0, dp(6), 0)
        t.layoutParams = lp
        t.setPadding(dp(12), 0, dp(12), 0)
        t.setOnClickListener { block() }
        press(t)
        return t
    }

    private fun onTimeFilterPick(idx: Int) {
        when (idx) {
            0 -> { timeEntry = "近一天"; timeFrom = System.currentTimeMillis() - 24 * 3600 * 1000; renderCurrent() }
            1 -> { timeEntry = "近7天"; timeFrom = System.currentTimeMillis() - 7 * 24 * 3600 * 1000; renderCurrent() }
            2 -> { timeEntry = "全部"; timeFrom = 0L; renderCurrent() }
            3 -> showCustomDatePicker()
        }
    }

    /** 自定义时间：选择某一天作为起始（当天 00:00 起） */
    private fun showCustomDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            timeFrom = c.timeInMillis
            timeEntry = "自定义"
            timeLabel = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(c.timeInMillis))
            renderCurrent()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun confirmClearHistory() {
        if (DB.allHistory().isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空历史记录")
            .setMessage("将把所有历史记录移入回收站，可随时恢复。")
            .setPositiveButton("清空") { _, _ ->
                DB.allHistory().forEach { DB.softDeleteHistory(it.id) }
                renderCurrent()
            }.setNegativeButton("取消", null).show()
    }

    /** iOS 风格分段控制器：白色滑块随点击滑动 + 文字亮度/字重切换动画 */
    private fun segment(names: List<String>, index: Int, onChange: (Int) -> Unit): FrameLayout {
        val pad = dp(3)
        val cellH = dp(30)
        val wrap = FrameLayout(this).apply {
            background = rnd(0xFFE9E9EC.toInt(), 10)
            setPadding(pad, pad, pad, pad)
        }
        val pill = View(this).apply {
            background = rnd(CARD, 9)
            // 注意：给滑块加 elevation 会导致它在 Android 上绘制在文字上层，
            // 盖住选中文字（历史页"切换后显示白色看不到字"的根因）——这里不加。
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, name ->
            val t = tv(name, 13f, if (i == index) TXT else TXT3, i == index, Gravity.CENTER)
            t.tag = "segTxt$i"
            row.addView(t, LinearLayout.LayoutParams(0, cellH, 1f))
        }
        wrap.addView(pill)
        // 文字行 elevation 略高于滑块，保证文字永远可见
        row.elevation = dp(1).toFloat()
        wrap.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH))
        // 布局完成后把滑块定位到当前选中项（首次直接定位，无动画）
        wrap.post {
            val w = wrap.width / names.size
            pill.layoutParams = FrameLayout.LayoutParams(w, cellH)
            pill.translationX = (w * index).toFloat()
        }
        fun moveTo(sel: Int) {
            val w = wrap.width / names.size
            pill.animate().translationX((w * sel).toFloat())
                .setDuration(220).setInterpolator(DecelerateInterpolator(2.2f)).start()
            for (i in names.indices) {
                val t = wrap.findViewWithTag<TextView>("segTxt$i") ?: continue
                val on = i == sel
                t.setTextColor(if (on) TXT else TXT3)
                t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        names.forEachIndexed { i, _ ->
            row.getChildAt(i).setOnClickListener {
                if (i != index) {
                    moveTo(i)
                    onChange(i)
                }
            }
        }
        return wrap
    }

    /** 历史分组列表（非滚动的内嵌版本，供首页使用） */
    private fun buildHistoryContent(): LinearLayout {
        val dots = statusesOf()
        val all = DB.allHistory()

        // 按 history 聚合下载任务：完成/未完成分离。
        // 已下载视频（任意一个任务 DL_DONE）不再出现在"下载中"列表。
        val dl = DB.allDownloads()
        val tasksByHistory = dl.groupBy { it.historyId }
        fun hasDone(id: Long) = tasksByHistory[id].orEmpty().any { it.status == DB.DL_DONE }
        fun hasPending(id: Long) = tasksByHistory[id].orEmpty().any { it.status != DB.DL_DONE }

        val beans: List<MediaBean> = all.filter { h ->
            // 平台筛选
            if (currentPlatform != "全部" && h.platform != platformKeyOf(currentPlatform)) return@filter false
            // 时间筛选（parsed_at 以秒存储）
            if (timeFrom > 0 && h.parsedAt * 1000 < timeFrom) return@filter false
            when (historySegment) {
                1 -> hasPending(h.id) && !hasDone(h.id)  // 下载中：有未完成任务且没有任何已完成任务
                2 -> hasDone(h.id)                        // 已下载：至少一个完成任务
                else -> true
            }
        }

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (beans.isEmpty()) {
            val emptyMsg = when {
                historySegment == 1 -> "没有进行中的下载，下载任务完成前会显示在这里"
                historySegment == 2 -> "还没有下载到本地的内容"
                currentPlatform != "全部" || timeEntry != "全部" -> "当前筛选条件下暂无记录"
                else -> "暂无历史记录，解析成功后会自动出现在这里"
            }
            wrap.addView(tv(emptyMsg, 12.5f, TXT3, false, Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(26); bottomMargin = dp(26)
                })
            wrap.setBackgroundColor(CARD)
            return wrap
        }

        // 按平台分组
        val grouped = LinkedHashMap<String, MutableList<MediaBean>>()
        for (b in beans) grouped.getOrPut(b.platform) { ArrayList() }.add(b)

        for ((platform, items) in grouped) {
            val group = historyGroup(platform, items, dots,
                isPending = { id -> hasPending(id) && !hasDone(id) },
                isDone = { id -> hasDone(id) })
            wrap.addView(group,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            staggerIn(group)
        }
        return wrap
    }

    /** 平台分组：一个圆角容器 + 细分割线（inset group 列表风格） */
    private fun historyGroup(platform: String, items: List<MediaBean>,
                             dots: HashMap<Long, DownloadBean>,
                             isPending: (Long) -> Boolean, isDone: (Long) -> Boolean): LinearLayout {
        val pc = platformColor(platform)
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(14), 0, dp(14), dp(2))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        header.addView(dot(pc, 8))
        header.addView(tv(platformName(platform), 12.5f, TXT, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        header.addView(tv("${items.size} 条", 11.5f, TXT3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        header.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        group.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        group.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        items.forEachIndexed { i, b ->
            group.addView(historyCell(b, dots[b.id],
                isPending(b.id) || isDone(b.id), isDone(b.id)))
            if (i != items.lastIndex)
                group.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(62) })
        }
        return group
    }

    /** 每个 history 当前下载状态 */
    private fun statusesOf(): HashMap<Long, DownloadBean> {
        val m = HashMap<Long, DownloadBean>()
        for (d in DB.allDownloads()) m[d.historyId] = d
        return m
    }

    /** 收藏条目下载状态视图（进度 / 暂停 / 失败 / 等待 / 已下载），供收藏 cell 与广播局部刷新共用 */
    private fun buildDlInfoInner(dl: DownloadBean): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        when (dl.status) {
            DB.DL_DOWNLOADING -> {
                val p = if (dl.totalSize > 0) ((dl.downloadedSize * 100) / dl.totalSize).toInt().coerceIn(0, 100) else 0
                box.addView(tv("下载中 $p%", 11f, BLUE, true))
                box.addView(progressBar(p), LinearLayout.LayoutParams(0, dp(6), 1f).apply { leftMargin = dp(10) })
            }
            DB.DL_PAUSED -> box.addView(tv("已暂停 · 进入详情继续", 11f, ORANGE, true))
            DB.DL_FAILED -> box.addView(tv("下载失败 · 进入详情重试", 11f, RED, true))
            DB.DL_WAITING -> box.addView(tv("等待下载", 11f, TXT3, true))
            DB.DL_DONE -> box.addView(tv("已下载到本地", 11f, GREEN, true))
        }
        return box
    }

    /** 按 tag 在视图树中查找（收藏页下载广播局部刷新用） */
    private fun findViewByTag(root: View?, tag: String): View? {
        root ?: return null
        if (root.tag == tag) return root
        if (root is ViewGroup)
            for (i in 0 until root.childCount) {
                findViewByTag(root.getChildAt(i), tag)?.let { return it }
            }
        return null
    }

    /** 收藏页：仅更新某条收藏的下载状态视图（不重建整页，避免闪动回顶） */
    private fun updateFavDlCell(hid: Long) {
        val dl = statusesOf()[hid] ?: return
        val info = findViewByTag(content, "dlInfo:$hid") as? LinearLayout ?: return
        info.removeAllViews()
        info.addView(buildDlInfoInner(dl))
    }

    private fun platformName(p: String): String = when (p) {
        "douyin" -> "抖音"; "kuaishou" -> "快手"; "twitter" -> "推特/X"; "bilibili" -> "哔哩哔哩"; "tiktok" -> "TikTok"
        else -> p.ifEmpty { "其他" }
    }

    private fun historyCell(b: MediaBean, dl: DownloadBean?, withDlInfo: Boolean, done: Boolean): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cell.setPadding(0, dp(10), 0, dp(10))
        cell.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        press(cell)  // 阻尼按压缩放（真弹簧）

        // 多选圆圈
        if (selectMode) {
            val ck = selCheck(selected.contains(b.id), BLUE)
            selectChecks[b.id] = ck
            cell.addView(ck, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        }

        // 封面
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rnd(0xFFF2F2F7.toInt(), 10)
        }
        ImageLoader.load(b.cover, cover)
        cell.addView(cover, LinearLayout.LayoutParams(dp(52), dp(52)))

        // 文字
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(tv(b.title.ifEmpty { "未命名作品" }.let { if (it.length > 24) it.take(24) + "…" else it },
            14.5f, TXT, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textCol.addView(tv(buildHistorySub(b, done), 11.5f, TXT3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })

        // 下载进度/状态
        if (dl != null && dl.status == DB.DL_DOWNLOADING) {
            val p = if (dl.totalSize > 0) ((dl.downloadedSize * 100) / dl.totalSize).toInt() else 0
            textCol.addView(progressBar(p), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
        } else if (dl != null && dl.status == DB.DL_PAUSED) {
            textCol.addView(tv("已暂停 · 点击进入详情继续下载", 11f, ORANGE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        } else if (dl != null && dl.status == DB.DL_FAILED) {
            textCol.addView(tv("下载失败 · 点击进入详情重试", 11f, RED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        } else if (dl != null && dl.status == DB.DL_WAITING) {
            textCol.addView(tv("等待下载 · 点击进入详情", 11f, TXT3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        cell.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })

        // 右侧：收藏星标 / 状态徽章
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        if (b.isFavorite)
            right.addView(glyph("star", GOLD, 1.6f, 14), LinearLayout.LayoutParams(dp(14), dp(14)))
        when {
            dl != null && dl.status == DB.DL_PAUSED ->
                right.addView(badge("已暂停", ORANGE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            done ->
                right.addView(badge("已下载", GREEN), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            dl != null && dl.status == DB.DL_DOWNLOADING ->
                right.addView(badge("下载中", BLUE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            dl != null && dl.status == DB.DL_FAILED ->
                right.addView(badge("下载失败", RED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        cell.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        cell.addView(chevron(), LinearLayout.LayoutParams(dp(15), dp(15)).apply { leftMargin = dp(4) })

        // 点击永远进入详情页：下载状态（暂停/继续/重试）统一在详情页里操作，避免列表卡在"下载中"
        cell.setOnClickListener {
            if (selectMode) toggleSelect(b.id) else openDetail(b)
        }
        cell.setOnLongClickListener {
            enterSelect(if (done || withDlInfo) "downloaded" else "history", b.id); true
        }
        return cell
    }

    private fun buildHistorySub(b: MediaBean, done: Boolean): String {
        val s = StringBuilder()
        s.append(platformName(b.platform))
        if (b.author.isNotEmpty()) s.append(" · ").append(b.author)
        s.append(" · ").append(fmtTime(b.parsedAt))
        if (done) s.append(" · 已下载")
        return s.toString()
    }

    private fun badge(text: String, color: Int): TextView =
        tv(text, 10f, Color.WHITE, true, Gravity.CENTER).apply {
            background = rnd(color, 8); setPadding(dp(6), dp(2), dp(6), dp(2))
        }

    private fun progressBar(p: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rnd(0xFFF2F2F7.toInt(), 3)
        val fill = LinearLayout(this@MainActivity).apply { background = rnd(BLUE, 3) }
        addView(fill, LinearLayout.LayoutParams(0, dp(6), p.toFloat().coerceIn(0f, 100f)))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(6), (100 - p).toFloat().coerceIn(0f, 100f)))
    }

    private fun fmtTime(sec: Long): String {
        if (sec <= 0) return "未知时间"
        val cal = Calendar.getInstance()
        val t = cal.apply { timeInMillis = sec * 1000 }
        val now = Calendar.getInstance()
        return when {
            t.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                t.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ->
                "今天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(t.time)
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(t.time)
        }
    }

    // ---------- iOS 风格 ActionSheet（底部弹层） ----------
    private var sheetShowing = false
    private var sheetToken = 0L
    private var sheetHidTabBar = false   // 弹出时隐藏底部玻璃栏，让面板覆盖其上（iOS 风格）

    /** 底部弹出选择面板：标题 + 多项列表 + 取消。onPick(index) 后自动收起 */
    private fun showActionSheet(title: String?, items: List<Pair<String, Int>>, onPick: (Int) -> Unit) {
        // 立即清空旧弹层：避免上一个"收起动画"的 withEndAction 把新弹层一并移除，
        // 这正是长按分类后"删除/重命名"等面板点一下就消失/失效的根因
        dismissSheet()
        sheetShowing = true
        val myToken = ++sheetToken
        sheetLayer.removeAllViews()
        sheetLayer.alpha = 1f
        sheetLayer.visibility = View.VISIBLE

        // 面板要覆盖在底部玻璃 TabBar 之上（tabBar 在 root 中位于 body 之后，z 序更高）
        sheetHidTabBar = tabBar.visibility == View.VISIBLE
        if (sheetHidTabBar) tabBar.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(0x60000000)
            setOnClickListener { dismissSheet() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 16)
        }
        if (title != null) {
            sheet.addView(tv(title, 12f, 0xFFAEAEB2.toInt(), false, Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(16); bottomMargin = dp(12) })
        }
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rnd(CARD, 14) }
        items.forEachIndexed { i, (label, color) ->
            val h = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setOnClickListener {
                    onPick(i)
                    // 回调里可能又弹出了下一层（如删除确认）；仅当当前层仍是最新一层时才收起
                    if (sheetShowing && sheetToken == myToken) dismissSheet()
                }
            }
            press(h)
            h.addView(tv(label, 17f, color, color != TXT, Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 1f))
            group.addView(h)
            if (i != items.lastIndex)
                group.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { leftMargin = dp(16); rightMargin = dp(16) })
        }
        sheet.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(12); rightMargin = dp(12) })

        val cancel = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rnd(CARD, 14)
            setOnClickListener { dismissSheet() }
        }
        press(cancel)
        cancel.addView(tv("取消", 17f, TXT, true, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 1f))
        sheet.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8); leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(14) })

        sheetLayer.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(12), 0, dp(12), dp(12))
        sheetLayer.addView(sheet, lp)

        // 遮罩淡入 + 面板上滑
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        sheet.translationY = dp(320).toFloat()
        sheet.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2.6f)).start()
    }

    /** 收起 ActionSheet（立即移除，避免动画竞争） */
    private fun dismissSheet() {
        if (!sheetShowing) return
        sheetShowing = false
        sheetToken++   // 使旧层捕获的 token 全部失效
        if (sheetHidTabBar) {
            sheetHidTabBar = false
            if (!selectMode) tabBar.visibility = View.VISIBLE
        }
        sheetLayer.animate().cancel()
        sheetLayer.alpha = 1f
        sheetLayer.visibility = View.GONE
        sheetLayer.removeAllViews()
    }

    private fun emptyState(msg: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            addView(glyph("doc", 0xFFD1D1D6.toInt(), 1.6f, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(tv(msg, 13.5f, TXT3, false, Gravity.CENTER),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }

    /** 列表进场动画：子项依次淡入 + 上滑（stagger），提升列表质感 */
    private fun staggerIn(container: ViewGroup, baseDelay: Long = 0) {
        val n = container.childCount
        for (i in 0 until n) {
            val v = container.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(10).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(baseDelay + i * 35)
                .setDuration(300).setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    // =====================================================================
    // 收藏
    // =====================================================================
    private fun buildFavPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 分类区：横向 chips（全部/各分类）+ 新建；长按分类可重命名/删除
        val cats = DB.categories()
        // 通体约束：分类条整体放入一块白色圆角卡，chips 居中规整排列
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        val chipsRow = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val allCnt = DB.favorites(-1).size
        chips.addView(favChip("全部 $allCnt", CORAL, favCategoryFilter == -1L) { favCategoryFilter = -1L; renderCurrent() })
        for ((id, name) in cats) {
            val cnt = DB.favorites(id).size
            chips.addView(favChip("$name $cnt", PURPLE, favCategoryFilter == id,
                { showCategoryActions(id, name) },
                { favCategoryFilter = id; renderCurrent() }))
        }
        if (!selectMode) {
            chips.addView(favChip("+ 新建", CORAL, false) { showNewCategoryDialog() })
        }
        chips.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
        chipsRow.addView(chips)
        cRow.addView(chipsRow, LinearLayout.LayoutParams(0, dp(32), 1f))
        col.addView(cRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(1) })

        val list = buildFavList()
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return col
    }

    /** 收藏页分类 chip：圆角矩形 + 数量角标；长按可管理分类（重命名/删除） */
    private fun favChip(text: String, color: Int, on: Boolean, onLong: (() -> Unit)? = null, block: () -> Unit = {}): TextView {
        val t = tv(text, 12.5f, if (on) Color.WHITE else TXT3, true, Gravity.CENTER)
        t.background = rnd(if (on) color else 0xFFF2F2F7.toInt(), 12)
        val lto = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
        lto.setMargins(0, 0, dp(6), 0)
        t.layoutParams = lto
        t.setPadding(dp(13), 0, dp(13), 0)
        t.setOnClickListener { block() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        press(t)
        return t
    }

    private fun buildFavList(): View {
        val allFavs = DB.favorites(-1)
        if (allFavs.isEmpty())
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(glyph("starline", 0xFFD1D1D6.toInt(), 1.5f, 52), LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(tv("还没有收藏", 16f, TXT, true, Gravity.CENTER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
                addView(tv("解析视频后点击卡片上的星标即可收藏，并可分类整理", 12.5f, TXT3, false, Gravity.CENTER),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }

        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (favCategoryFilter >= 0) {
            // 指定分类：单组列表
            val favs = allFavs.filter { it.categoryId == favCategoryFilter }
            if (favs.isEmpty()) return emptyState("该分类下暂无收藏")
            scrollContent.addView(favGroup(favs))
        } else {
            // 全部：按分类分组（默认收藏夹 → 用户自建分类）
            val groups = LinkedHashMap<Long, MutableList<FavoriteBean>>()
            for (f in allFavs) groups.getOrPut(f.categoryId) { ArrayList() }.add(f)
            groups.forEach { (cid, list) ->
                val name = DB.categories().find { it.first == cid }?.second ?: "默认收藏夹"
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                head.addView(dot(if (cid == 0L) CORAL else PURPLE, 7))
                head.addView(tv(name, 13.5f, TXT, true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
                head.addView(tv("${list.size} 个", 11.5f, 0xFFAEAEB2.toInt()),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                scrollContent.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                scrollContent.addView(favGroup(list), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
        staggerIn(scrollContent, 60)
        scrollContent.addView(TextView(this).apply { text = " "; textSize = 3f })
        return scrolled { scrollContent }
    }

    /** 一组收藏卡片（白色圆角容器 + 分隔线） */
    private fun favGroup(favs: List<FavoriteBean>): View {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        favs.forEachIndexed { i, f ->
            group.addView(favCell(f))
            if (i != favs.lastIndex)
                group.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(62) })
        }
        return group
    }

    private fun favCell(f: FavoriteBean): View {
        val b = f.bean
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        cell.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        press(cell)  // 阻尼按压缩放（真弹簧）

        if (selectMode) {
            val ck = selCheck(selected.contains(f.id), CORAL)
            selectChecks[f.id] = ck
            cell.addView(ck,
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rnd(0xFFF2F2F7.toInt(), 12) }
        ImageLoader.load(b.cover, cover)
        cell.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)))

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(tv(b.title.ifEmpty { "未命名作品" }.let { if (it.length > 24) it.take(24) + "…" else it }, 14.5f, TXT, true))
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(tv(platformName(b.platform), 11.5f, platformColor(b.platform), true))
        if (b.author.isNotEmpty())
            sub.addView(tv("· ${b.author.let { if (it.length > 10) it.take(10) + "…" else it }}", 11.5f, TXT3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(5) })
        textCol.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        textCol.addView(tv("收藏于 " + fmtTime(f.createdAt), 10.5f, 0xFFC7C7CC.toInt()),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })

        // 下载状态区（实时进度 / 暂停 / 失败 / 已下载），tag 供下载广播局部刷新，不重建整页
        statusesOf()[b.id]?.let { dl ->
            val dlInfo = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "dlInfo:${b.id}"
            }
            dlInfo.addView(buildDlInfoInner(dl))
            textCol.addView(dlInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        cell.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })

        cell.addView(glyph("star", GOLD, 1.6f, 16), LinearLayout.LayoutParams(dp(16), dp(16)))
        cell.addView(chevron(), LinearLayout.LayoutParams(dp(15), dp(15)).apply { leftMargin = dp(2) })

        cell.setOnClickListener { if (selectMode) toggleSelect(f.id) else openDetail(b) }
        // 长按：弹出媒体操作面板（下载 / 移动 / 取消收藏 / 删除 / 多选），风格与全局 ActionSheet 统一
        cell.setOnLongClickListener {
            if (selectMode) { toggleSelect(f.id); true }
            else { showFavMediaSheet(f); true }
        }
        return cell
    }

    /** 收藏项长按操作面板（iOS ActionSheet 风格：标题 + 操作列表 + 取消） */
    private fun showFavMediaSheet(f: FavoriteBean) {
        val b = f.bean
        val hasVideo = b.firstVideoUrl().isNotEmpty()
        val title = b.title.ifEmpty { "未命名作品" }.let { if (it.length > 16) it.take(16) + "…" else it }
        val items = ArrayList<Pair<String, Int>>()
        items.add((if (hasVideo) "下载视频" else "下载图集") to BLUE)
        items.add("移动到分类" to PURPLE)
        items.add("取消收藏" to RED)
        items.add("删除记录" to RED)
        items.add("多选" to TXT)
        showActionSheet("「$title」", items) { idx ->
            when (idx) {
                0 -> downloadFavMedia(b)
                1 -> pickMoveCategory(f, b)
                2 -> {
                    DB.removeFavorite(f.historyId)
                    toast("已取消收藏"); renderCurrent()
                }
                3 -> confirmDeleteFavorite(b)
                4 -> enterSelect("favorites", f.id)
            }
        }
    }

    /** 收藏项下载：视频 / 图集（两者都有时让用户选） */
    private fun downloadFavMedia(b: MediaBean) {
        val hasVideo = b.firstVideoUrl().isNotEmpty()
        val imgs = imageUrlsList(b)
        when {
            hasVideo && imgs.isNotEmpty() -> showActionSheet("选择下载内容",
                listOf("下载视频" to BLUE, "下载全部图片（${imgs.size} 张）" to BLUE)) { idx ->
                    if (idx == 0) downloadFirst(b)
                    else {
                        imgs.forEachIndexed { i, u -> startDownload(u, imgFileName(b, i), "image", b.id) }
                        toast("已加入下载队列")
                    }
                }
            hasVideo -> downloadFirst(b)
            imgs.isNotEmpty() -> showImageDownloadSheet(imgs, b)
            else -> toast("暂无可下载媒体")
        }
    }

    /** 删除收藏记录：取消收藏 + 历史移入回收站（可恢复） */
    private fun confirmDeleteFavorite(b: MediaBean) {
        AlertDialog.Builder(this).setTitle("删除该收藏？")
            .setMessage("将从收藏中移除，历史记录移入回收站，可随时恢复")
            .setPositiveButton("删除") { _, _ ->
                DB.removeFavorite(b.id)
                DB.softDeleteHistory(b.id)
                toast("已删除"); renderCurrent()
            }.setNegativeButton("取消", null).show()
    }

    /** 长按分类 chip：重命名 / 删除（删除后收藏迁移默认分类） */
    private fun showCategoryActions(id: Long, name: String) {
        showActionSheet("分类「$name」",
            listOf("重命名" to BLUE, "删除分类" to RED)) { idx ->
                when (idx) {
                    0 -> showRenameCategory(id, name)
                    1 -> showActionSheet("删除分类「$name」？其中的收藏会移到默认收藏夹", listOf("确认删除" to RED)) {
                        DB.deleteCategory(id)
                        if (favCategoryFilter == id) favCategoryFilter = -1L
                        renderCurrent(); toast("已删除分类")
                    }
                }
            }
    }

    /** 重命名分类对话框 */
    private fun showRenameCategory(id: Long, old: String) {
        val input = EditText(this).apply {
            setText(old); textSize = 14f
            setTextColor(TXT); setHintTextColor(0xFFC7C7CC.toInt())
            background = rnd(0xFFF2F2F7.toInt(), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名分类")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { DB.renameCategory(id, n); renderCurrent(); toast("已重命名") }
            }.setNegativeButton("取消", null).show()
    }

    private fun showNewCategoryDialog(onCreated: ((Long) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "分类名称"; textSize = 14f
            setTextColor(TXT); setHintTextColor(0xFFC7C7CC.toInt())
            background = rnd(0xFFF2F2F7.toInt(), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("新建收藏分类")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                if (input.text.toString().trim().isNotEmpty()) {
                    val id = DB.addCategory(input.text.toString().trim())
                    renderCurrent(); toast("已创建分类")
                    onCreated?.invoke(id)
                }
            }.setNegativeButton("取消", null).show()
    }

    // =====================================================================
    // 我的（回收站 / 权限 / 关于）
    // =====================================================================
    private fun buildMinePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = " "; textSize = 4f })

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("1.0.0")

        // ---- 顶部：应用卡片（图标 + 名称 + 版本） ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rnd(CARD, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val appIcon = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        header.addView(appIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
        val hCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hCol.addView(tv("轻解析", 19f, TXT, true))
        hCol.addView(tv("iOS 极简质感 · 多平台视频解析", 12f, TXT3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        hCol.addView(tv("版本 $ver", 11.5f, 0xFFC7C7CC.toInt()),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        header.addView(hCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 设置分组卡片（统一圆角白卡 + 细分割线） ----
        fun groupCard(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 16)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun row(g: LinearLayout, icon: String, iconColor: Int, title: String, sub: String,
                onClick: (View) -> Unit, last: Boolean = false) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener(onClick)
            }
            press(r)
            val iconBg = FrameLayout(this).apply { background = rnd(iconColor, 12) }
            iconBg.addView(glyph(icon, Color.WHITE, 1.8f, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            r.addView(iconBg, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
            val tCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tCol.addView(tv(title, 15f, TXT, true))
            tCol.addView(tv(sub, 12f, TXT3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            r.addView(tCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(chevron(), LinearLayout.LayoutParams(dp(16), dp(16)))
            g.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
            if (!last) g.addView(hairline(this),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(58) })
        }

        val recycleCount = DB.recycled().size
        val gData = groupCard()
        row(gData, "bin", RED, "回收站", if (recycleCount > 0) "$recycleCount 条记录可恢复" else "暂无记录",
            { pvMode = "recycle"; renderCurrent() }, last = true)
        col.addView(gData, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val gPower = groupCard()
        row(gPower, "bolt", ORANGE, "电池后台运行权限", "允许后台持续解析与下载（建议开启）",
            { requestBatteryOptimization() })
        row(gPower, "bell", BLUE, "通知权限", "解析与下载进度实时通知",
            { requestPermissionsAtLaunch() }, last = true)
        col.addView(gPower, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val gAbout = groupCard()
        row(gAbout, "info", BLUE, "关于轻解析", "版本 $ver · 介绍与开源项目",
            { showAboutSheet() }, last = true)
        col.addView(gAbout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // 底部：开源声明
        val foot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        foot.addView(tv("轻解析 · 开源免费", 11.5f, TXT3, false, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        foot.addView(tv(GITHUB_URL.removePrefix("https://"), 11f, BLUE, false, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        col.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(TextView(this).apply { text = " "; textSize = 3f })
        return scrolled { col }
    }

    /** 关于面板：App 介绍 + 开源仓库链接（与全局 ActionSheet 同款 iOS 极简风） */
    private fun showAboutSheet() {
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("1.0.0")
        dismissSheet()
        sheetShowing = true
        val myToken = ++sheetToken
        sheetLayer.removeAllViews()
        sheetLayer.alpha = 1f
        sheetLayer.visibility = View.VISIBLE
        sheetHidTabBar = tabBar.visibility == View.VISIBLE
        if (sheetHidTabBar) tabBar.visibility = View.GONE

        val overlay = View(this).apply {
            setBackgroundColor(0x60000000)
            setOnClickListener { dismissSheet() }
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 16)
        }

        // 标题 + 版本
        sheet.addView(tv("关于轻解析", 18f, TXT, true, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        sheet.addView(tv("版本 $ver", 12f, 0xFFAEAEB2.toInt(), false, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // 可滚动内容区（内置滑动查看文本）
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String, body: String) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rnd(CARD, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(tv(title, 13.5f, TXT, true))
            card.addView(tv(body, 13f, TXT3).apply { setLineSpacing(dp(4).toFloat(), 1f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            scrollContent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) })
        }
        section("项目简介",
            "轻解析是一款 iOS 极简质感的多平台视频解析下载工具，支持 抖音 / 快手 / 哔哩哔哩 / TikTok / X（推特）等主流平台。" +
            "核心能力：一键解析高清直链、视频 / 图集 / 原声下载、多线程分片传输与后台断点续传、历史记录、收藏管理与回收站恢复。" +
            "全程无广告、无账号体系，完全开源免费。")
        section("原创性声明",
            "本项目为开发者从零独立设计与实现：界面布局、矢量图标、交互动效均为 Canvas 自绘，未使用任何第三方 UI 框架；" +
            "解析、下载、数据库等工程结构均为原创实现。全部代码公开托管于 GitHub，欢迎审阅、建议与贡献。")
        section("免责声明",
            "本应用仅用于个人学习与信息技术交流。解析内容版权归原作者及所属平台所有，请勿将本工具用于商业用途或传播侵权内容。" +
            "因使用本应用产生的一切后果由使用者自行承担；如您的作品被解析并在应用中出现，请联系开源仓库作者，我们将在核实后协助处理。" +
            "请勿下载、传播违反中国法律法规及平台规则的内容。")
        section("技术栈",
            "Android 原生 Kotlin（minSdk 24）· 自绘 View 体系与矢量图标（Canvas）· MediaPlayer 自定义 UA/Referer 直链预览 · " +
            "前台服务 + 多线程分片断点下载 · SQLite（SQLiteOpenHelper）本地存储 · Gradle 构建，无任何第三方依赖。")

        // GitHub 链接行（两行卡：标题 + 完整仓库地址，点按跳转）
        val gh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }
            }
        }
        press(gh)
        val ghRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ghIcon = FrameLayout(this).apply { background = rnd(0xFF24292F.toInt(), 12) }
        ghIcon.addView(glyph("link", Color.WHITE, 1.7f, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        ghRow.addView(ghIcon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
        ghRow.addView(tv("开源项目 · GitHub", 14f, TXT, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ghRow.addView(chevron(), LinearLayout.LayoutParams(dp(15), dp(15)))
        gh.addView(ghRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gh.addView(tv(GITHUB_URL, 11f, BLUE),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); leftMargin = dp(48) })
        scrollContent.addView(gh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(scrollContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { topMargin = dp(16); leftMargin = dp(12); rightMargin = dp(12) })

        // 关闭（固定底部）
        val cancel = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rnd(CARD, 14)
            setOnClickListener { dismissSheet() }
        }
        press(cancel)
        cancel.addView(tv("关闭", 17f, TXT, true, Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 1f))
        sheet.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4); leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(14) })

        sheetLayer.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val maxH = (resources.displayMetrics.heightPixels * 0.72).toInt().coerceAtLeast(dp(420))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH, Gravity.BOTTOM)
        lp.setMargins(dp(12), 0, dp(12), dp(12))
        sheetLayer.addView(sheet, lp)

        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        sheet.translationY = dp(520).toFloat()
        sheet.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2.6f)).start()
    }

    private fun buildRecyclePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { exitSelect(); pvMode = "mine"; renderMine() }
        }.also { back ->
            back.addView(glyph("back", TXT, 2.2f, 22), LinearLayout.LayoutParams(dp(22), dp(22)))
            back.addView(tv("回收站", 17f, TXT, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val items = DB.recycled()
        if (items.isEmpty()) {
            col.addView(emptyState("回收站是空的\n\n删除的历史记录和下载任务会暂存在这里，\n可以恢复或彻底删除"),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(30) })
            return col
        }
        if (!selectMode) {
            col.addView(tv("清空回收站", 13.5f, RED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
                (col.getChildAt(col.childCount - 1) as TextView).setOnClickListener {
                    if (DB.recycled().isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity).setTitle("清空回收站")
                            .setMessage("将彻底删除 ${DB.recycled().size} 条记录，无法恢复")
                            .setPositiveButton("彻底删除") { _, _ -> DB.clearRecycle(); renderCurrent() }
                            .setNegativeButton("取消", null).show()
                    }
                }
            }
        }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollContent.addView(TextView(this).apply { text = " "; textSize = 1f })
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 16)
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        items.forEachIndexed { i, r ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            cell.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            press(cell)  // 阻尼按压缩放（真弹簧）
            if (selectMode) {
                val ck = selCheck(selected.contains(r.id), RED)
                selectChecks[r.id] = ck
                cell.addView(ck,
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
            }
            val kindBg = FrameLayout(this).apply { background = rnd(if (r.kind == "history") PURPLE else BLUE, 11) }
            kindBg.addView(glyph(if (r.kind == "history") "play" else "download", Color.WHITE, 1.9f, 17),
                FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            cell.addView(kindBg, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(tv(r.title.take(30).let { if (r.title.length > 30) "$it…" else it }, 14f, TXT, true))
            textCol.addView(tv("${if (r.kind == "history") "历史记录" else "下载任务"} · 删除于 ${fmtTime(r.deletedAt)}", 11.5f, TXT3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            cell.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cell.setOnClickListener { if (selectMode) toggleSelect(r.id) else restoreOne(r) }
            cell.setOnLongClickListener { enterSelect("recycle", r.id); true }
            group.addView(cell)
            if (i != items.lastIndex)
                group.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(48) })
        }
        scrollContent.addView(group)
        col.addView(scrolled { scrollContent }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        return col
    }

    private fun restoreOne(r: RecycleBean) {
        AlertDialog.Builder(this).setTitle("恢复「${r.title.take(18)}」？")
            .setMessage(if (r.kind == "history") "恢复后将重新出现在历史记录中" else "恢复后重新加入下载队列并自动开始")
            .setPositiveButton("恢复") { _, _ ->
                val before = DB.allDownloads().map { it.id }.toSet()
                DB.restoreRecycle(r.id)
                if (r.kind == "download") {
                    startService()
                    DB.allDownloads().firstOrNull { it.id !in before && it.status == DB.DL_WAITING }
                        ?.let { MediaService.resume(this, it.id) }
                }
                renderCurrent(); toast("已恢复")
            }.setNegativeButton("取消", null).show()
    }

    // =====================================================================
    // 详情页
    // =====================================================================
    private var tabBeforeDetail = 0   // 进入详情前的 Tab（返回时还原，避免跳回 tab1）

    private fun openDetail(b: MediaBean? = null) {
        b?.let { detailBean = it }
        if (detailBean == null) { toast("暂无详情"); return }
        val wasDetail = pvMode == "detail"
        tabBeforeDetail = currentTab
        pvMode = "detail"
        tabBar.visibility = View.GONE
        if (!wasDetail) addImmersive()
        renderCurrent()
    }

    private fun buildDetailPage(): View {
        val b = detailBean ?: MediaBean()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 顶栏
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onBackPressed() }
        }.also { back ->
            back.addView(glyph("back", BLUE, 2.2f, 22), LinearLayout.LayoutParams(dp(22), dp(22)))
            back.addView(tv("返回", 15f, BLUE, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        top.addView(tv(platformName(b.platform).ifEmpty { "详情" }, 13f, TXT3, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(glyph("more", 0xFFC7C7CC.toInt(), 2.4f, 18),
            LinearLayout.LayoutParams(dp(40), dp(44)).apply { gravity = Gravity.CENTER })
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        val scroll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 封面（大图）
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rnd(0xFFF2F2F7.toInt(), 18)
        }
        ImageLoader.load(b.cover, cover)
        scroll.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        // 信息区：无卡片框体，纯文本 + 细分割线
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(b.title.ifEmpty { "未命名作品" }, 18f, TXT, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        meta.addView(dot(platformColor(b.platform), 8))
        meta.addView(tv(platformName(b.platform), 12.5f, platformColor(b.platform), true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        if (b.author.isNotEmpty())
            meta.addView(tv("@" + b.author, 12.5f, TXT3),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(14) })
        if (b.parsedAt > 0)
            meta.addView(tv(fmtTime(b.parsedAt), 11.5f, 0xFFC7C7CC.toInt()),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(14) })
        meta.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val favBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (b.isFavorite) {
            favBox.addView(glyph("star", GOLD, 1.6f, 15), LinearLayout.LayoutParams(dp(15), dp(15)))
            favBox.addView(tv("已收藏", 12f, GOLD, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        } else {
            favBox.addView(glyph("starline", CORAL, 1.6f, 15), LinearLayout.LayoutParams(dp(15), dp(15)))
            favBox.addView(tv("收藏", 12f, CORAL, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
            favBox.setOnClickListener { toggleFavorite(b) }
        }
        meta.addView(favBox)
        info.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        info.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14) })

        // 操作
        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(opBtn("share", "分享", GREEN) { shareMedia(b) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { topMargin = dp(14) })
        ops.addView(opBtn("download", "下载视频", BLUE) { downloadSmart(b) },
            LinearLayout.LayoutParams(0, dp(44), 1.2f).apply { topMargin = dp(14); leftMargin = dp(10) })
        info.addView(ops, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        buildDetailDownloadCard(b)?.let {
            scroll.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }

        // 视频实时预览
        if (b.firstVideoUrl().isNotEmpty()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val vv = HeaderVideoView(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                onError = { true }
                onRetry = { toast("链接可能已过期，可返回首页重新解析") }
                onVideoSize = { w, h -> fitVideoBox(this, w, h) }
                onDownload = { downloadFirst(b) }
                onToggleFullscreen = { enterOrExitFullscreen(this) }
            }
            box.addView(vv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
            liveVideos.add(vv)
            scroll.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            vv.load(videoPreviewUrls(b))
            scroll.addView(tv("点击画面全屏 · 控制条可暂停/倍速/下载", 11f, 0xFFC7C7CC.toInt()),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        // 下载选项（不展示链接文本）
        val vids = videoList(b)
        if (vids.isNotEmpty()) {
            val vCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rnd(CARD, 16); setPadding(dp(14), dp(2), dp(14), dp(2)) }
            vCard.addView(tv("下载选项 · ${vids.size}", 13f, TXT, true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
            vCard.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            vids.forEachIndexed { i, (name, url) ->
                val isAudio = name.startsWith("音频")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(glyph(if (isAudio) "music" else "play", BLUE, 1.7f, 17),
                    LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(10) })
                row.addView(tv(name, 13.5f, TXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val dlBtn = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        startDownload(url,
                            "${platformName(b.platform)}_${b.title.let { if (it.length > 12) it.take(12) else it }}_${i + 1}.${if (isAudio) "mp3" else "mp4"}",
                            if (isAudio) "audio" else "video", b.id)
                    }
                }
                dlBtn.addView(glyph("download", BLUE, 1.8f, 14), LinearLayout.LayoutParams(dp(14), dp(14)))
                dlBtn.addView(tv("下载", 13f, BLUE, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
                row.addView(dlBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
                vCard.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
                if (i != vids.lastIndex)
                    vCard.addView(hairline(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { leftMargin = dp(27) })
            }
            scroll.addView(vCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }

        // 图集：点击缩略图进入全屏查看器（可放大 / 多图轮换）；下载支持单张 / 全部
        val imgs = imageUrlsList(b)
        if (imgs.isNotEmpty()) {
            val gCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rnd(CARD, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            val gHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            gHead.addView(tv("图集 · ${imgs.size} 张", 13f, TXT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val dlImgBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(2), 0)
                setOnClickListener { showImageDownloadSheet(imgs, b) }
            }
            press(dlImgBtn)
            dlImgBtn.addView(GlyphView(this).apply { icon = "download"; tint = BLUE; strokeW = 1.8f },
                LinearLayout.LayoutParams(dp(14), dp(14)))
            dlImgBtn.addView(tv("下载", 12.5f, BLUE, true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
            gHead.addView(dlImgBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
            gCard.addView(gHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val hsv = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
            }
            val list = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            imgs.forEachIndexed { i, u ->
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rnd(0xFFF2F2F7.toInt(), 8) }
                ImageLoader.load(u, iv)
                press(iv)
                iv.setOnClickListener { openImageViewer(imgs, i) }
                list.addView(iv, LinearLayout.LayoutParams(dp(84), dp(84)).apply { rightMargin = dp(8) })
            }
            hsv.addView(list)
            gCard.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            scroll.addView(gCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }

        scroll.addView(TextView(this).apply { text = " "; textSize = 3f })
        col.addView(scrolled { scroll }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // =====================================================================
    // 图集：全屏查看器（放大 / 多图轮换）+ 单张/全部下载
    // =====================================================================

    /** 提取图集图片直链（kind=image；旧数据无 kind 时回退全部 url） */
    private fun imageUrlsList(b: MediaBean): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until b.imageUrls.length()) {
            val o = b.imageUrls.optJSONObject(i) ?: continue
            if (o.optString("kind", "") == "image") {
                val u = o.optString("url", ""); if (u.isNotEmpty()) out.add(u)
            }
        }
        if (out.isEmpty()) {
            for (i in 0 until b.imageUrls.length()) {
                val o = b.imageUrls.optJSONObject(i) ?: continue
                val u = o.optString("url", ""); if (u.isNotEmpty()) out.add(u)
            }
        }
        return out.distinct()
    }

    /** 打开全屏图片查看器：横向滑动轮换 + 双击/捏合缩放，顶部显示页码 */
    private fun openImageViewer(images: List<String>, pos: Int) {
        dismissSheet()
        // 全屏查看器须盖住底部玻璃栏，否则玻璃遮罩会挡到查看器底部的组件
        viewerHidTabBar = tabBar.visibility == View.VISIBLE
        if (viewerHidTabBar) tabBar.visibility = View.GONE
        if (images.isEmpty()) return
        imgLayer.removeAllViews()
        imgPages = images.size

        // 顶部：页码 + 关闭按钮（iOS 极简：深蓝灰半透小胶囊 + 细白叉）
        imgIndexTxt = tv("${pos + 1} / $imgPages", 12.5f, 0xFFFFFFFF.toInt(), true, Gravity.CENTER).apply {
            background = rnd(0x7A111318.toInt(), 12)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        imgLayer.addView(imgIndexTxt!!, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(16) })
        val close = GlyphView(this).apply {
            icon = "x"; tint = 0xFFFFFFFF.toInt(); strokeW = 1.8f
            background = rnd(0x7A111318.toInt(), 16)
            setOnClickListener { closeImageViewer() }
        }
        imgLayer.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(14), dp(12), 0) })

        // 中部：横向翻页（每页一图，支持缩放）
        val sw = resources.displayMetrics.widthPixels
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        images.forEach { u ->
            val iv = ZoomableImageView(this)
            ImageLoader.load(u, iv)
            row.addView(iv, LinearLayout.LayoutParams(sw, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val pager = object : HorizontalScrollView(this@MainActivity) {
            override fun onScrollChanged(l: Int, t: Int, oldL: Int, oldT: Int) {
                super.onScrollChanged(l, t, oldL, oldT)
                idleCheck()
            }
            private var idleRun: Runnable? = null
            private fun idleCheck() {
                idleRun?.let { removeCallbacks(it) }
                val r = Runnable { snapToPage() }
                idleRun = r
                postDelayed(r, 150)
            }
            private fun snapToPage() {
                val curX = scrollX
                val page = ((curX + sw / 2) / sw).coerceIn(0, imgPages - 1)
                if (curX != page * sw) {
                    // 停止播放中的属性动画时间轴，确保 smoothScroll 生效
                    smoothScrollTo(page * sw, 0)
                }
                updateImgIndex(page)
            }
        }
        pager.isHorizontalScrollBarEnabled = false
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pager.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        imgPager = pager
        imgLayer.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        imgLayer.alpha = 0f
        imgLayer.visibility = View.VISIBLE
        addImmersive()
        imgLayer.animate().alpha(1f).setDuration(200).start()

        // 初始定位到点击的那一张
        pager.post {
            pager.scrollTo(pos * sw, 0)
            updateImgIndex(pos)
        }
    }

    private fun updateImgIndex(page: Int) {
        imgIndexTxt?.text = "${(page + 1).coerceIn(1, imgPages)} / $imgPages"
    }

    private fun closeImageViewer() {
        removeImmersive()
        imgPager = null
        imgLayer.animate().alpha(0f).setDuration(160).withEndAction {
            imgLayer.visibility = View.GONE
            imgLayer.removeAllViews()
            // 收起后恢复底部玻璃栏（仅当进入查看器前是显示的）
            if (viewerHidTabBar) {
                viewerHidTabBar = false
                if (!selectMode && !sheetShowing) tabBar.visibility = View.VISIBLE
            }
        }.start()
    }

    /** 图集下载：弹出列表选择单张 / 全部（与全局 ActionSheet 风格统一） */
    private fun showImageDownloadSheet(images: List<String>, b: MediaBean) {
        val items = mutableListOf<Pair<String, Int>>()
        items.add("全部下载（${images.size} 张）" to BLUE)
        images.forEachIndexed { i, _ -> items.add("图片 ${i + 1}" to TXT) }
        showActionSheet("选择要下载的图片", items) { idx ->
            if (idx == 0) {
                images.forEachIndexed { i, u ->
                    startDownload(u, imgFileName(b, i), "image", b.id)
                }
                toast("已加入 ${images.size} 张图片下载")
            } else {
                startDownload(images[idx - 1], imgFileName(b, idx - 1), "image", b.id)
                toast("已加入下载队列")
            }
        }
    }

    private fun imgFileName(b: MediaBean, i: Int): String {
        val t = b.title.ifEmpty { "未命名" }.let { if (it.length > 12) it.take(12) else it }
        return "${platformName(b.platform)}_${t}_img${i + 1}.jpg"
    }

    /** 预览用视频候选直链（主地址 + 图集内嵌视频），失败自动切换下一个 */
    private fun videoPreviewUrls(b: MediaBean): List<String> {
        val out = ArrayList<String>()
        if (b.videoUrl.isNotEmpty()) out.add(b.videoUrl)
        for (i in 0 until b.imageUrls.length()) {
            val o = b.imageUrls.optJSONObject(i) ?: continue
            if (o.optString("kind", "") == "video") {
                val u = o.optString("url", "")
                if (u.isNotEmpty()) out.add(u)
            }
        }
        return out.distinct()
    }

    private fun videoList(b: MediaBean): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (b.videoUrl.isNotEmpty()) out.add((if (b.quality.isNotEmpty()) b.quality else "高清") to b.videoUrl)
        for (i in 0 until b.imageUrls.length()) {
            val o = b.imageUrls.optJSONObject(i) ?: continue
            val k = o.optString("kind", ""); val u = o.optString("url", "")
            if (k == "video" && u.isNotEmpty()) out.add("视频 ${out.size + 1}" to u)
        }
        for (i in 0 until b.audioUrls.length()) {
            val o = b.audioUrls.optJSONObject(i) ?: continue
            val u = o.optString("url", "")
            if (u.isNotEmpty()) out.add("音频 ${i + 1}" to u)
        }
        return out
    }

    private fun toggleFavorite(b: MediaBean) {
        val existing = DB.favorites(-1).find { it.historyId == b.id }
        if (existing != null) {
            // 已收藏：可取消收藏 / 移动到其他分类
            showActionSheet("已收藏于「${catNameOf(existing.categoryId)}」",
                listOf("取消收藏" to RED, "移动到其他分类" to BLUE)) { idx ->
                    when (idx) {
                        0 -> { DB.removeFavorite(b.id); toast("已取消收藏"); renderCurrent() }
                        1 -> pickMoveCategory(existing, b)
                    }
                }
        } else {
            // 未收藏：选择分类（默认收藏夹 / 自建分类 / 新建分类后立即收藏）
            val cats = DB.categories()
            val items = ArrayList<Pair<String, Int>>()
            items.add("默认收藏夹" to TXT)
            for ((_, name) in cats) items.add(name to PURPLE)
            items.add("＋ 新建分类" to CORAL)
            showActionSheet("收藏到…", items) { idx ->
                val addTo = { cid: Long ->
                    DB.addFavorite(b.id, cid)
                    DB.setHistoryFlag(b.id, true)
                    toast("已收藏到「${catNameOf(cid)}」")
                    renderCurrent()
                }
                when {
                    idx == 0 -> addTo(0L)
                    idx < items.size - 1 -> addTo(cats[idx - 1].first)
                    else -> showNewCategoryDialog { cid -> addTo(cid) }
                }
            }
        }
    }

    /** 分类名（默认收藏夹兜底） */
    private fun catNameOf(cid: Long): String =
        DB.categories().find { it.first == cid }?.second ?: "默认收藏夹"

    /** 把已收藏的条目移动到其他分类 */
    private fun pickMoveCategory(existing: FavoriteBean, b: MediaBean) {
        val cats = DB.categories().filter { it.first != existing.categoryId }
        val items = ArrayList<Pair<String, Int>>()
        items.add("默认收藏夹" to TXT)
        for ((_, name) in cats) items.add(name to PURPLE)
        showActionSheet("移动到…", items) { idx ->
            val target = if (idx == 0) 0L else cats[idx - 1].first
            DB.moveFavorites(listOf(existing.id), target)
            toast("已移动到「${catNameOf(target)}」")
            renderCurrent()
        }
    }

    private fun shareMedia(b: MediaBean) {
        val text = "【${b.platformName}】${b.title}\n作者：${b.author.ifEmpty { "未知" }}\n作品：${platformName(b.platform)}\n（来自 轻解析）"
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "分享到"))
    }

    private fun downloadFirst(b: MediaBean) {
        val u = b.firstVideoUrl()
        if (u.isEmpty()) { toast("无可用下载地址"); return }
        startDownload(u, "${platformName(b.platform)}_${b.title.let { if (it.length > 14) it.take(14) else it }}.mp4", "video", b.id)
    }

    /** 智能下载：同一作品已有未完成任务（暂停/失败/等待）则直接续传，否则新建任务 */
    private fun downloadSmart(b: MediaBean) {
        val dl = DB.allDownloads().lastOrNull { it.historyId == b.id }
        if (dl != null) {
            when (dl.status) {
                DB.DL_DOWNLOADING -> toast("正在下载中，进度见下方")
                DB.DL_PAUSED, DB.DL_FAILED, DB.DL_WAITING -> {
                    startService()
                    MediaService.resume(this, dl.id)
                    toast("已继续下载…")
                }
                else -> downloadFirst(b)
            }
        } else downloadFirst(b)
    }

    /** 详情页下载状态卡：进度 + 暂停 / 继续 / 重试（断点续传）。无下载记录时返回 null */
    private fun buildDetailDownloadCard(b: MediaBean): LinearLayout? {
        val dl = DB.allDownloads().lastOrNull { it.historyId == b.id } ?: return null
        val pct = if (dl.totalSize > 0) ((dl.downloadedSize * 100) / dl.totalSize).toInt().coerceIn(0, 100) else 0
        val (label, color) = when (dl.status) {
            DB.DL_DOWNLOADING -> "正在下载" to BLUE
            DB.DL_PAUSED -> "已暂停" to ORANGE
            DB.DL_FAILED -> "下载失败" to RED
            DB.DL_WAITING -> "等待下载" to TXT3
            else -> "已下载到本地" to GREEN
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rnd(CARD, 14)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(GlyphView(this).apply { icon = "download"; tint = color; strokeW = 1.7f },
            LinearLayout.LayoutParams(dp(16), dp(16)))
        row.addView(tv(label, 13.5f, color, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        row.addView(tv(if (dl.downloadedSize > 0) "已下载 $pct%" else "", 12f, TXT3),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val act: Pair<String, (() -> Unit)?> = when (dl.status) {
            DB.DL_DOWNLOADING -> "暂停" to { MediaService.pause(this@MainActivity, dl.id) }
            DB.DL_PAUSED, DB.DL_FAILED, DB.DL_WAITING -> "继续下载" to {
                startService()
                MediaService.resume(this@MainActivity, dl.id)
            }
            else -> "" to null
        }
        if (act.first.isNotEmpty() && act.second != null) {
            val btn = TextView(this).apply {
                text = act.first; textSize = 12.5f; setTextColor(color)
                gravity = Gravity.CENTER
                background = rnd(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)), 9)
                setPadding(dp(14), dp(2), dp(14), dp(2))
                setOnClickListener { act.second?.invoke() }
            }
            press(btn)
            row.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        if (dl.status == DB.DL_DOWNLOADING || ((dl.status == DB.DL_PAUSED || dl.status == DB.DL_FAILED) && pct > 0)) {
            card.addView(progressBar(pct), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        }
        return card
    }

    private fun startDownload(url: String, fileName: String, kind: String, historyId: Long) {
        startService()
        MediaService.download(this, url, fileName, kind, historyId)
        toast("已加入下载队列，通知栏可查看进度")
    }

    private fun renderMine() { pvMode = "mine"; renderTabBar(); renderCurrent(); showTabBar() }
    private fun showTabBar() { if (!selectMode) tabBar.visibility = View.VISIBLE }

    // ---------- 沉浸式系统栏（隐藏底部导航 + 状态栏，返回逐层恢复） ----------
    private var immersiveLayers = 0

    /** 进入全屏/详情/查看器时隐藏系统栏；支持嵌套叠加（详情→查看器→全屏） */
    private fun addImmersive() { immersiveLayers++; applySystemBars() }

    /** 退出某一层全屏后恢复；仅当所有层都退出才显示系统栏 */
    private fun removeImmersive() {
        if (immersiveLayers > 0) immersiveLayers--
        applySystemBars()
    }

    private fun applySystemBars() {
        if (immersiveLayers > 0) hideSystemBars() else showSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                window.insetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            runCatching {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { window.insetsController?.show(WindowInsets.Type.systemBars()) }
        } else {
            runCatching { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
        }
    }

    // ---------- 多选批量操作 ----------
    private fun selectedHistoryBeans(): List<MediaBean> = when (selectKind) {
        "history" -> DB.allHistory().filter { selected.contains(it.id) }
        "favorites" -> DB.favorites(favCategoryFilter).filter { selected.contains(it.id) }.map { it.bean }
        else -> emptyList()
    }

    private fun multiDelete() {
        val beans = selectedHistoryBeans()
        AlertDialog.Builder(this).setTitle("删除 ${beans.size} 条记录？")
            .setMessage("将移入回收站，可随时恢复")
            .setPositiveButton("删除") { _, _ ->
                for (b in beans) DB.softDeleteHistory(b.id)
                exitSelect(); toast("已移入回收站")
            }.setNegativeButton("取消", null).show()
    }

    private fun multiFavorite() {
        val beans = selectedHistoryBeans()
        var count = 0
        for (b in beans) {
            if (DB.favorites(-1).none { it.historyId == b.id }) {
                DB.addFavorite(b.id, 0); DB.setHistoryFlag(b.id, true); count++
            }
        }
        exitSelect(); toast("已收藏 $count 条")
    }

    private fun multiUnfavorite() {
        val beans = selectedHistoryBeans()
        for (b in beans) DB.removeFavorite(b.id)
        exitSelect(); toast("已取消收藏")
    }

    private fun multiMoveCategory() {
        val beans = selectedHistoryBeans()
        val cats = DB.categories()
        val names = arrayOf("默认分类") + cats.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle("移动到分类")
            .setItems(names) { _, which ->
                val catId = if (which == 0) 0L else cats[which - 1].first
                for (b in beans) {
                    DB.favorites(favCategoryFilter).filter { it.historyId == b.id }.forEach { f ->
                        DB.moveFavorites(listOf(f.id), catId)
                    }
                }
                exitSelect(); toast("已移动")
            }.show()
    }

    private fun multiDownload() {
        val beans = selectedHistoryBeans()
        var cnt = 0
        for (b in beans) {
            val u = b.firstVideoUrl()
            if (u.isNotEmpty()) { startDownload(u, "${platformName(b.platform)}_${b.title.let { if (it.length > 12) it.take(12) else it }}.mp4", "video", b.id); cnt++ }
        }
        exitSelect(); toast("已加入 $cnt 个下载")
    }

    private fun multiShare() {
        val beans = selectedHistoryBeans()
        val sb = StringBuilder()
        beans.forEachIndexed { i, b ->
            sb.append("${i + 1}. 【${b.platformName}】${b.title}\n   作者：${b.author.ifEmpty { "未知" }}\n")
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString() + "\n（来自 轻解析）")
        }
        startActivity(Intent.createChooser(i, "分享到"))
        exitSelect()
    }

    private fun multiDeleteDownloaded() {
        val beans = selectedHistoryBeans()
        AlertDialog.Builder(this).setTitle("删除 ${beans.size} 条已下载记录？")
            .setMessage("下载任务将被移入回收站（本地文件保留）")
            .setPositiveButton("删除") { _, _ ->
                for (b in beans) {
                    DB.allDownloads().filter { it.historyId == b.id && it.status == DB.DL_DONE }.forEach { d ->
                        DB.putRecycleDownload(d); DB.hardDeleteDownload(d.id)
                    }
                }
                exitSelect(); toast("已移入回收站")
            }.setNegativeButton("取消", null).show()
    }

    private fun multiRestore() {
        val ids = selected.toList()
        for (r in DB.recycled().filter { ids.contains(it.id) }) {
            DB.restoreRecycle(r.id)
            if (r.kind == "download") { startService() }
        }
        exitSelect(); toast("已恢复")
    }

    private fun multiPurge() {
        val ids = selected.toList()
        AlertDialog.Builder(this).setTitle("彻底删除 ${ids.size} 条？")
            .setMessage("删除后将无法恢复")
            .setPositiveButton("彻底删除") { _, _ ->
                ids.forEach { DB.purgeRecycle(it) }
                exitSelect(); toast("已彻底删除")
            }.setNegativeButton("取消", null).show()
    }

    // ---------- 网络状态刷新 ----------
    private fun refreshAllAfterDownload() {
        // 历史记录已合并进首页：原地只刷新历史小节，不重建整页（避免打断视频预览）
        if (pvMode != "home" || fsHost != null) return
        val sec = content.findViewWithTag<View>("historySection") ?: return
        val parent = sec.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(sec)
        val lp = sec.layoutParams
        parent.removeView(sec)
        val fresh = homeHistorySection()
        parent.addView(fresh, idx, lp)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestBatteryOptimization() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } else toast("已开启电池后台运行权限")
        } catch (_: Exception) { toast("无法打开设置") }
    }
}

// ---------- 简易图片加载（LRU + 线程，加载后回填 ImageView） ----------
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36"

    /** 异步加载网络图片到 view；view.tag 存 url，防止错位 */
    fun load(url: String, view: ImageView) {
        if (url.isEmpty()) return
        view.tag = url
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        Thread {
            var bmp: Bitmap? = null
            try {
                val conn = URL(url).openConnection()
                conn.setRequestProperty("User-Agent", UA)
                conn.connectTimeout = 12000
                bmp = BitmapFactory.decodeStream(conn.getInputStream())
            } catch (_: Exception) {}
            if (bmp != null) cache.put(url, bmp)
            val finalBmp = bmp
            view.post {
                if (finalBmp != null && view.tag == url) view.setImageBitmap(finalBmp)
            }
        }.start()
    }
}
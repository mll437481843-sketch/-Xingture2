package com.qqbq.export

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 全屏预览页：左右滑动切换，一键导出当前这张。 */
class ViewerActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var imageView: ImageView
    private lateinit var infoText: TextView
    private lateinit var indexText: TextView
    private lateinit var overlay: LinearLayout

    private val items = ArrayList<Scanner.Found>()
    private var index = 0
    private var playing: AnimatedImageDrawable? = null
    private val byteCache = HashMap<Int, ByteArray>()
    private var overlayVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monet.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = 0xFF000000.toInt()
        window.navigationBarColor = 0xFF000000.toInt()

        load(intent)
        if (items.isEmpty()) {
            finish()
            return
        }

        val rootView = FrameLayout(this)
        rootView.setBackgroundColor(0xFF0B0B10.toInt())

        imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        rootView.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 顶部信息
        overlay = LinearLayout(this)
        overlay.orientation = LinearLayout.VERTICAL
        overlay.setPadding(dp(16), dp(18), dp(16), dp(10))

        indexText = TextView(this)
        indexText.setTextColor(0xFFB9B4C7.toInt())
        indexText.textSize = 12f
        indexText.typeface = Typeface.MONOSPACE
        overlay.addView(indexText)

        infoText = TextView(this)
        infoText.setTextColor(0xFFFFFFFF.toInt())
        infoText.textSize = 14f
        infoText.setPadding(0, dp(4), 0, 0)
        overlay.addView(infoText)

        rootView.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))

        // 底部操作
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        bottom.setPadding(dp(16), dp(10), dp(16), dp(26))

        val export = fab("导出这张", true) { exportCurrent() }
        val close = fab("关闭", false) { finish() }
        bottom.addView(export, wrapFab())
        bottom.addView(close, wrapFab())
        rootView.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // 手势：左右滑 = 上一张/下一张；单击 = 显示/隐藏信息层
        imageView.setOnTouchListener { _, event -> handleTouch(event) }

        setContentView(rootView)
        index = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)
        show()
    }

    private fun load(intent: android.content.Intent) {
        val paths = intent.getStringArrayListExtra("paths") ?: arrayListOf()
        val packNames = intent.getStringArrayListExtra("packNames") ?: arrayListOf()
        val packIds = intent.getStringArrayListExtra("packIds") ?: arrayListOf()
        val formats = intent.getStringArrayListExtra("formats") ?: arrayListOf()
        val sources = intent.getStringArrayListExtra("sources") ?: arrayListOf()
        val categories = intent.getStringArrayListExtra("categories") ?: arrayListOf()
        val sizes = intent.getLongArrayExtra("sizes") ?: LongArray(0)
        val mtimes = intent.getLongArrayExtra("mtimes") ?: LongArray(0)
        val obf = intent.getBooleanArrayExtra("obf") ?: BooleanArray(0)
        for (i in paths.indices) {
            items.add(
                Scanner.Found(
                    paths[i],
                    if (i < sizes.size) sizes[i] else 0L,
                    if (i < mtimes.size) mtimes[i] else 0L,
                    formats.getOrElse(i) { "GIF" },
                    i < obf.size && obf[i],
                    packIds.getOrElse(i) { "" },
                    packNames.getOrElse(i) { "" },
                    sources.getOrElse(i) { "" },
                    categories.getOrElse(i) { "" }
                )
            )
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dt = System.currentTimeMillis() - downTime
                if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                    if (dx < 0) next(1) else next(-1)
                } else if (dt < 300 && Math.abs(dx) < dp(24)) {
                    if (event.x < imageView.width * 0.3f) next(-1)
                    else if (event.x > imageView.width * 0.7f) next(1)
                    else toggleOverlay()
                }
            }
        }
        return true
    }

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        overlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
    }

    private fun next(delta: Int) {
        val target = index + delta
        if (target < 0 || target >= items.size) {
            Toast.makeText(this, if (delta > 0) "已经是最后一张" else "已经是第一张", Toast.LENGTH_SHORT).show()
            return
        }
        index = target
        show()
    }

    private fun show() {
        val item = items[index]
        indexText.text = (index + 1).toString() + " / " + items.size
        infoText.text = "[" + item.category + "] " + item.packName + " · " + item.format + " · " + item.sizeText

        val cached = byteCache[index]
        if (cached != null) {
            render(cached)
            preload(index + 1)
            return
        }
        imageView.setImageDrawable(null)
        Thread {
            val bytes = read(item)
            if (bytes != null) {
                byteCache[index] = bytes
                main.post {
                    if (items.getOrNull(index) == item) render(bytes)
                }
            } else {
                main.post { infoText.text = "读取失败：" + item.fileName }
            }
            preload(index + 1)
            preload(index - 1)
        }.start()
    }

    private fun preload(target: Int) {
        if (target < 0 || target >= items.size || byteCache.containsKey(target)) return
        val bytes = read(items[target]) ?: return
        byteCache[target] = bytes
    }

    private fun read(item: Scanner.Found): ByteArray? {
        val svc = Shell.ensureService() ?: return null
        val bytes = Scanner.readAll(svc, item.path) ?: return null
        if (item.obfuscated) Scanner.decodeHeader(bytes)
        return bytes
    }

    private fun render(bytes: ByteArray) {
        try {
            playing?.stop()
        } catch (ignored: Throwable) {
        }
        playing = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val drawable: Drawable = ImageDecoderCompat.decode(this, bytes)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                    playing = drawable
                }
                imageView.setImageDrawable(drawable)
                return
            } catch (t: Throwable) {
            }
        }
        imageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun exportCurrent() {
        val item = items[index]
        Toast.makeText(this, "开始导出…", Toast.LENGTH_SHORT).show()
        Thread {
            val svc = Shell.ensureService()
            if (svc == null) {
                main.post { Toast.makeText(this, "需要 Shizuku 授权", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            val result = Exporter.exportAll(this, svc, listOf(item)) { _, _, _ -> }
            main.post {
                val msg = if (result.ok > 0) "已存到相册 Pictures/" + Exporter.ALBUM
                else if (result.duplicate > 0) "这张之前已经导过了"
                else "导出失败"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun fab(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val view = TextView(this)
        view.text = label
        view.textSize = 13f
        view.gravity = Gravity.CENTER
        view.setTextColor(if (primary) 0xFF1B1B1F.toInt() else 0xFFE8E3F5.toInt())
        view.background = Ui.round(22, if (primary) 0xFFEADDFF.toInt() else 0x33FFFFFF, this)
        view.setPadding(dp(18), dp(12), dp(18), dp(12))
        view.setOnClickListener { onClick() }
        return view
    }

    private fun wrapFab(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(dp(6), 0, dp(6), 0)
        return params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try {
            playing?.stop()
        } catch (ignored: Throwable) {
        }
        super.onDestroy()
    }
}

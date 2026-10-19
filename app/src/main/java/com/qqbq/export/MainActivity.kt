package com.qqbq.export

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val thumbExec = Executors.newSingleThreadExecutor()
    private val thumbCache = LruCache<String, Bitmap>(240)

    private lateinit var statusBar: TextView
    private lateinit var gridView: GridView
    private lateinit var listView: ListView
    private lateinit var contentBox: FrameLayout
    private lateinit var gridAdapter: GridAdapter
    private lateinit var listAdapter: FoundAdapter
    private lateinit var logText: TextView
    private val navButtons = LinkedHashMap<String, Button>()
    private val sourceButtons = LinkedHashMap<Int, Button>()
    private val actionButtons = ArrayList<Button>()

    private val items = ArrayList<Scanner.Found>()
    private val shown = ArrayList<Scanner.Found>()
    private val selected = HashSet<String>()
    private var filterMode = "全部"
    private var columns = 4
    private var gridMode = true
    private var busy = false

    private val biliUrl = "https://space.bilibili.com/1967666609"

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == Shell.REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) writeLog("Shizuku 授权成功")
            else writeLog("Shizuku 授权被拒绝")
            refreshStatus()
        }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monet.init(this)
        Shell.configure(applicationContext)
        gridAdapter = GridAdapter()
        listAdapter = FoundAdapter()
        buildUi()

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (t: Throwable) {
            writeLog("Shizuku 初始化失败：" + t.javaClass.simpleName)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 71)
            }
        }

        refreshStatus()
        refreshSourceButtons()
        refreshNavButtons()
        updateStatusBar()
        maybeShowNotice()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (t: Throwable) {
        }
        thumbExec.shutdownNow()
        super.onDestroy()
    }

    // ================= 布局（PCL 风格） =================

    private fun buildUi() {
        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL
        rootView.setBackgroundColor(Monet.surface)

        // ---- 顶栏 ----
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(14), dp(10), dp(10), dp(8))
        topBar.background = Ui.card(this, 0)

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "QQ图片提取工具"
        title.textSize = 18f
        title.setTextColor(Monet.onSurface)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setOnLongClickListener {
            showGuide(0)
            true
        }
        val subtitle = TextView(this)
        subtitle.text = "作者 星球卑_PHb"
        subtitle.textSize = 10f
        subtitle.setTextColor(Monet.onSurfaceVariant)
        titles.addView(title)
        titles.addView(subtitle)
        topBar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(iconButton("网格") { setGridMode(true) }, wrap(4))
        topBar.addView(iconButton("列表") { setGridMode(false) }, wrap(4))
        topBar.addView(iconButton("设置") { showSettings() }, wrap(4))
        rootView.addView(topBar, wrap())

        // ---- 来源条 ----
        val sourceBar = LinearLayout(this)
        sourceBar.orientation = LinearLayout.HORIZONTAL
        sourceBar.setPadding(dp(10), 0, dp(10), dp(6))
        sourceBar.addView(chip("QQ", Prefs.SOURCE_QQ), chipParams())
        sourceBar.addView(chip("微信", Prefs.SOURCE_WECHAT), chipParams())
        sourceBar.addView(chip("抖音", Prefs.SOURCE_DOUYIN), chipParams())
        sourceBar.addView(chip("小红书", Prefs.SOURCE_XHS), chipParams())
        sourceBar.addView(chip("QQ+微信", Prefs.SOURCE_BOTH), chipParams())
        sourceBar.addView(chip("全部", Prefs.SOURCE_ALL), chipParams())
        val sourceScroll = android.widget.HorizontalScrollView(this)
        sourceScroll.isHorizontalScrollBarEnabled = false
        sourceScroll.addView(sourceBar)
        rootView.addView(sourceScroll, wrap())

        // ---- 主体：左导航 + 内容 ----
        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        body.setPadding(dp(8), 0, dp(8), 0)

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.VERTICAL
        nav.background = Ui.card(this, 18)
        nav.setPadding(dp(4), dp(6), dp(4), dp(6))
        for (mode in listOf("全部", "我保存的", "表情包", "聊天图", "其他", "含系统")) {
            val button = navButton(mode)
            navButtons[mode] = button
            nav.addView(button, navParams())
        }
        val navScroll = ScrollView(this)
        navScroll.isVerticalScrollBarEnabled = false
        navScroll.addView(nav)
        body.addView(navScroll, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT))

        contentBox = FrameLayout(this)
        contentBox.setPadding(dp(6), 0, 0, 0)

        gridView = GridView(this)
        gridView.numColumns = columns
        gridView.horizontalSpacing = dp(6)
        gridView.verticalSpacing = dp(6)
        gridView.setSelector(android.R.color.transparent)
        gridView.isVerticalScrollBarEnabled = false
        gridView.adapter = gridAdapter
        gridView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        gridView.setOnItemLongClickListener { _, _, position, _ ->
            openViewer(position)
            true
        }
        contentBox.addView(gridView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setSelector(android.R.color.transparent)
        listView.isVerticalScrollBarEnabled = false
        listView.adapter = listAdapter
        listView.visibility = View.GONE
        listView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            openViewer(position)
            true
        }
        contentBox.addView(listView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        body.addView(contentBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        rootView.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(4) })

        // ---- 底部状态栏 ----
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(dp(12), dp(6), dp(10), dp(8))

        statusBar = TextView(this)
        statusBar.textSize = 10.5f
        statusBar.setTextColor(Monet.onSurfaceVariant)
        bottom.addView(statusBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sizeLabel = TextView(this)
        sizeLabel.text = "大小"
        sizeLabel.textSize = 10f
        sizeLabel.setTextColor(Monet.onSurfaceVariant)
        bottom.addView(sizeLabel, wrap(4))

        val seek = SeekBar(this)
        seek.max = 6
        seek.progress = columns - 2
        seek.layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                columns = progress + 2
                gridView.numColumns = columns
                gridAdapter.notifyDataSetChanged()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        bottom.addView(seek, wrap(2))

        bottom.addView(iconButton("扫描") { onScan() }, wrap(4))
        bottom.addView(iconButton("导出") { onExport() }, wrap(4))
        rootView.addView(bottom, wrap())

        setContentView(rootView)
    }

    private fun setGridMode(grid: Boolean) {
        gridMode = grid
        gridView.visibility = if (grid) View.VISIBLE else View.GONE
        listView.visibility = if (grid) View.GONE else View.VISIBLE
        writeLog(if (grid) "切换到宫格模式" else "切换到列表模式")
    }

    private fun navButton(mode: String): Button {
        val button = Button(this)
        button.text = mode
        button.isAllCaps = false
        button.textSize = 10.5f
        button.stateListAnimator = null
        button.minHeight = 0
        button.minimumHeight = dp(38)
        button.setPadding(0, dp(6), 0, dp(6))
        button.setOnClickListener {
            filterMode = mode
            refreshNavButtons()
            applyFilter()
        }
        actionButtons.add(button)
        return button
    }

    private fun chip(label: String, value: Int): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.textSize = 11f
        button.stateListAnimator = null
        button.minHeight = 0
        button.minimumHeight = dp(32)
        button.setPadding(dp(10), dp(4), dp(10), dp(4))
        button.setOnClickListener { setSource(value) }
        sourceButtons[value] = button
        actionButtons.add(button)
        return button
    }

    private fun iconButton(label: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.textSize = 11f
        button.setTextColor(Monet.onSurface)
        button.background = Ui.tonal(this)
        button.stateListAnimator = null
        button.minHeight = 0
        button.minimumHeight = dp(32)
        button.setPadding(dp(8), dp(4), dp(8), dp(4))
        button.setOnClickListener { onClick() }
        actionButtons.add(button)
        return button
    }

    private fun navParams(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, dp(2), 0, dp(2))
        return params
    }

    private fun chipParams(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(dp(3), 0, dp(3), 0)
        return params
    }

    private fun wrap(horizontalDp: Int = 0): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(horizontalDp, dp(2), horizontalDp, dp(2))
        return params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ================= 状态与筛选 =================

    private fun refreshStatus() {
        val alive = Shell.binderAlive()
        val granted = alive && Shell.hasPermission()
        ShizukuState.text = when {
            !alive -> "Shizuku 未运行"
            !granted -> "Shizuku 待授权"
            else -> "Shizuku 已授权"
        }
        updateStatusBar()
    }

    private fun updateStatusBar() {
        var mine = 0
        var pack = 0
        var chat = 0
        var sys = 0
        var other = 0
        for (item in items) {
            when (item.category) {
                "我保存的" -> mine++
                "表情包" -> pack++
                "聊天图" -> chat++
                "系统资源" -> sys++
                else -> other++
            }
        }
        statusBar.text = ShizukuState.text + " · 已选 " + selected.size + " / 显示 " + shown.size +
            "（我保存 " + mine + " 表情包 " + pack + " 聊天图 " + chat + " 其他 " + other + " 系统 " + sys + "）"
        for ((mode, button) in navButtons) {
            val count = when (mode) {
                "全部" -> mine + pack + chat + other
                "我保存的" -> mine
                "表情包" -> pack
                "聊天图" -> chat
                "其他" -> other
                "含系统" -> mine + pack + chat + other + sys
                else -> 0
            }
            button.text = mode + " " + count
        }
    }

    private fun refreshNavButtons() {
        for ((mode, button) in navButtons) {
            val active = mode == filterMode
            button.setTextColor(if (active) Monet.onPrimary else Monet.onSurface)
            button.background = if (active) Ui.filled(this) else Ui.round(16, Color.TRANSPARENT, this)
        }
    }

    private fun refreshSourceButtons() {
        val source = Prefs.source(this)
        for ((value, button) in sourceButtons) {
            val active = value == source
            button.setTextColor(if (active) Monet.onPrimary else Monet.onSurface)
            button.background = if (active) Ui.filled(this) else Ui.tonal(this)
        }
    }

    private fun applyFilter() {
        shown.clear()
        for (item in items) {
            val keep = when (filterMode) {
                "含系统" -> true
                "全部" -> item.category != "系统资源"
                else -> item.category == filterMode
            }
            if (keep) shown.add(item)
        }
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        updateStatusBar()
    }

    private fun setSource(value: Int) {
        Prefs.setSource(this, value)
        refreshSourceButtons()
        writeLog("扫描来源：" + sourceName(value))
        if (value != Prefs.SOURCE_QQ && value != Prefs.SOURCE_BOTH) maybeExplainOtherApps(value)
    }

    private fun sourceName(value: Int): String = when (value) {
        Prefs.SOURCE_WECHAT -> "微信"
        Prefs.SOURCE_DOUYIN -> "抖音"
        Prefs.SOURCE_XHS -> "小红书"
        Prefs.SOURCE_BOTH -> "QQ + 微信"
        Prefs.SOURCE_ALL -> "全部"
        else -> "QQ"
    }

    // ================= 选择 =================

    private fun toggle(position: Int) {
        if (position !in shown.indices) return
        val item = shown[position]
        if (!selected.add(item.path)) selected.remove(item.path)
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        updateStatusBar()
    }

    private fun selectAll(value: Boolean) {
        selected.clear()
        if (value) for (item in shown) selected.add(item.path)
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        updateStatusBar()
    }

    private fun openViewer(position: Int) {
        if (shown.isEmpty()) {
            toast("先扫描")
            return
        }
        val intent = Intent(this, ViewerActivity::class.java)
        intent.putStringArrayListExtra("paths", ArrayList(shown.map { it.path }))
        intent.putStringArrayListExtra("packNames", ArrayList(shown.map { it.packName }))
        intent.putStringArrayListExtra("packIds", ArrayList(shown.map { it.packId }))
        intent.putStringArrayListExtra("formats", ArrayList(shown.map { it.format }))
        intent.putStringArrayListExtra("sources", ArrayList(shown.map { it.source }))
        intent.putStringArrayListExtra("categories", ArrayList(shown.map { it.category }))
        intent.putExtra("sizes", shown.map { it.size }.toLongArray())
        intent.putExtra("mtimes", shown.map { it.mtime }.toLongArray())
        intent.putExtra("obf", shown.map { it.obfuscated }.toBooleanArray())
        intent.putExtra("index", position)
        startActivity(intent)
    }

    // ================= 设置页 =================

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(10), dp(16), dp(10))

        val hint = TextView(this)
        hint.textSize = 11f
        hint.setTextColor(Monet.onSurfaceVariant)
        hint.text = "扫描来源与目录、诊断、使用引导都在这里。"
        box.addView(hint, wrap())

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(iconButton("授权") { onAuth() }, chipParams())
        row1.addView(iconButton("诊断") { onDiag() }, chipParams())
        row1.addView(iconButton("目录") { onEditRoots() }, chipParams())
        box.addView(row1, wrap())

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(iconButton("导出清单") { onInventory() }, chipParams())
        row2.addView(iconButton("使用引导") { showGuide(0) }, chipParams())
        row2.addView(iconButton("全选") { selectAll(true) }, chipParams())
        row2.addView(iconButton("全不选") { selectAll(false) }, chipParams())
        box.addView(row2, wrap())

        val chk = CheckBox(this)
        chk.text = "同时导出静态表情"
        chk.textSize = 12f
        chk.setTextColor(Monet.onSurface)
        chk.buttonTintList = ColorStateList.valueOf(Monet.primary)
        chk.isChecked = Prefs.wantStatic(this)
        chk.setOnCheckedChangeListener { _, value -> Prefs.setWantStatic(this, value) }
        box.addView(chk, wrap())

        val log = TextView(this)
        log.textSize = 9.5f
        log.setTextColor(Monet.onSurfaceVariant)
        log.typeface = Typeface.MONOSPACE
        log.text = if (logText.text.isNullOrEmpty()) "（暂无日志）" else logText.text
        logTextRef = log
        val scroll = ScrollView(this)
        scroll.addView(log)
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        val bili = TextView(this)
        bili.text = "B 站主页：space.bilibili.com/1967666609（点此处打开）"
        bili.textSize = 11f
        bili.setTextColor(Monet.primary)
        bili.setPadding(0, dp(6), 0, 0)
        bili.setOnClickListener { openUrl(biliUrl) }
        box.addView(bili, wrap())

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(box)
            .setPositiveButton("关闭", null)
            .show()
    }

    private var logTextRef: TextView? = null

    private fun writeLog(line: String) {
        main.post {
            val current = if (logText.text.isNullOrEmpty()) "" else logText.text.toString()
            val merged = if (current.isEmpty()) line else current + "\n" + line
            val lines = merged.split("\n")
            logText.text = if (lines.size > 200) lines.subList(lines.size - 200, lines.size).joinToString("\n") else merged
            logTextRef?.text = logText.text
        }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ================= 动作 =================

    private fun onAuth() {
        if (!Shell.binderAlive()) {
            writeLog("没检测到 Shizuku，先安装并启动它")
            openShizuku()
            return
        }
        if (Shell.hasPermission()) {
            worker("绑定") {
                val svc = Shell.ensureService()
                if (svc == null) writeLog("绑定失败")
                else {
                    Prefs.setFlag(this, "ok_auth", true)
                    writeLog("绑定成功：" + svc.whoami())
                }
            }
        } else {
            writeLog("向 Shizuku 申请权限…")
            Shell.requestPermission()
        }
    }

    private fun onScan() {
        worker("扫描") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("拿不到 shell 服务，先授权")
                toast("先点「设置 → 授权」")
                return@worker
            }
            val source = Prefs.source(this)
            val roots = Prefs.rootsFor(this, source)
            val wantStatic = Prefs.wantStatic(this) || source != Prefs.SOURCE_QQ
            writeLog("来源 " + sourceName(source) + "，目录 " + roots.size + " 个")

            val found = ArrayList<Scanner.Found>()
            var scanned = 0
            var skipped = 0
            for (root in roots) {
                val files = Scanner.listFiles(svc, root) ?: continue
                if (files.isEmpty()) continue
                writeLog("· " + root + " → " + files.size)
                val packNames = HashMap<String, String>()
                for (path in files) {
                    scanned++
                    val file = File(path)
                    val packDir = file.parent ?: continue
                    val packId = File(packDir).name
                    val packName = packNames.getOrPut(packDir) {
                        Scanner.readPackName(svc, packDir, packId) ?: ("表情包" + packId)
                    }
                    val item = Scanner.classify(svc, path, wantStatic, packId, packName, sourceName(source))
                    if (item == null) skipped++ else found.add(item)
                }
                writeLog("  └ 累计 " + found.size)
            }

            val unique = LinkedHashMap<String, Scanner.Found>()
            for (item in found) unique[item.path] = item
            found.clear()
            found.addAll(unique.values)
            found.sortByDescending { it.mtime }

            main.post {
                items.clear()
                items.addAll(found)
                selected.clear()
                applyFilter()
                if (items.isNotEmpty()) Prefs.setFlag(this, "ok_scan", true)
            }
            writeLog("扫描完成：检查 " + scanned + "，命中 " + found.size + "，跳过 " + skipped)
            main.post {
                try {
                    (application as App).publish("qqbq-scan.txt", logText.text.toString())
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun onExport() {
        if (items.isEmpty()) {
            toast("先扫描")
            return
        }
        val chosen = ArrayList<Scanner.Found>()
        for (item in items) if (selected.contains(item.path)) chosen.add(item)
        if (chosen.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没勾选")
                .setMessage("要导出当前显示的 " + shown.size + " 个吗？")
                .setPositiveButton("全部导出") { _, _ -> doExport(ArrayList(shown)) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doExport(chosen)
    }

    private fun doExport(snapshot: List<Scanner.Found>) {
        worker("导出 " + snapshot.size + " 个") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("拿不到 shell 服务")
                return@worker
            }
            val result = Exporter.exportAll(this, svc, snapshot) { index, item, ok ->
                if (index % 10 == 0 || !ok) {
                    writeLog("  " + index + "/" + snapshot.size + " " + (if (ok) "OK " else "跳过 ") + item.label)
                }
            }
            writeLog("完成：成功 " + result.ok + "，重复 " + result.duplicate + "，失败 " + result.failed)
            if (result.ok > 0) {
                Prefs.setFlag(this, "ok_export", true)
                toast("已存到相册 Pictures/" + Exporter.ALBUM)
            }
        }
    }

    private fun onDiag() {
        worker("诊断") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("需要授权")
                return@worker
            }
            val sb = StringBuilder()
            sb.append("=== 身份 ===\n").append(svc.exec("id")).append("\n\n")
            for (root in Prefs.DEFAULT_ROOTS + Prefs.WECHAT_ROOTS + Prefs.DOUYIN_ROOTS + Prefs.XHS_ROOTS) {
                val exists = svc.exec("if [ -d '" + root + "' ]; then echo OK; else echo MISSING; fi").trim()
                val files = svc.exec("find '" + root + "' -type f 2>/dev/null | wc -l").trim()
                sb.append(root).append("  ").append(exists).append("  ").append(files).append("\n")
            }
            val report = sb.toString()
            try {
                (application as App).publish("qqbq-diag.txt", report)
            } catch (ignored: Throwable) {
            }
            writeLog("诊断已写入 Download/qqbq-diag.txt")
            main.post { showTextDialog("诊断结果", report) }
        }
    }

    private fun onEditRoots() {
        val edit = EditText(this)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        edit.setText(Prefs.roots(this).joinToString("\n"))
        edit.setMinLines(6)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        box.addView(edit, wrap())
        AlertDialog.Builder(this)
            .setTitle("QQ 扫描目录（一行一个）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val list = edit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                Prefs.setRoots(this, if (list.isEmpty()) Prefs.DEFAULT_ROOTS else list)
                writeLog("已保存 " + Prefs.roots(this).size + " 个目录")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.setRoots(this, Prefs.DEFAULT_ROOTS)
                writeLog("已恢复默认目录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onInventory() {
        worker("导出清单") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("需要授权")
                return@worker
            }
            val roots = ArrayList<String>()
            roots.addAll(Prefs.rootsFor(this, Prefs.source(this)))
            roots.add("/sdcard/Android/data/com.tencent.mobileqq")
            roots.add("/sdcard/Android/data/com.tencent.mm")
            roots.add("/sdcard/Android/data/com.ss.android.ugc.aweme")
            roots.add("/sdcard/Android/data/com.xingin.xhs")
            val sb = StringBuilder()
            sb.append("kind,root_or_dir,path,size,mtime,hex32,typeRaw,typeDec\n")
            var rows = 0
            for (root in roots.distinct()) {
                val files = Scanner.listFiles(svc, root) ?: continue
                for (path in files) {
                    if (rows >= 120000) break
                    val info = Scanner.probe(svc, path) ?: continue
                    sb.append("file,").append(csv(root)).append(",").append(csv(path)).append(",")
                        .append(info.size).append(",").append(info.mtime).append(",")
                        .append(info.hex).append(",").append(csv(info.rawType)).append(",")
                        .append(csv(info.decodedType)).append("\n")
                    rows++
                }
            }
            (application as App).publish("qqbq-inventory.csv", sb.toString())
            writeLog("清单完成：$rows 行")
        }
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun openShizuku() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent == null) writeLog("本机没装 Shizuku") else startActivity(intent)
        } catch (t: Throwable) {
            writeLog("打开 Shizuku 失败：" + t.message)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (t: Throwable) {
            writeLog("打不开链接")
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val view = TextView(this)
        view.typeface = Typeface.MONOSPACE
        view.textSize = 10f
        view.setTextIsSelectable(true)
        view.text = text
        val scroll = ScrollView(this)
        scroll.setPadding(dp(16), dp(8), dp(16), dp(8))
        scroll.addView(view)
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭", null).show()
    }

    // ================= 引导 / 须知 =================

    private fun maybeShowNotice() {
        val sp = getSharedPreferences("qqbq", MODE_PRIVATE)
        if (sp.getBoolean("notice_shown_v1", false)) return
        val text = StringBuilder()
            .append("1. 需要 Shizuku（无线调试授权）才能读取 QQ / 微信 / 抖音 / 小红书 的缓存。\n\n")
            .append("2. 扫描的是各 App 缓存目录，**可能扫到一些你不需要的文件**；")
            .append("结果已按「我保存的 / 表情包 / 聊天图 / 其他 / 系统资源」分类，系统资源默认隐藏。\n\n")
            .append("3. 全程只读：不修改、不删除任何 App 数据；不联网、不上传。\n\n")
            .append("4. 导出位置：相册 Pictures/QQ表情/\n\n")
            .append("5. 收藏类表情存在应用内部存储，没有 root 读不到，需要先在 App 里使用或保存一次。\n\n")
            .append("作者：星球卑_PHb　B站 UID 1967666609（欢迎来主页坐坐）")
            .toString()
        AlertDialog.Builder(this)
            .setTitle("使用须知")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("我已了解") { _, _ ->
                sp.edit().putBoolean("notice_shown_v1", true).apply()
                if (!Prefs.flag(this, "guide_shown_v1")) {
                    Prefs.setFlag(this, "guide_shown_v1", true)
                    showGuide(0)
                }
            }
            .show()
    }

    private fun showGuide(step: Int) {
        val steps = arrayOf(
            "① 准备 Shizuku\n\n启动 Shizuku（开发者选项 → 无线调试 → 配对 → 启动），回到本 App 点「设置」→「授权」。",
            "② 选来源\n\n顶部一排 chip：QQ / 微信 / 抖音 / 小红书 / QQ+微信 / 全部。只用一个 App 时建议单选。",
            "③ 扫描\n\n点右下「扫描」。文件多会慢，日志在「设置」里能看到。",
            "④ 宫格浏览\n\n左栏是分类（全部 / 我保存的 / 表情包 / 聊天图 / 其他 / 含系统）。底部「大小」滑块可调宫格列数。",
            "⑤ 预览与导出\n\n点一格 = 勾选；长按 = 全屏预览（左右滑动、页内可导出）；勾完点「导出」到相册。"
        )
        val index = step.coerceIn(0, steps.size - 1)
        val builder = AlertDialog.Builder(this)
            .setTitle("使用引导 " + (index + 1) + " / " + steps.size)
            .setMessage(steps[index])
            .setNeutralButton("关闭", null)
        if (index > 0) builder.setNegativeButton("上一步") { _, _ -> showGuide(index - 1) }
        if (index < steps.size - 1) builder.setPositiveButton("下一步") { _, _ -> showGuide(index + 1) }
        else builder.setPositiveButton("开始使用") { _, _ -> }
        builder.show()
    }

    private fun maybeExplainOtherApps(value: Int) {
        when (value) {
            Prefs.SOURCE_DOUYIN -> explainOnce("douyin_explained_v1", "关于抖音",
                "抖音的图片/表情缓存在 cache 目录，扫到的可能很多。\n\n" +
                    "结果已分类，系统小图标会归到「系统资源」默认隐藏。")
            Prefs.SOURCE_XHS -> explainOnce("xhs_explained_v1", "关于小红书",
                "小红书图片缓存在外置目录。\n\n结果已分类，想找自己的图点左栏「我保存的」。")
            else -> explainOnce("wechat_explained_v1", "关于微信",
                "微信的自定义表情在应用内部存储（/data/data/com.tencent.mm），没有 root 读不到。\n\n" +
                    "能拿到的是微信外置目录里的聊天图和你手动保存的图片（Pictures/WeiXin）。\n\n" +
                    "要导出微信表情：先发给文件传输助手 → 长按保存到手机 → 回来选「微信」扫描。")
        }
    }

    private fun explainOnce(key: String, title: String, body: String) {
        val sp = getSharedPreferences("qqbq", MODE_PRIVATE)
        if (sp.getBoolean(key, false)) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("知道了") { _, _ -> sp.edit().putBoolean(key, true).apply() }
            .show()
    }

    // ================= 线程 =================

    private fun worker(tag: String, block: () -> Unit) {
        if (busy) {
            toast("上一个任务还没跑完")
            return
        }
        busy = true
        setActionsEnabled(false)
        writeLog("== " + tag + " ==")
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                writeLog("出错：" + t.javaClass.simpleName + " " + (t.message ?: ""))
            } finally {
                main.post {
                    busy = false
                    setActionsEnabled(true)
                    refreshStatus()
                }
            }
        }.start()
    }

    private fun setActionsEnabled(enabled: Boolean) {
        for (button in actionButtons) button.isEnabled = enabled
    }

    // ================= 适配器 =================

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val box: LinearLayout
            val image: ImageView
            val label: TextView
            if (convertView == null) {
                box = LinearLayout(this@MainActivity)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(dp(2), dp(2), dp(2), dp(2))
                image = ImageView(this@MainActivity)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                box.addView(image, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
                label = TextView(this@MainActivity)
                label.textSize = 9f
                label.maxLines = 1
                label.setTextColor(Monet.onSurfaceVariant)
                label.gravity = Gravity.CENTER
                box.addView(label, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.tag = arrayOf(image, label)
                box.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                box = convertView as LinearLayout
                val pair = box.tag as Array<*>
                image = pair[0] as ImageView
                label = pair[1] as TextView
            }
            val item = shown[position]
            val isSelected = selected.contains(item.path)
            box.background = if (isSelected) Ui.round(16, Monet.softSecondary, this@MainActivity, 2, Monet.primary)
            else Ui.round(16, Color.TRANSPARENT, this@MainActivity)
            label.text = if (isSelected) "✓ " + item.format else item.format
            label.setTextColor(if (isSelected) Monet.primary else Monet.onSurfaceVariant)
            bindThumb(image, item)
            return box
        }
    }

    private inner class RowHolder(
        val row: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val sub: TextView
    )

    private inner class FoundAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holder: RowHolder
            if (convertView == null) {
                row = LinearLayout(this@MainActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(10), dp(8), dp(8), dp(8))

                val thumb = ImageView(this@MainActivity)
                thumb.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(thumb)

                val texts = LinearLayout(this@MainActivity)
                texts.orientation = LinearLayout.VERTICAL
                texts.setPadding(dp(10), 0, dp(6), 0)
                val title = TextView(this@MainActivity)
                title.textSize = 13f
                title.setTextColor(Monet.onSurface)
                val sub = TextView(this@MainActivity)
                sub.textSize = 10f
                sub.setTextColor(Monet.onSurfaceVariant)
                texts.addView(title)
                texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                holder = RowHolder(row, thumb, title, sub)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as RowHolder
            }
            val item = shown[position]
            val isSelected = selected.contains(item.path)
            holder.row.background = Ui.row(this@MainActivity, isSelected)
            holder.title.text = "[" + item.category + "] " + item.label
            holder.sub.text = item.source + " · " + item.format + " · " + item.sizeText
            bindThumb(holder.thumb, item)
            return row
        }
    }

    private fun bindThumb(view: ImageView, item: Scanner.Found) {
        val cached = thumbCache.get(item.path)
        if (cached != null) {
            view.tag = item.path
            view.setImageBitmap(cached)
            return
        }
        view.tag = item.path
        view.setImageBitmap(null)
        thumbExec.submit {
            try {
                val svc = Shell.ensureService() ?: return@submit
                val bytes = Scanner.readAll(svc, item.path, 6 * 1024 * 1024) ?: return@submit
                if (item.obfuscated) Scanner.decodeHeader(bytes)
                val options = BitmapFactory.Options()
                options.inSampleSize = 3
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@submit
                thumbCache.put(item.path, bitmap)
                main.post { if (view.tag == item.path) view.setImageBitmap(bitmap) }
            } catch (t: Throwable) {
            }
        }
    }
}

/** 用一个简单单例把 Shizuku 状态带到状态栏，避免多处重复计算。 */
object ShizukuState {
    var text: String = "Shizuku 未运行"
}

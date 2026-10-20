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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val thumbExec = Executors.newSingleThreadExecutor()
    private val thumbCache = LruCache<String, Bitmap>(300)

    private lateinit var statusBar: TextView
    private lateinit var gridView: GridView
    private lateinit var listView: ListView
    private lateinit var contentBox: FrameLayout
    private lateinit var gridAdapter: GridAdapter
    private lateinit var listAdapter: FoundAdapter
    private lateinit var exportButton: TextView
    private val newChips = LinkedHashMap<Int, TextView>()
    private val categoryChips = LinkedHashMap<String, TextView>()
    private val viewChips = LinkedHashMap<Int, TextView>()
    private val actionViews = ArrayList<View>()

    private val items = ArrayList<Scanner.Found>()
    private val shown = ArrayList<Scanner.Found>()
    private val selected = HashSet<String>()
    private val logBuffer = StringBuilder()

    private var filterCategory = "全部"
    private var filterNew = 0
    private var viewMode = 3
    private var busy = false

    private val biliUrl = "https://space.bilibili.com/1967666609"

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == Shell.REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) writeLog("授权成功")
            else writeLog("授权被拒绝")
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
            writeLog("Shizuku 初始化失败")
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 71)
            }
        }

        refreshStatus()
        refreshChips()
        setViewMode(viewMode)
        applyFilter()
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

    // ================= 界面骨架 =================

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Monet.surface)

        // —— 头部：标题 + 副标题 + 设置 ——
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), dp(18), dp(10), dp(10))

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "QQ图片提取工具"
        title.textSize = 21f
        title.setTextColor(Monet.onSurface)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setOnLongClickListener {
            showGuide(0)
            true
        }
        val sub = TextView(this)
        sub.text = "作者 星球卑_PHb"
        sub.textSize = 10.5f
        sub.setTextColor(Monet.onSurfaceVariant)
        titles.addView(title)
        titles.addView(sub)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(plainButton("⋯") { showSettings() }, LinearLayout.LayoutParams(dp(40), dp(36)))
        root.addView(header, wrap())

        // —— 状态一行 ——
        statusBar = TextView(this)
        statusBar.textSize = 11f
        statusBar.setTextColor(Monet.onSurfaceVariant)
        statusBar.setPadding(dp(20), 0, dp(20), dp(10))
        root.addView(statusBar, wrap())

        // —— 第一排：新增 / 已看过 ——
        root.addView(chipRow { row ->
            addChip(row, newChips, 0, "全部") { setFilterNew(0) }
            addChip(row, newChips, 1, "新增") { setFilterNew(1) }
            addChip(row, newChips, 2, "已看过") { setFilterNew(2) }
        }, chipRowParams())

        // —— 第二排：分类 ——
        root.addView(chipRow { row ->
            val cats = listOf("全部", "我保存的", "表情包", "聊天图", "其他")
            for (cat in cats) {
                addChip(row, categoryChips, cat, cat) { setFilterCategory(cat) }
            }
        }, chipRowParams())

        // —— 第三排：浏览模式 ——
        root.addView(chipRow { row ->
            addViewChip(row, 0, "列表")
            addViewChip(row, 1, "四宫格")
            addViewChip(row, 2, "九宫格")
            addViewChip(row, 3, "多宫格")
        }, chipRowParams())

        // —— 内容区 ——
        contentBox = FrameLayout(this)
        contentBox.setPadding(dp(12), dp(4), dp(12), 0)

        gridView = GridView(this)
        gridView.numColumns = 4
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
        root.addView(contentBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // —— 底部：扫描 + 导出 ——
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(dp(16), dp(10), dp(16), dp(16))

        val scan = pillButton("扫描", true) { onScan() }
        bottom.addView(scan, LinearLayout.LayoutParams(0, dp(46), 1f).also { it.rightMargin = dp(8) })

        exportButton = pillButton("导出选中", false) { onExport() }
        bottom.addView(exportButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(bottom, wrap())

        setContentView(root)
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chipRowParams(): LinearLayout.LayoutParams {
        val params = wrap()
        params.setMargins(dp(16), 0, dp(16), dp(8))
        return params
    }

    private fun chipRow(add: (LinearLayout) -> Unit): HorizontalScrollView {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        add(row)
        scroll.addView(row)
        return scroll
    }

    private fun <K> addChip(
        row: LinearLayout,
        map: LinkedHashMap<K, TextView>,
        key: K,
        label: String,
        onClick: () -> Unit
    ) {
        val chip = TextView(this)
        chip.text = label
        chip.textSize = 12f
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(14), dp(7), dp(14), dp(7))
        chip.background = Ui.chip(this, false)
        chip.setTextColor(Monet.onSurfaceVariant)
        chip.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.rightMargin = dp(8)
        row.addView(chip, params)
        map[key] = chip
        actionViews.add(chip)
    }

    private fun addViewChip(row: LinearLayout, mode: Int, label: String) {
        addChip(row, viewChips, mode, label) { setViewMode(mode) }
    }

    private fun plainButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Monet.onSurfaceVariant)
        tv.background = Ui.round(14, Color.TRANSPARENT, this)
        tv.setOnClickListener { onClick() }
        actionViews.add(tv)
        return tv
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.setTextColor(if (primary) Monet.onPrimaryFill else Monet.primary)
        tv.background = if (primary) Ui.filled(this) else Ui.soft(this)
        tv.setOnClickListener { onClick() }
        actionViews.add(tv)
        return tv
    }

    // ================= 状态 / 筛选 =================

    private fun refreshStatus() {
        val alive = Shell.binderAlive()
        val granted = alive && Shell.hasPermission()
        ShizukuState.text = when {
            !alive -> "Shizuku 未运行"
            !granted -> "Shizuku 待授权"
            else -> "已连接"
        }
        updateStatusBar()
    }

    private fun updateStatusBar() {
        var fresh = 0
        for (item in items) if (item.isNew) fresh++
        statusBar.text = ShizukuState.text + " · 共 " + items.size + " · 新增 " + fresh + " · 已选 " + selected.size

        newChips[0]?.text = "全部 " + items.size
        newChips[1]?.text = "新增 " + fresh
        newChips[2]?.text = "已看过 " + (items.size - fresh)

        exportButton.text = if (selected.isEmpty()) "导出选中" else "导出选中 " + selected.size
    }

    private fun refreshChips() {
        for ((key, chip) in newChips) {
            val active = key == filterNew
            chip.background = Ui.chip(this, active)
            chip.setTextColor(if (active) Monet.primary else Monet.onSurfaceVariant)
        }
        for ((key, chip) in categoryChips) {
            val active = key == filterCategory
            chip.background = Ui.chip(this, active)
            chip.setTextColor(if (active) Monet.primary else Monet.onSurfaceVariant)
        }
        for ((key, chip) in viewChips) {
            val active = key == viewMode
            chip.background = Ui.chip(this, active)
            chip.setTextColor(if (active) Monet.primary else Monet.onSurfaceVariant)
        }
    }

    private fun setFilterNew(value: Int) {
        filterNew = value
        refreshChips()
        applyFilter()
    }

    private fun setFilterCategory(cat: String) {
        filterCategory = cat
        refreshChips()
        applyFilter()
    }

    private fun setViewMode(mode: Int) {
        viewMode = mode
        refreshChips()
        when (mode) {
            0 -> {
                gridView.visibility = View.GONE
                listView.visibility = View.VISIBLE
            }
            else -> {
                gridView.visibility = View.VISIBLE
                listView.visibility = View.GONE
                gridView.numColumns = mode + 1
                gridAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun applyFilter() {
        shown.clear()
        for (item in items) {
            val catOk = when (filterCategory) {
                "全部" -> item.category != "系统资源"
                else -> item.category == filterCategory
            }
            if (!catOk) continue
            val newOk = when (filterNew) {
                1 -> item.isNew
                2 -> !item.isNew
                else -> true
            }
            if (newOk) shown.add(item)
        }
        gridAdapter.notifyDataSetChanged()
        listAdapter.notifyDataSetChanged()
        updateStatusBar()
    }

    private fun sourceName(value: Int): String = when (value) {
        Prefs.SOURCE_WECHAT -> "微信"
        Prefs.SOURCE_DOUYIN -> "抖音"
        Prefs.SOURCE_XHS -> "小红书"
        Prefs.SOURCE_BOTH -> "QQ + 微信"
        Prefs.SOURCE_ALL -> "全部"
        else -> "QQ"
    }

    private fun setSource(value: Int) {
        Prefs.setSource(this, value)
        writeLog("扫描来源：" + sourceName(value))
        if (value != Prefs.SOURCE_QQ && value != Prefs.SOURCE_BOTH) maybeExplainOtherApps(value)
    }

    // ================= 选择 / 预览 =================

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

    // ================= 设置 =================

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(8), dp(18), dp(8))

        val sourceLabel = TextView(this)
        sourceLabel.text = "扫描来源"
        sourceLabel.textSize = 11f
        sourceLabel.setTextColor(Monet.onSurfaceVariant)
        box.addView(sourceLabel, wrap())

        val sourceRow = LinearLayout(this)
        sourceRow.orientation = LinearLayout.HORIZONTAL
        val sources = listOf(
            Prefs.SOURCE_QQ to "QQ",
            Prefs.SOURCE_WECHAT to "微信",
            Prefs.SOURCE_DOUYIN to "抖音",
            Prefs.SOURCE_XHS to "小红书",
            Prefs.SOURCE_BOTH to "QQ+微信",
            Prefs.SOURCE_ALL to "全部"
        )
        for ((value, label) in sources) {
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 11f
            chip.setPadding(dp(10), dp(6), dp(10), dp(6))
            val active = Prefs.source(this) == value
            chip.background = Ui.chip(this, active)
            chip.setTextColor(if (active) Monet.primary else Monet.onSurfaceVariant)
            chip.setOnClickListener {
                setSource(value)
                for (i in 0 until sourceRow.childCount) {
                    val child = sourceRow.getChildAt(i) as TextView
                    val isActive = child === chip
                    child.background = Ui.chip(this, isActive)
                    child.setTextColor(if (isActive) Monet.primary else Monet.onSurfaceVariant)
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.rightMargin = dp(6)
            sourceRow.addView(chip, params)
        }
        val sourceScroll = HorizontalScrollView(this)
        sourceScroll.isHorizontalScrollBarEnabled = false
        sourceScroll.addView(sourceRow)
        box.addView(sourceScroll, wrap())

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(chipAction("授权") { onAuth() }, chipLP())
        row1.addView(chipAction("诊断") { onDiag() }, chipLP())
        row1.addView(chipAction("目录") { onEditRoots() }, chipLP())
        box.addView(row1, wrap())

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(chipAction("导出清单") { onInventory() }, chipLP())
        row2.addView(chipAction("使用引导") { showGuide(0) }, chipLP())
        row2.addView(chipAction("全选") { selectAll(true) }, chipLP())
        row2.addView(chipAction("全不选") { selectAll(false) }, chipLP())
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
        log.typeface = Typeface.MONOSPACE
        log.setTextColor(Monet.onSurfaceVariant)
        log.text = if (logBuffer.isEmpty()) "（暂无日志）" else logBuffer.toString()
        val logScroll = ScrollView(this)
        logScroll.addView(log)
        box.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        val bili = TextView(this)
        bili.text = "B 站主页 space.bilibili.com/1967666609 ↗"
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

    private fun chipLP(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.rightMargin = dp(6)
        params.topMargin = dp(6)
        return params
    }

    private fun chipAction(label: String, onClick: () -> Unit): TextView {
        val chip = TextView(this)
        chip.text = label
        chip.textSize = 11.5f
        chip.setPadding(dp(12), dp(7), dp(12), dp(7))
        chip.background = Ui.chip(this, false)
        chip.setTextColor(Monet.onSurface)
        chip.setOnClickListener { onClick() }
        return chip
    }

    // ================= 日志 =================

    private fun writeLog(line: String) {
        main.post {
            logBuffer.append(line).append('\n')
            if (logBuffer.length > 24000) logBuffer.delete(0, logBuffer.length - 20000)
        }
    }

    private fun logTextContent(): String = logBuffer.toString()

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ================= 动作 =================

    private fun onAuth() {
        if (!Shell.binderAlive()) {
            writeLog("没检测到 Shizuku")
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
            writeLog("申请 Shizuku 权限…")
            Shell.requestPermission()
        }
    }

    private fun onScan() {
        worker("扫描") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("拿不到 shell 服务，先授权")
                toast("先授权")
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

            // 标记"本次新增"：之前没见过的 key
            val seen = Prefs.seenKeys(this)
            var fresh = 0
            for (item in found) {
                item.isNew = !seen.contains(item.key)
                if (item.isNew) fresh++
            }
            Prefs.markSeen(this, found.map { it.key })
            found.sortByDescending { it.mtime }

            main.post {
                items.clear()
                items.addAll(found)
                selected.clear()
                applyFilter()
                if (items.isNotEmpty()) Prefs.setFlag(this, "ok_scan", true)
            }
            writeLog("扫描完成：检查 " + scanned + "，命中 " + found.size + "（新增 " + fresh + "），跳过 " + skipped)
            main.post {
                try {
                    (application as App).publish("qqbq-scan.txt", logTextContent())
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
            writeLog("打开 Shizuku 失败")
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
            .append("2. 扫描的是各 App 缓存目录，可能扫到一些你不需要的文件；")
            .append("结果按「我保存的 / 表情包 / 聊天图 / 其他」分类，并用「新增 / 已看过」标出本次新出现的。\n\n")
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
            "① 准备 Shizuku\n\n启动 Shizuku（开发者选项 → 无线调试 → 配对 → 启动），回到本 App 点右上「⋯」→「授权」。",
            "② 扫描\n\n点底部「扫描」。第一次会久一点，进度在「⋯」里的日志能看到。",
            "③ 认标记\n\n顶部第一排：「全部 / 新增 / 已看过」——「新增」就是这次新扫到的。",
            "④ 分类\n\n第二排：「我保存的 / 表情包 / 聊天图 / 其他」，把系统缓存和你自己的东西分开。",
            "⑤ 换排版\n\n第三排：「列表 / 四宫格 / 九宫格 / 多宫格」。点一格 = 勾选，长按 = 全屏预览。"
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
            Prefs.SOURCE_DOUYIN -> explainOnce(
                "douyin_explained_v1", "关于抖音",
                "抖音的图片缓存在 cache 目录，扫到的可能很多。\n\n系统小图标会归到「系统资源」，默认不显示。"
            )
            Prefs.SOURCE_XHS -> explainOnce(
                "xhs_explained_v1", "关于小红书",
                "小红书图片缓存在外置目录。\n\n想找自己的图点第二排「我保存的」。"
            )
            else -> explainOnce(
                "wechat_explained_v1", "关于微信",
                "微信的自定义表情在应用内部存储（/data/data/com.tencent.mm），没有 root 读不到。\n\n" +
                    "能拿到的是外置目录里的聊天图和你手动保存的图片。\n\n" +
                    "要导出微信表情：先发给文件传输助手 → 长按保存到手机 → 回来选「微信」扫描。"
            )
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
        for (view in actionViews) view.isEnabled = enabled
    }

    // ================= 适配器 =================

    private inner class GridHolder(
        val cell: FrameLayout,
        val image: ImageView,
        val label: TextView,
        val newBadge: TextView,
        val checkBadge: TextView
    )

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: GridHolder
            if (convertView == null) {
                val cell = FrameLayout(this@MainActivity)
                val box = LinearLayout(this@MainActivity)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(dp(4), dp(4), dp(4), dp(6))

                val image = ImageView(this@MainActivity)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                box.addView(image, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(78)))

                val label = TextView(this@MainActivity)
                label.textSize = 9f
                label.maxLines = 1
                label.gravity = Gravity.CENTER
                label.setTextColor(Monet.onSurfaceVariant)
                box.addView(label, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                cell.addView(box, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val newBadge = TextView(this@MainActivity)
                newBadge.text = "新"
                newBadge.textSize = 8.5f
                newBadge.setPadding(dp(5), dp(1), dp(5), dp(1))
                newBadge.setTextColor(Monet.onPrimaryFill)
                newBadge.background = Ui.badge(this@MainActivity)
                val badgeParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                badgeParams.leftMargin = dp(7)
                badgeParams.topMargin = dp(7)
                cell.addView(newBadge, badgeParams)

                val checkBadge = TextView(this@MainActivity)
                checkBadge.text = "✓"
                checkBadge.textSize = 10f
                checkBadge.gravity = Gravity.CENTER
                checkBadge.setTextColor(Monet.onPrimaryFill)
                checkBadge.background = Ui.badge(this@MainActivity)
                val checkParams = FrameLayout.LayoutParams(dp(18), dp(18))
                checkParams.gravity = Gravity.END or Gravity.TOP
                checkParams.rightMargin = dp(7)
                checkParams.topMargin = dp(7)
                cell.addView(checkBadge, checkParams)

                cell.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                holder = GridHolder(cell, image, label, newBadge, checkBadge)
                cell.tag = holder
                return cell
            }
            holder = convertView.tag as GridHolder

            val item = shown[position]
            val isSelected = selected.contains(item.path)
            holder.image.parent?.let { (it as View).background = null }
            holder.image.background = Ui.tile(this@MainActivity, isSelected)
            holder.image.setPadding(dp(6), dp(6), dp(6), dp(6))
            holder.label.text = if (isSelected) "✓ " + item.category else item.category
            holder.newBadge.visibility = if (item.isNew) View.VISIBLE else View.GONE
            holder.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            bindThumb(holder.image, item)
            return convertView
        }
    }

    private inner class RowHolder(
        val row: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val sub: TextView,
        val newBadge: TextView,
        val check: TextView
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
                row.setPadding(dp(12), dp(10), dp(10), dp(10))

                val thumb = ImageView(this@MainActivity)
                thumb.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(thumb)

                val texts = LinearLayout(this@MainActivity)
                texts.orientation = LinearLayout.VERTICAL
                texts.setPadding(dp(12), 0, dp(6), 0)
                val title = TextView(this@MainActivity)
                title.textSize = 13f
                title.setTextColor(Monet.onSurface)
                val sub = TextView(this@MainActivity)
                sub.textSize = 10f
                sub.setTextColor(Monet.onSurfaceVariant)
                texts.addView(title)
                texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val newBadge = TextView(this@MainActivity)
                newBadge.text = "新"
                newBadge.textSize = 9f
                newBadge.setPadding(dp(6), dp(1), dp(6), dp(1))
                newBadge.setTextColor(Monet.onPrimaryFill)
                newBadge.background = Ui.badge(this@MainActivity)
                row.addView(newBadge)

                val check = TextView(this@MainActivity)
                check.text = "✓"
                check.textSize = 13f
                check.setTextColor(Monet.primary)
                check.setPadding(dp(8), 0, dp(2), 0)
                row.addView(check)

                row.tag = RowHolder(row, thumb, title, sub, newBadge, check)
                holder = row.tag as RowHolder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as RowHolder
            }
            val item = shown[position]
            val isSelected = selected.contains(item.path)
            holder.row.background = Ui.row(this@MainActivity, isSelected)
            holder.title.text = item.packName + " · " + item.fileName
            holder.sub.text = item.category + " · " + item.format + " · " + item.sizeText
            holder.newBadge.visibility = if (item.isNew) View.VISIBLE else View.GONE
            holder.check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
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
                main.post {
                    if (view.tag == item.path) view.setImageBitmap(bitmap)
                }
            } catch (t: Throwable) {
            }
        }
    }
}

object ShizukuState {
    var text: String = "Shizuku 未运行"
}

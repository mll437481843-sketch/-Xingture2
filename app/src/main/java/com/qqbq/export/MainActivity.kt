package com.qqbq.export

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
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
    private val thumbCache = LruCache<String, Bitmap>(120)

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var listView: ListView
    private lateinit var chkStatic: CheckBox
    private lateinit var adapter: FoundAdapter
    private val actionButtons = ArrayList<Button>()

    private val biliUrl = "https://space.bilibili.com/1967666609"

    private val items = ArrayList<Scanner.Found>()
    private val selected = HashSet<String>()
    private var busy = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == Shell.REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                writeLog("Shizuku 授权成功，可以开始扫描了")
            } else {
                writeLog("Shizuku 授权被拒绝，请到 Shizuku 应用里给本应用授权")
            }
            refreshStatus()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monet.init(this)
        Shell.configure(applicationContext)
        adapter = FoundAdapter()
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
        writeLog("用法：授权 → 扫描 → 点一行勾选（长按播放动图）→ 导出选中")
        maybeShowNotice()
    }

    /** 首次启动：使用须知（说明会扫到一些不需要的文件）。 */
    private fun maybeShowNotice() {
        val sp = getSharedPreferences("qqbq", MODE_PRIVATE)
        if (sp.getBoolean("notice_shown_v1", false)) return
        val text = StringBuilder()
            .append("1. 本工具需要 Shizuku（无线调试授权）才能读取 QQ 的表情缓存目录。\n\n")
            .append("2. 扫描范围是 QQ 的缓存目录，因此**可能扫到一些你并不需要的文件**：\n")
            .append("   · 群聊里显示过的表情\n")
            .append("   · 整包下载的表情包（一个包里有几十张）\n")
            .append("   · 缩略图 / 合集图 / 元数据\n")
            .append("   列表里每一项都可以取消勾选，只导出你要的。\n\n")
            .append("3. 全程只读：不修改、不删除 QQ 的任何数据；不联网、不上传。\n\n")
            .append("4. 导出位置：相册 Pictures/QQ表情/\n\n")
            .append("5. 「我的表情」里的收藏如果不在缓存里，需要先在 QQ 里使用或转发过一次才会出现。\n\n")
            .append("如果这个小工具帮到了你，欢迎来 B 站主页坐坐（UID 1967666609），")
            .append("顺手充个电就当请作者喝杯咖啡，完全随意。\n\n")
            .append("作者：星球卑_PHb")
            .toString()
        AlertDialog.Builder(this)
            .setTitle("使用须知")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("我已了解") { _, _ ->
                sp.edit().putBoolean("notice_shown_v1", true).apply()
            }
            .show()
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

    // ---------- UI ----------

    private fun buildUi() {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(Monet.surface)
        content.setPadding(dp(16), dp(16), dp(16), dp(8))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "QQ图片提取工具"
        title.textSize = 23f
        title.setTextColor(Monet.onSurface)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val subtitle = TextView(this)
        subtitle.text = "导出 QQ 动图表情 · 可预览 · 可挑选"
        subtitle.textSize = 11.5f
        subtitle.setTextColor(Monet.onSurfaceVariant)
        titles.addView(title)
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val author = TextView(this)
        author.text = "星球卑_PHb"
        author.textSize = 11f
        author.setTextColor(Monet.primary)
        author.background = Ui.round(14, Monet.softPrimary, this)
        author.setPadding(dp(10), dp(6), dp(10), dp(6))
        author.setOnClickListener { openUrl(biliUrl) }
        header.addView(author)
        content.addView(header, wrap())

        // 状态：primaryContainer 主色块
        val statusCard = LinearLayout(this)
        statusCard.orientation = LinearLayout.VERTICAL
        statusCard.background = Ui.heroCard(this)
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14))

        statusText = TextView(this)
        statusText.textSize = 14f
        statusText.setTextColor(Monet.onSoftPrimary)
        statusText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        statusCard.addView(statusText, wrap())

        countText = TextView(this)
        countText.textSize = 12f
        countText.setTextColor(Ui.withAlpha(Monet.onSoftPrimary, 190))
        countText.setPadding(0, dp(4), 0, 0)
        statusCard.addView(countText, wrap())

        content.addView(statusCard, spaced(12))

        // 操作区：两行 M3 按钮
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.VERTICAL
        actions.background = Ui.card(this, 24)
        actions.setPadding(dp(8), dp(8), dp(8), dp(8))

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(m3Button("授权", false) { onAuth() }, weight())
        row1.addView(m3Button("扫描", true) { onScan() }, weight())
        row1.addView(m3Button("导出选中", true) { onExport() }, weight())
        actions.addView(row1, wrap())

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(m3Button("全选", false) { selectAll(true) }, weight())
        row2.addView(m3Button("全不选", false) { selectAll(false) }, weight())
        row2.addView(m3Button("清空", false) { clearResults() }, weight())
        actions.addView(row2, wrap())

        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.addView(m3Button("诊断", false) { onDiag() }, weight())
        row3.addView(m3Button("目录", false) { onEditRoots() }, weight())
        row3.addView(m3Button("导出清单", false) { onInventory() }, weight())
        actions.addView(row3, wrap())

        content.addView(actions, spaced(12))

        val options = LinearLayout(this)
        options.orientation = LinearLayout.VERTICAL
        options.background = Ui.card(this, 24)
        options.setPadding(dp(14), dp(4), dp(14), dp(10))

        chkStatic = CheckBox(this)
        chkStatic.text = "同时导出静态表情"
        chkStatic.textSize = 13f
        chkStatic.setTextColor(Monet.onSurface)
        chkStatic.buttonTintList = ColorStateList.valueOf(Monet.primary)
        chkStatic.isChecked = Prefs.wantStatic(this)
        chkStatic.setOnCheckedChangeListener { _, value -> Prefs.setWantStatic(this, value) }
        options.addView(chkStatic, wrap())

        val hint = TextView(this)
        hint.textSize = 11f
        hint.setTextColor(Monet.onSurfaceVariant)
        hint.text = "点一行=勾选/取消，长按一行=播放动图。想让「我的表情」进缓存，先在 QQ 里把表情面板滑一遍。"
        options.addView(hint, wrap())

        content.addView(options, spaced(12))

        val listCard = FrameLayout(this)
        listCard.background = Ui.card(this, 24)
        listCard.setPadding(dp(6), dp(6), dp(6), dp(6))

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setSelector(android.R.color.transparent)
        listView.isVerticalScrollBarEnabled = false
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position in items.indices) preview(items[position])
            true
        }
        listCard.addView(listView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(listCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(12) })

        val logCard = FrameLayout(this)
        logCard.background = Ui.card(this, 20)
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10))

        logScroll = ScrollView(this)
        logText = TextView(this)
        logText.textSize = 10f
        logText.setTextColor(Monet.onSurfaceVariant)
        logText.typeface = Typeface.MONOSPACE
        logScroll.addView(logText)
        logCard.addView(logScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(logCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)).also { it.topMargin = dp(10) })

        val footer = TextView(this)
        footer.text = "作者 星球卑_PHb · v3.2 · 来 B 站主页坐坐 ↗"
        footer.textSize = 10f
        footer.setTextColor(Ui.withAlpha(Monet.primary, 220))
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(8), 0, 0)
        footer.setOnClickListener { openUrl(biliUrl) }
        content.addView(footer, wrap())

        setContentView(content)
        updateCount()
    }

    private fun m3Button(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.textSize = 13f
        button.setTextColor(if (primary) Monet.onPrimary else Monet.onSurface)
        button.background = if (primary) Ui.filled(this) else Ui.tonal(this)
        button.stateListAnimator = null
        button.minHeight = 0
        button.minimumHeight = dp(42)
        button.setPadding(dp(6), dp(8), dp(6), dp(8))
        button.setOnClickListener { onClick() }
        actionButtons.add(button)
        return button
    }

    private fun weight(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        params.setMargins(dp(3), dp(3), dp(3), dp(3))
        return params
    }

    private fun spaced(topDp: Int): LinearLayout.LayoutParams {
        val params = wrap()
        params.topMargin = dp(topDp)
        return params
    }

    private fun wrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun refreshStatus() {
        val alive = Shell.binderAlive()
        val granted = alive && Shell.hasPermission()
        statusText.text = when {
            !alive -> "Shizuku 未运行"
            !granted -> "Shizuku 已运行，等待授权"
            else -> "Shizuku 已授权（v" + Shell.serviceVersion() + "）"
        }
    }

    private fun updateCount() {
        countText.text = "已选 " + selected.size + " 个 / 共 " + items.size + " 个"
    }

    // ---------- 选择 ----------

    private fun toggle(position: Int) {
        if (position !in items.indices) return
        val item = items[position]
        if (!selected.add(item.path)) selected.remove(item.path)
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun selectAll(value: Boolean) {
        selected.clear()
        if (value) {
            for (item in items) selected.add(item.path)
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun clearResults() {
        items.clear()
        selected.clear()
        adapter.notifyDataSetChanged()
        updateCount()
        writeLog("已清空当前结果（不会删任何文件）")
    }

    // ---------- 动作 ----------

    private fun onAuth() {
        if (!Shell.binderAlive()) {
            writeLog("没检测到 Shizuku 服务，先安装并启动 Shizuku")
            openShizuku()
            return
        }
        if (Shell.hasPermission()) {
            worker("绑定") {
                val svc = Shell.ensureService()
                if (svc == null) {
                    writeLog("绑定失败：确认 Shizuku 仍在运行且已授权")
                } else {
                    writeLog("绑定成功，shell 身份 " + svc.whoami())
                }
            }
        } else {
            writeLog("正在向 Shizuku 申请权限…")
            Shell.requestPermission()
        }
    }

    private fun onScan() {
        worker("扫描") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("拿不到 shell 服务，请先授权")
                return@worker
            }
            writeLog("shell 身份：" + svc.whoami())

            val wantStatic = Prefs.wantStatic(this)
            val roots = Prefs.roots(this)
            val found = ArrayList<Scanner.Found>()
            var scanned = 0
            var skipped = 0
            var packCount = 0

            for (root in roots) {
                val files = Scanner.listFiles(svc, root)
                if (files == null) {
                    writeLog("· 不存在  " + root)
                    continue
                }
                if (files.isEmpty()) {
                    writeLog("· " + root + " → 空目录")
                    continue
                }
                writeLog("· " + root + " → " + files.size + " 个文件")
                val packNames = HashMap<String, String>()
                for (path in files) {
                    scanned++
                    val file = File(path)
                    val packDir = file.parent ?: continue
                    val packId = File(packDir).name
                    val packName = packNames.getOrPut(packDir) {
                        Scanner.readPackName(svc, packDir, packId) ?: ("表情包" + packId)
                    }
                    val item = Scanner.classify(svc, path, wantStatic, packId, packName)
                    if (item == null) skipped++ else found.add(item)
                }
                packCount += packNames.size
                writeLog("  └ 累计命中 " + found.size + " 个")
            }

            found.sortByDescending { it.mtime }
            main.post {
                items.clear()
                items.addAll(found)
                selected.clear()
                adapter.notifyDataSetChanged()
                updateCount()
            }

            writeLog("扫描完成：检查 " + scanned + " 个文件，命中 " + found.size + " 个贴纸（来自 " + packCount + " 个目录），跳过 " + skipped)
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
            toast("先扫描出结果")
            return
        }
        val chosen = ArrayList<Scanner.Found>()
        for (item in items) {
            if (selected.contains(item.path)) chosen.add(item)
        }
        if (chosen.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没勾选")
                .setMessage("要导出全部 " + items.size + " 个吗？")
                .setPositiveButton("全部导出") { _, _ -> doExport(ArrayList(items)) }
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
                writeLog("拿不到 shell 服务，无法读取表情文件")
                return@worker
            }
            writeLog("导出到 Pictures/" + Exporter.ALBUM)
            val result = Exporter.exportAll(this, svc, snapshot) { index, item, ok ->
                if (index % 10 == 0 || !ok) {
                    writeLog("  " + index + "/" + snapshot.size + "  " + (if (ok) "OK " else "跳过 ") + item.label)
                }
            }
            writeLog("导出结束：成功 " + result.ok + "，重复 " + result.duplicate + "，失败 " + result.failed)
            writeLog("位置：/sdcard/Pictures/" + Exporter.ALBUM)
        }
    }

    private fun onDiag() {
        worker("诊断") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("诊断需要 Shizuku 授权")
                return@worker
            }
            val sb = StringBuilder()
            sb.append("=== 身份 ===\n").append(svc.exec("id")).append("\n\n")
            sb.append("=== 候选目录 ===\n")
            for (root in Prefs.roots(this)) {
                val exists = svc.exec("if [ -d '" + root + "' ]; then echo OK; else echo MISSING; fi").trim()
                val files = svc.exec("find '" + root + "' -type f 2>/dev/null | wc -l").trim()
                sb.append(root).append("  ->  ").append(exists).append("  ").append(files).append(" 个文件\n")
            }
            val base = "/sdcard/Android/data/com.tencent.mobileqq"
            sb.append("\n=== QQ 外置目录 ===\n")
            sb.append(svc.exec("ls -1a " + base + " 2>/dev/null")).append("\n")
            main.post { showTextDialog("诊断结果", sb.toString()) }
        }
    }

    private fun onEditRoots() {
        val edit = EditText(this)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        edit.setText(Prefs.roots(this).joinToString("\n"))
        edit.setMinLines(8)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(8), dp(16), dp(8))
        container.addView(edit, wrap())
        AlertDialog.Builder(this)
            .setTitle("扫描目录（一行一个）")
            .setView(container)
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

    private fun openUrl(url: String) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (t: Throwable) {
            writeLog("打不开链接：" + url)
        }
    }

    private fun openShizuku() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent == null) {
                writeLog("本机没装 Shizuku")
            } else {
                startActivity(intent)
            }
        } catch (t: Throwable) {
            writeLog("打开 Shizuku 失败：" + t.message)
        }
    }

    private fun onInventory() {
        worker("导出清单") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("需要 Shizuku 授权")
                return@worker
            }
            val roots = ArrayList<String>()
            roots.addAll(Prefs.roots(this))
            roots.add("/sdcard/Android/data/com.tencent.mobileqq")

            val sb = StringBuilder()
            sb.append("kind,root_or_dir,path,size,mtime,hex32,typeRaw,typeDec\n")
            var rows = 0
            var animated = 0
            for (root in roots.distinct()) {
                val files = Scanner.listFiles(svc, root)
                if (files == null) {
                    writeLog("· 跳过（不存在）" + root)
                    continue
                }
                writeLog("· " + root + " → " + files.size + " 个文件")
                for (path in files) {
                    if (rows >= 120000) break
                    val info = Scanner.probe(svc, path) ?: continue
                    val isAnimated = info.rawType.startsWith("GIF") || info.decodedType.startsWith("GIF") ||
                        info.rawType.startsWith("APNG") || info.decodedType.startsWith("APNG") ||
                        info.rawType.startsWith("动态") || info.decodedType.startsWith("动态")
                    if (isAnimated) animated++
                    sb.append("file,").append(csv(root)).append(",").append(csv(path)).append(",")
                        .append(info.size).append(",").append(info.mtime).append(",")
                        .append(info.hex).append(",").append(csv(info.rawType)).append(",")
                        .append(csv(info.decodedType)).append("\n")
                    rows++
                }
            }
            (application as App).publish("qqbq-inventory.csv", sb.toString())
            writeLog("清单完成：$rows 行（动图 $animated）→ Download/qqbq-inventory.csv")
        }
    }

    private fun csv(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    // ---------- 预览 ----------

    private fun preview(item: Scanner.Found) {
        worker("预览") {
            val svc = Shell.ensureService()
            if (svc == null) {
                writeLog("预览需要 Shizuku 授权")
                return@worker
            }
            val bytes = Scanner.readAll(svc, item.path)
            if (bytes == null) {
                writeLog("读取失败：" + item.path)
                return@worker
            }
            if (item.obfuscated) Scanner.decodeHeader(bytes)
            main.post { showPreviewDialog(item, bytes) }
        }
    }

    private fun showPreviewDialog(item: Scanner.Found, bytes: ByteArray) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(16), dp(16), dp(6))
        container.setBackgroundColor(Monet.surface)

        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        var playing: AnimatedImageDrawable? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val drawable: Drawable = ImageDecoderCompat.decode(this, bytes)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                    playing = drawable
                }
                image.setImageDrawable(drawable)
            } catch (t: Throwable) {
                image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        } else {
            image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        container.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        val info = TextView(this)
        info.textSize = 11f
        info.setTextColor(Monet.onSurfaceVariant)
        info.text = item.format + " · " + item.sizeText + " · " + item.timeText + "\n" + item.label
        container.addView(info)

        val dialog = AlertDialog.Builder(this)
            .setTitle("动图预览")
            .setView(container)
            .setPositiveButton(if (selected.contains(item.path)) "取消勾选" else "勾选这个") { _, _ ->
                if (!selected.add(item.path)) selected.remove(item.path)
                adapter.notifyDataSetChanged()
                updateCount()
            }
            .setNegativeButton("关闭", null)
            .create()

        dialog.setOnDismissListener {
            try {
                playing?.stop()
            } catch (ignored: Throwable) {
            }
        }
        dialog.show()
    }

    private fun showTextDialog(title: String, text: String) {
        val view = TextView(this)
        view.typeface = Typeface.MONOSPACE
        view.textSize = 10.5f
        view.setTextIsSelectable(true)
        view.text = text
        val scroll = ScrollView(this)
        scroll.setPadding(dp(16), dp(8), dp(16), dp(8))
        scroll.addView(view)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---------- 线程 ----------

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

    private fun writeLog(line: String) {
        main.post {
            val merged = if (logText.text.isNullOrEmpty()) line else logText.text.toString() + "\n" + line
            val lines = merged.split("\n")
            logText.text = if (lines.size > 300) lines.subList(lines.size - 300, lines.size).joinToString("\n") else merged
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------- 列表 ----------

    private inner class RowHolder(
        val row: LinearLayout,
        val thumb: ImageView,
        val check: CheckBox,
        val title: TextView,
        val sub: TextView
    )

    private inner class FoundAdapter : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val wrapper: FrameLayout
            val holder: RowHolder
            if (convertView == null) {
                wrapper = FrameLayout(this@MainActivity)
                wrapper.setPadding(dp(4), dp(2), dp(4), dp(2))

                val row = LinearLayout(this@MainActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(10), dp(10), dp(10))

                val thumb = ImageView(this@MainActivity)
                thumb.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(thumb)

                val texts = LinearLayout(this@MainActivity)
                texts.orientation = LinearLayout.VERTICAL
                texts.setPadding(dp(12), 0, dp(6), 0)
                val title = TextView(this@MainActivity)
                title.textSize = 13.5f
                title.setTextColor(Monet.onSurface)
                val sub = TextView(this@MainActivity)
                sub.textSize = 10.5f
                sub.setTextColor(Monet.onSurfaceVariant)
                texts.addView(title)
                texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val check = CheckBox(this@MainActivity)
                check.isClickable = false
                check.isFocusable = false
                check.buttonTintList = ColorStateList.valueOf(Monet.primary)
                row.addView(check)

                wrapper.addView(row, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                wrapper.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                holder = RowHolder(row, thumb, check, title, sub)
                wrapper.tag = holder
            } else {
                wrapper = convertView as FrameLayout
                holder = wrapper.tag as RowHolder
            }

            val item = items[position]
            val isSelected = selected.contains(item.path)
            holder.row.background = Ui.row(this@MainActivity, isSelected)
            holder.title.text = item.format + "  " + item.label
            holder.sub.text = item.sizeText + "  " + item.timeText
            holder.check.isChecked = isSelected
            bindThumb(holder.thumb, item)
            return wrapper
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

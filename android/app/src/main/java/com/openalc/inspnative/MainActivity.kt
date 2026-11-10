package com.openalc.inspnative

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.ImageSpan
import android.text.style.QuoteSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialContainerTransform
import androidx.transition.TransitionManager
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: NativeStore
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var cards: RecyclerView
    private lateinit var filters: LinearLayout
    private lateinit var cardAdapter: Cards
    private var drawerOverlay: FrameLayout? = null
    private var settingsOverlay: LinearLayout? = null
    private var selectedCard: View? = null
    private var active: NativeNote? = null
    private var bodyInput: EditText? = null
    private var mindPanel: MindMapPanel? = null
    private var saveTask: Runnable? = null
    private var pendingImageNoteId: String? = null
    private var pendingImagePrivate = false
    private var groupId: String? = null
    private var privateArea = false
    private var privateGeneration = 0
    private var search = ""
    private var allNotes = listOf<NativeNote>()
    private var allGroups = listOf<NoteGroup>()
    private val green = 0xff54d81b.toInt()
    private val dark by lazy { resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private val ink by lazy { if (dark) 0xffd4d4d4.toInt() else Color.BLACK }
    private val paper by lazy { if (dark) 0xff20221f.toInt() else Color.WHITE }
    private val background by lazy { if (dark) 0xff101010.toInt() else 0xfff5f5f5.toInt() }
    private val secondary by lazy { if (dark) 0xff9fa79b.toInt() else 0xff616860.toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NativeStore(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        root = FrameLayout(this).apply { id = View.generateViewId(); setBackgroundColor(this@MainActivity.background) }
        home = buildHome()
        editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper); visibility = View.GONE }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            home.setPadding(0, bars.top, 0, bars.bottom)
            editor.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
        val appearance = if (dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(appearance,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        refresh()
    }

    private fun buildHome(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)) }
        header.addView(action("☰") { openDrawer() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "笔记"; textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("⋯") { listOptions() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)
        val searchInput = EditText(this).apply {
            hint = "搜索笔记"; textSize = 15f; setSingleLine(true); setTextColor(ink); setHintTextColor(secondary)
            background = rounded(if (dark) 0xff30342e.toInt() else 0xffebeeea.toInt(), 17)
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(watcher { search = it; renderCards() })
        }
        page.addView(searchInput, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(22), dp(8), dp(22), dp(14)) })
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filters = LinearLayout(this).apply { setPadding(dp(22), 0, dp(22), 0) }
        filterScroll.addView(filters)
        page.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(52)))
        cards = RecyclerView(this).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            clipToPadding = false
            setPadding(dp(14), dp(16), dp(14), dp(18))
        }
        cardAdapter = Cards()
        cards.adapter = cardAdapter
        page.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f))
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(10)) }
        compose.addView(pill("＋  笔记") { create("richtext") }, LinearLayout.LayoutParams(0, dp(54), 1f))
        compose.addView(pill("⌘  灵感笔记") { create("mindmap") }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(8) })
        page.addView(compose)
        return page
    }

    private fun refresh() {
        val area = privateArea
        val generation = privateGeneration
        io.execute {
            val groups = store.groups()
            val notes = store.notes(area)
            runOnUiThread {
                if (generation == privateGeneration && area == privateArea) {
                    allGroups = groups; allNotes = notes; renderFilters(); renderCards()
                }
            }
        }
    }

    private fun renderFilters() {
        filters.removeAllViews()
        val choices = listOf(null to if (privateArea) "隐私笔记" else "全部") +
            if (privateArea) emptyList() else allGroups.map { it.id to it.name }
        for ((id, label) in choices) {
            filters.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(ink); gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                background = rounded(if (groupId == id) paper else Color.TRANSPARENT, 24)
                setOnClickListener { groupId = id; renderFilters(); renderCards() }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(7) })
        }
    }

    private fun renderCards() {
        val notes = allNotes.filter { (groupId == null || it.groupId == groupId) &&
            (search.isBlank() || it.title.contains(search, true) || preview(it).contains(search, true)) }
        cardAdapter.submit(notes)
    }

    private fun preview(note: NativeNote): String = if (note.kind == "mindmap") {
        val children = JSONObject(note.body).getJSONObject("root").getJSONArray("children")
        (0 until minOf(3, children.length())).joinToString("  ·  ") { children.getJSONObject(it).getString("text") }
    } else RichTextCodec.plain(note.body).replace("\uFFFC", "图片").trim().take(180)

    private fun create(kind: String) {
        io.execute {
            val note = store.create(kind, groupId, privateArea)
            runOnUiThread { allNotes = listOf(note) + allNotes; renderCards(); openNote(note, null) }
        }
    }

    private fun openNote(note: NativeNote, card: View?) {
        val generation = privateGeneration
        active = note
        selectedCard = card
        if (note.kind == "richtext") {
            io.execute {
                val document = RichTextCodec.decode(note.body) { id -> imageDrawable(note, id) }
                runOnUiThread { if (generation == privateGeneration) showEditor(note, document, card) }
            }
        } else showEditor(note, null, card)
    }

    private fun showEditor(note: NativeNote, document: Spannable?, card: View?) {
        editor.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        top.addView(action("‹") { closeEditor() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = EditText(this).apply {
            setSingleLine(true); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            background = null; setText(note.title); clearFocus()
            addTextChangedListener(watcher {
                note.title = it.ifBlank { if (note.kind == "mindmap") "未命名的灵感笔记" else "未命名笔记" }
                mindPanel?.renameRoot(note.title)
                scheduleSave()
            })
        }
        top.addView(title, LinearLayout.LayoutParams(0, dp(50), 1f))
        if (note.kind == "mindmap") {
            lateinit var toggle: TextView
            toggle = action("◇") { toggle.text = if (mindPanel?.toggleMode() == true) "☷" else "◇" }
            top.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        top.addView(action("⋯") { noteOptions(note) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        editor.addView(top)
        if (note.kind == "richtext") {
            val input = EditText(this).apply {
                gravity = Gravity.TOP; textSize = 18f; setTextColor(ink); setHintTextColor(secondary)
                background = null; hint = "从这里开始记录…"
                setPadding(dp(24), dp(22), dp(24), dp(24))
                setText(document ?: RichTextCodec.decode(note.body))
                clearFocus()
                addTextChangedListener(watcher { scheduleSave() })
            }
            bodyInput = input
            mindPanel = null
            editor.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
            editor.addView(formatBar(), LinearLayout.LayoutParams(-1, dp(57)))
        } else {
            bodyInput = null
            mindPanel = MindMapPanel(this, note) { scheduleSave() }.also { editor.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        }
        editor.isFocusableInTouchMode = true
        editor.requestFocus()
        if (card != null) {
            val transition = MaterialContainerTransform().apply {
                startView = card; endView = editor; addTarget(editor)
                drawingViewId = android.R.id.content; duration = 320
                scrimColor = Color.TRANSPARENT
            }
            TransitionManager.beginDelayedTransition(root, transition)
            card.visibility = View.INVISIBLE
        }
        editor.visibility = View.VISIBLE
    }

    private fun closeEditor() {
        persistNow()
        val card = selectedCard
        if (card != null && card.parent != null) {
            val transition = MaterialContainerTransform().apply {
                startView = editor; endView = card; addTarget(card)
                drawingViewId = android.R.id.content; duration = 270
                scrimColor = Color.TRANSPARENT
            }
            TransitionManager.beginDelayedTransition(root, transition)
            card.visibility = View.VISIBLE
        }
        editor.visibility = View.GONE
        active = null; bodyInput = null; mindPanel = null; selectedCard = null
        if (card != null && card.parent != null) handler.postDelayed({ refresh() }, 280) else refresh()
    }

    private fun scheduleSave() {
        saveTask?.let(handler::removeCallbacks)
        saveTask = Runnable { persistNow() }.also { handler.postDelayed(it, 260) }
    }

    private fun persistNow() {
        saveTask?.let(handler::removeCallbacks)
        saveTask = null
        val note = active ?: return
        bodyInput?.let { note.body = RichTextCodec.encode(it.text) }
        val copy = note.copy()
        io.execute { store.save(copy) }
    }

    private fun formatBar(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(paper) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        val commands = listOf("B" to "bold", "I" to "italic", "U" to "underline", "❝" to "quote", "•" to "bullet",
            "≡" to "left", "☰" to "center", "≡›" to "right", "＋图" to "image")
        for ((label, command) in commands) row.addView(action(label) { format(command) }, LinearLayout.LayoutParams(dp(50), dp(48)))
        scroll.addView(row)
        return scroll
    }

    private fun format(command: String) {
        if (command == "image") {
            pendingImageNoteId = active?.id
            pendingImagePrivate = active?.private == true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(intent, 700)
            return
        }
        val input = bodyInput ?: return
        val start = input.selectionStart
        val end = input.selectionEnd
        if (start == end) { toast("先选中要设置格式的文字"); return }
        val span = when (command) {
            "bold" -> StyleSpan(Typeface.BOLD)
            "italic" -> StyleSpan(Typeface.ITALIC)
            "underline" -> UnderlineSpan()
            "quote" -> QuoteSpan(green)
            "bullet" -> BulletSpan(dp(12), green)
            "center" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
            "right" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)
            else -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)
        }
        input.text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (command == "quote") input.text.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        scheduleSave()
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            io.execute {
                try {
                    val bundle = contentResolver.openInputStream(uri)!!.use(TransferImport::read)
                    runOnUiThread {
                        if (bundle.notes.any { it.note.private }) authenticate { importBundle(bundle) }
                        else importBundle(bundle)
                    }
                } catch (error: Exception) {
                    runOnUiThread { toast("迁移文件无法读取：${error.message}") }
                }
            }
            return
        }
        if (requestCode != 700) return
        if (resultCode != Activity.RESULT_OK) { pendingImageNoteId = null; return }
        val noteId = pendingImageNoteId ?: return
        val isPrivate = pendingImagePrivate
        pendingImageNoteId = null
        val uri = data?.data ?: return
        if (isPrivate && !privateArea) authenticate { importPickedImage(noteId, uri, true) }
        else importPickedImage(noteId, uri, isPrivate)
    }

    private fun importPickedImage(noteId: String, uri: Uri, isPrivate: Boolean) {
        io.execute {
            try {
                val note = store.notes(isPrivate).first { it.id == noteId }
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val id = store.addImage(note, bytes, contentResolver.getType(uri) ?: "image/jpeg")
                val drawable = decodeImage(bytes)
                runOnUiThread {
                    val input = bodyInput?.takeIf { active?.id == noteId }
                    if (input != null) {
                        val start = input.selectionStart.coerceAtLeast(0)
                        input.text.insert(start, "\n\uFFFC\n")
                        input.text.setSpan(ImageSpan(drawable, id), start + 1, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        scheduleSave()
                    } else {
                        io.execute {
                            val document = RichTextCodec.decode(note.body) { imageDrawable(note, it) }
                            val start = document.length
                            document.append("\n\uFFFC\n")
                            document.setSpan(ImageSpan(drawable, id), start + 1, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            note.body = RichTextCodec.encode(document)
                            store.save(note)
                            runOnUiThread {
                                if (isPrivate) { privateArea = true; groupId = null }
                                refresh(); openNote(note, null)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { toast("图片无法插入：${error.message}") }
            }
        }
    }

    private fun imageDrawable(note: NativeNote, id: String) = decodeImage(store.image(note, id))

    private fun decodeImage(bytes: ByteArray, maxWidth: Int = resources.displayMetrics.widthPixels - dp(48)): BitmapDrawable {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = maxWidth.coerceAtLeast(1)
        val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= width * 2 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return BitmapDrawable(resources, bitmap).apply {
            val height = (bitmap.height.toFloat() * width / bitmap.width).toInt()
            setBounds(0, 0, width, height)
        }
    }

    private fun noteOptions(note: NativeNote) {
        val items = arrayOf("分享纯文本", "复制纯文本", "移动到分组", if (note.private) "移出隐私笔记" else "移入隐私笔记", "删除笔记")
        MaterialAlertDialogBuilder(this).setItems(items) { _, index -> when (index) {
            0 -> share(note)
            1 -> {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(note.title, contentText(note)))
                toast("已复制")
            }
            2 -> groupPicker(note)
            3 -> authenticate {
                persistNow()
                io.execute {
                    store.setPrivacy(note, !note.private)
                    runOnUiThread { closeEditor(); leavePrivate() }
                }
            }
            4 -> MaterialAlertDialogBuilder(this).setTitle("删除这篇笔记？").setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> io.execute { store.delete(note.id); runOnUiThread { closeEditor() } } }.show()
        } }.show()
    }

    private fun contentText(note: NativeNote): String = if (note.kind == "richtext") RichTextCodec.plain(note.body) else {
        val lines = mutableListOf<String>()
        fun visit(node: JSONObject, depth: Int) {
            lines += "  ".repeat(depth) + node.getString("text")
            val children = node.getJSONArray("children")
            for (index in 0 until children.length()) visit(children.getJSONObject(index), depth + 1)
        }
        visit(JSONObject(note.body).getJSONObject("root"), 0)
        lines.joinToString("\n")
    }

    private fun share(note: NativeNote) {
        val text = "${note.title}\n\n${contentText(note)}"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(note.title, text))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "分享笔记"))
    }

    private fun groupPicker(note: NativeNote) {
        val names = listOf("未分组") + allGroups.map { it.name }
        MaterialAlertDialogBuilder(this).setTitle("移动到").setItems(names.toTypedArray()) { _, index ->
            note.groupId = if (index == 0) null else allGroups[index - 1].id
            io.execute { store.save(note.copy()); runOnUiThread { refresh() } }
        }.show()
    }

    private fun listOptions() {
        MaterialAlertDialogBuilder(this).setItems(arrayOf("新建分组", "进入隐私笔记", "从拾页导入", "关于拾页 Native")) { _, index -> when (index) {
            0 -> groupDialog()
            1 -> enterPrivate()
            2 -> pickTransfer()
            3 -> MaterialAlertDialogBuilder(this).setTitle("拾页 Native").setMessage("OpenALC · MIT License\nAndroid 原生单机版 0.1.0").setPositiveButton("完成", null).show()
        } }.show()
    }

    private fun pickTransfer() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 701)
    }

    private fun importBundle(bundle: TransferBundle) {
        io.execute {
            try {
                val count = store.importBundle(bundle)
                if (!privateArea) store.lockPrivate()
                runOnUiThread { refresh(); toast("已导入 $count 篇笔记") }
            } catch (error: Exception) {
                runOnUiThread { toast("导入失败：${error.message}") }
            }
        }
    }

    private fun groupDialog() {
        val input = EditText(this).apply { hint = "分组名称"; setSingleLine(true) }
        MaterialAlertDialogBuilder(this).setTitle("新建分组").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) io.execute { store.addGroup(name); runOnUiThread { refresh() } }
            }.show()
    }

    private fun openDrawer() {
        val overlay = FrameLayout(this)
        drawerOverlay = overlay
        val shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; setOnClickListener { closeDrawer() } }
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val width = minOf((resources.displayMetrics.widthPixels * 0.72f).toInt(), dp(340))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xff171717.toInt() else 0xfff5f5f5.toInt())
            translationX = -width.toFloat()
            val bars = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            setPadding(dp(13), bars?.top ?: 0, dp(13), bars?.bottom ?: 0)
        }
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        panel.addView(TextView(this).apply {
            text = "拾页 · Inspiration"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(58)))
        panel.addView(TextView(this).apply {
            text = "by OpenALC"; textSize = 12f; setTextColor(secondary); gravity = Gravity.END
            setPadding(0, 0, dp(14), dp(15))
        }, LinearLayout.LayoutParams(-1, dp(32)))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(links)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(label: String, click: () -> Unit) {
            links.addView(TextView(this).apply {
                text = label; textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                background = rounded(Color.TRANSPARENT, 14)
                setOnClickListener { closeDrawer(); click() }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        row("全部笔记") { leavePrivate() }
        row("隐私笔记") { enterPrivate() }
        links.addView(TextView(this).apply {
            text = "分组"; textSize = 13f; setTextColor(secondary)
            setPadding(dp(16), dp(22), 0, dp(9))
        })
        for (group in allGroups) row(group.name) { leavePrivate(); groupId = group.id; renderFilters(); renderCards() }
        row("＋  新建分组") { groupDialog() }
        panel.addView(TextView(this).apply {
            text = "⚙  设置"; textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener { closeDrawer(); openSettings() }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        shade.animate().alpha(if (dark) 0.7f else 0.38f).setDuration(230).start()
        panel.animate().translationX(0f).setDuration(260).start()
    }

    private fun closeDrawer() {
        val overlay = drawerOverlay ?: return
        drawerOverlay = null
        val panel = overlay.getChildAt(1)
        overlay.getChildAt(0).animate().alpha(0f).setDuration(200).start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220).withEndAction { root.removeView(overlay) }.start()
    }

    private fun openSettings() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background)
            val bars = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            setPadding(dp(16), bars?.top ?: 0, dp(16), bars?.bottom ?: 0)
        }
        settingsOverlay = page
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹") { closeSettings() }, LinearLayout.LayoutParams(dp(48), dp(54)))
        header.addView(TextView(this).apply {
            text = "设置"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
        })
        page.addView(header)
        fun row(title: String, detail: String, click: (() -> Unit)? = null) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(7), dp(18), dp(7)); background = rounded(this@MainActivity.paper, 17)
            }
            item.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(ink) })
            item.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(secondary) })
            click?.let { item.setOnClickListener { it() } }
            page.addView(item, LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(10) })
        }
        row("普通笔记", "直接打开 · 本地加密")
        row("隐私笔记", "系统锁屏密码或生物识别") { closeSettings(); enterPrivate() }
        row("从拾页导入", "选择旧版导出的迁移 ZIP") { pickTransfer() }
        row("关于拾页 Native", "Android 原生单机版 · OpenALC · MIT") {
            MaterialAlertDialogBuilder(this).setTitle("拾页 · Inspiration")
                .setMessage("Android 原生单机版 0.1.0\nGitHub: github.com/OpenALC/insp\nMIT License")
                .setPositiveButton("完成", null).show()
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.alpha = 0f; page.translationX = dp(24).toFloat()
        page.animate().alpha(1f).translationX(0f).setDuration(220).start()
    }

    private fun closeSettings() {
        val page = settingsOverlay ?: return
        settingsOverlay = null
        page.animate().alpha(0f).translationX(dp(24).toFloat()).setDuration(190)
            .withEndAction { root.removeView(page) }.start()
    }

    private fun leavePrivate() {
        privateGeneration++
        privateArea = false; groupId = null; allNotes = emptyList(); renderCards()
        io.execute { store.lockPrivate() }
        refresh()
    }

    private fun enterPrivate() {
        authenticate { privateArea = true; groupId = null; refresh() }
    }

    private fun authenticate(success: () -> Unit) {
        val manager = getSystemService(BiometricManager::class.java)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("请先设置系统锁屏密码或生物识别")
            return
        }
        BiometricPrompt.Builder(this).setTitle("解锁隐私笔记")
            .setSubtitle("使用系统锁屏密码或生物识别")
            .setAllowedAuthenticators(authenticators).build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    io.execute { store.unlockPrivate(); runOnUiThread(success) }
                }
            })
    }

    override fun onStop() {
        super.onStop()
        persistNow()
        io.execute { store.lockPrivate() }
        if (privateArea) {
            privateGeneration++
            privateArea = false; groupId = null
            editor.visibility = View.GONE; active = null; bodyInput = null; mindPanel = null
            allNotes = emptyList(); renderCards()
            refresh()
        }
    }

    @Deprecated("Activity back callback")
    override fun onBackPressed() {
        when {
            settingsOverlay != null -> closeSettings()
            drawerOverlay != null -> closeDrawer()
            editor.visibility == View.VISIBLE -> closeEditor()
            privateArea -> leavePrivate()
            else -> super.onBackPressed()
        }
    }

    private inner class Cards : RecyclerView.Adapter<Cards.Holder>() {
        private var notes = listOf<NativeNote>()

        inner class Holder(val card: MaterialCardView, val title: TextView, val image: ImageView, val preview: TextView) : RecyclerView.ViewHolder(card)

        fun submit(items: List<NativeNote>) { notes = items; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = MaterialCardView(this@MainActivity).apply {
                radius = dp(19).toFloat(); cardElevation = 0f; setCardBackgroundColor(paper)
                strokeWidth = 0; useCompatPadding = false
            }
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
            }
            val title = TextView(this@MainActivity).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink); maxLines = 2 }
            val image = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(this@MainActivity.background, 12)
                clipToOutline = true
                visibility = View.GONE
            }
            val preview = TextView(this@MainActivity).apply { textSize = 14f; setTextColor(secondary); maxLines = 5; setLineSpacing(dp(3).toFloat(), 1f) }
            content.addView(title)
            content.addView(image, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(12) })
            content.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            card.addView(content)
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(7), dp(6), dp(7), dp(6)) }
            return Holder(card, title, image, preview)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val note = notes[position]
            holder.title.text = note.title
            holder.preview.text = if (note.kind == "richtext") {
                RichTextCodec.decode(note.body).apply {
                    for (index in length - 1 downTo 0) if (this[index] == '\uFFFC') replace(index, index + 1, "")
                }.takeIf { it.isNotBlank() } ?: "空白笔记"
            } else preview(note).ifBlank { "点此添加分支" }
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            val imageId = if (note.kind == "richtext") {
                val spans = JSONObject(note.body).optJSONArray("spans")
                (0 until (spans?.length() ?: 0)).firstNotNullOfOrNull { index ->
                    spans!!.getJSONObject(index).takeIf { it.optString("type") == "image" }?.optString("attachmentId")
                }
            } else null
            if (imageId != null) {
                holder.image.tag = note.id
                io.execute {
                    val image = decodeImage(store.image(note, imageId), dp(250))
                    runOnUiThread {
                        if (holder.image.tag == note.id) {
                            holder.image.setImageDrawable(image)
                            holder.image.visibility = View.VISIBLE
                        }
                    }
                }
            }
            holder.card.transitionName = "note-${note.id}"
            holder.card.setOnClickListener { openNote(note, holder.card) }
            holder.card.setOnLongClickListener { noteOptions(note); true }
        }

        override fun getItemCount() = notes.size
    }

    private fun action(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(ink); gravity = Gravity.CENTER
        background = rounded(Color.TRANSPARENT, 50)
        setOnClickListener { click() }
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink); gravity = Gravity.CENTER
        background = rounded(if (label.contains("灵感")) paper else 0xffe6f8df.toInt(), 28)
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun watcher(change: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change(s.toString()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

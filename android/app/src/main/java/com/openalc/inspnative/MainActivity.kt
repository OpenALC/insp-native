package com.openalc.inspnative

import android.app.Activity
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.LruCache
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.transition.MaterialContainerTransform
import androidx.transition.TransitionManager
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.File

class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: NativeStore
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var cards: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var filters: LinearLayout
    private lateinit var cardAdapter: Cards
    private var tabletSidebar: LinearLayout? = null
    private var tabletPlaceholder: TextView? = null
    private lateinit var headerTitle: TextView
    private lateinit var headerAction: TextView
    private lateinit var headerLayoutAction: TextView
    private lateinit var headerLayoutButton: FrameLayout
    private lateinit var headerLayoutIcon: NativeIcon
    private lateinit var headerMoreIcon: NativeIcon
    private lateinit var composeBar: LinearLayout
    private lateinit var composeNoteButton: TextView
    private lateinit var composeMindButton: TextView
    private var composeCompact = false
    private var composeAnimator: ValueAnimator? = null
    private lateinit var selectionBar: LinearLayout
    private val selectedIds = mutableSetOf<String>()
    private val thumbnails = object : LruCache<String, BitmapDrawable>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: BitmapDrawable) = value.bitmap.byteCount
    }
    private var selectionMode = false
    private var drawerOverlay: FrameLayout? = null
    private var settingsOverlay: LinearLayout? = null
    private var settingsParent: LinearLayout? = null
    private var aboutOpen = false
    private var selectedCard: View? = null
    private var active: NativeNote? = null
    private var bodyInput: EditText? = null
    private var titleInput: EditText? = null
    private var formatToolbar: View? = null
    private var doneButton: View? = null
    private var editingMode = false
    private val pendingStyles = mutableSetOf<String>()
    private val suppressedStyles = mutableSetOf<String>()
    private var pendingParagraph: String? = null
    private var mindPanel: MindMapPanel? = null
    private var saveTask: Runnable? = null
    private val undoHistory = ArrayDeque<SpannableStringBuilder>()
    private val redoHistory = ArrayDeque<SpannableStringBuilder>()
    private var historyTask: Runnable? = null
    private var editBurst = false
    private var restoringHistory = false
    private var pendingImageNoteId: String? = null
    private var pendingImagePrivate = false
    private var pendingExport: ExportedNote? = null
    private var groupId: String? = null
    private var privateArea = false
    private var privateGeneration = 0
    private var search = ""
    private var listLayout = false
    private var allNotes = listOf<NativeNote>()
    private var allGroups = listOf<NoteGroup>()
    private val green = 0xff54d81b.toInt()
    private val tablet by lazy { resources.configuration.smallestScreenWidthDp >= 600 }
    private val wideTablet by lazy { tablet && resources.configuration.screenWidthDp >= 900 }
    private val dark by lazy { resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private val ink by lazy { if (dark) 0xffd4d4d4.toInt() else Color.BLACK }
    private val paper by lazy { if (dark) 0xff20221f.toInt() else Color.WHITE }
    private val background by lazy { if (dark) 0xff101010.toInt() else 0xfff5f5f5.toInt() }
    private val secondary by lazy { if (dark) 0xff9fa79b.toInt() else 0xff616860.toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences("appearance", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        super.onCreate(savedInstanceState)
        listLayout = preferences.getBoolean("list_layout", false)
        store = NativeStore(this)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        root = FrameLayout(this).apply { id = View.generateViewId(); setBackgroundColor(this@MainActivity.background) }
        home = buildHome()
        editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper); visibility = View.GONE }
        if (tablet) {
            val sidebarWidth = if (wideTablet) dp(220) else 0
            val listWidth = if (wideTablet) dp(360) else dp(300)
            if (wideTablet) {
                tabletSidebar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(if (dark) 0xff171717.toInt() else 0xfff5f5f5.toInt()) }
                root.addView(tabletSidebar, FrameLayout.LayoutParams(sidebarWidth, -1, Gravity.START))
            }
            root.addView(home, FrameLayout.LayoutParams(listWidth, -1, Gravity.START).apply { leftMargin = sidebarWidth })
            val editorParams = FrameLayout.LayoutParams(-1, -1).apply { leftMargin = sidebarWidth + listWidth }
            tabletPlaceholder = TextView(this).apply {
                text = "选择一篇笔记，开始记录"; textSize = 19f; setTextColor(secondary); gravity = Gravity.CENTER
                setBackgroundColor(paper)
            }
            root.addView(tabletPlaceholder, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = sidebarWidth + listWidth })
            root.addView(editor, editorParams)
        } else {
            root.addView(home, FrameLayout.LayoutParams(-1, -1))
            root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            home.setPadding(0, bars.top, 0, bars.bottom)
            editor.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            tabletSidebar?.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val insets = root.rootWindowInsets
                if ((insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0) >
                    (insets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0) + dp(40)) {
                    window.insetsController?.hide(WindowInsets.Type.ime())
                    return
                }
                when {
                    settingsOverlay != null -> closeSettings()
                    drawerOverlay != null -> closeDrawer()
                    selectionMode -> clearSelection()
                    editor.visibility == View.VISIBLE && editingMode -> exitEditing()
                    editor.visibility == View.VISIBLE -> closeEditor()
                    privateArea -> leavePrivate()
                    else -> finishAffinity()
                }
            }
        })
        val appearance = if (dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(appearance,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        refresh()
    }

    private fun buildHome(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)) }
        if (!wideTablet) header.addView(iconButton("menu") { openDrawer() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerTitle = TextView(this).apply {
            text = "笔记"; textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        headerLayoutButton = FrameLayout(this).apply { tapFeedback(); setOnClickListener {
            if (selectionMode) {
                val visible = allNotes.filter { (groupId == null || if (groupId == "") it.groupId == null else it.groupId == groupId) &&
                    (search.isBlank() || it.title.contains(search, true) || preview(it).contains(search, true)) }
                if (selectedIds.size == visible.size) clearSelection()
                else { selectedIds.addAll(visible.map { it.id }); updateSelection(); cardAdapter.selectionChangedAll() }
            } else setListLayout(!listLayout)
        } }
        headerLayoutAction = TextView(this).apply { text = "全选"; textSize = 13f; setTextColor(ink); gravity = Gravity.CENTER; visibility = View.GONE }
        headerLayoutIcon = NativeIcon(this, if (listLayout) "grid" else "list", ink)
        headerLayoutButton.addView(headerLayoutAction, FrameLayout.LayoutParams(-1, -1))
        headerLayoutButton.addView(headerLayoutIcon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        header.addView(headerLayoutButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val moreButton = FrameLayout(this).apply { tapFeedback(); setOnClickListener { if (selectionMode) clearSelection() else listOptions() } }
        headerAction = TextView(this).apply { text = "取消"; textSize = 13f; setTextColor(ink); gravity = Gravity.CENTER; visibility = View.GONE }
        headerMoreIcon = NativeIcon(this, "more", ink)
        moreButton.addView(headerAction, FrameLayout.LayoutParams(-1, -1))
        moreButton.addView(headerMoreIcon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        header.addView(moreButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header)
        searchInput = EditText(this).apply {
            hint = "搜索笔记"; textSize = 15f; setSingleLine(true); setTextColor(ink); setHintTextColor(secondary)
            background = rounded(if (dark) 0xff30342e.toInt() else 0xffebeeea.toInt(), 17)
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(watcher { search = it; if (selectionMode) clearSelection(); renderCards() })
        }
        page.addView(searchInput, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(22), dp(8), dp(22), dp(14)) })
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filters = LinearLayout(this).apply { setPadding(dp(22), 0, dp(22), 0) }
        filterScroll.addView(filters)
        page.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(52)))
        cards = RecyclerView(this).apply {
            layoutManager = StaggeredGridLayoutManager(if (tablet || listLayout) 1 else 2, StaggeredGridLayoutManager.VERTICAL)
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            clipToPadding = false
            setPadding(dp(14), dp(16), dp(14), dp(18))
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                private var downX = 0f
                private var downY = 0f
                override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                        MotionEvent.ACTION_UP -> {
                            val dx = event.x - downX
                            if (kotlin.math.abs(dx) > dp(64) && kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY) * 1.5f) {
                                switchGroup(if (dx < 0) 1 else -1)
                                return true
                            }
                        }
                    }
                    return false
                }
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) setComposeCompact(true)
                }
                override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) setComposeCompact(false)
                }
            })
        }
        cardAdapter = Cards()
        cards.adapter = cardAdapter
        page.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f))
        composeBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(paper, 36)
            elevation = dp(8).toFloat()
        }
        val expanded = dp(if (tablet) 105 else 145)
        composeNoteButton = pill("＋  笔记") { create("richtext") }
        composeMindButton = pill("⌘  灵感笔记") { create("mindmap") }
        composeBar.addView(composeNoteButton, LinearLayout.LayoutParams(expanded, dp(48)))
        composeBar.addView(View(this).apply { setBackgroundColor(secondary) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { leftMargin = dp(5); rightMargin = dp(5) })
        composeBar.addView(composeMindButton, LinearLayout.LayoutParams(expanded, dp(48)))
        page.addView(composeBar, LinearLayout.LayoutParams(-2, dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18) })
        selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE
            background = rounded(paper, 24); elevation = dp(8).toFloat()
        }
        selectionBar.addView(selectionAction("移动到", "folder", ink) { moveSelected() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        selectionBar.addView(selectionAction("删除", "trash", 0xffbb4848.toInt()) { deleteSelected() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        page.addView(selectionBar, LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(22), 0, dp(22), dp(18)) })
        return page
    }

    private fun toggleSelection(id: String) {
        val entering = !selectionMode
        if (!selectionMode) selectionMode = true
        if (!selectedIds.add(id)) selectedIds.remove(id)
        if (selectedIds.isEmpty()) clearSelection() else {
            updateSelection()
            if (entering) cardAdapter.selectionChangedAll() else cardAdapter.selectionChanged(id)
        }
    }

    private fun updateSelection() {
        headerTitle.text = "已选 ${selectedIds.size} 项"
        headerLayoutAction.text = if (selectedIds.size == cardAdapter.itemCount) "取消" else "全选"
        headerLayoutAction.visibility = View.VISIBLE; headerLayoutIcon.visibility = View.GONE
        headerAction.visibility = View.VISIBLE; headerMoreIcon.visibility = View.GONE
        composeBar.visibility = View.GONE
        selectionBar.visibility = View.VISIBLE
    }

    private fun clearSelection() {
        selectedIds.clear(); selectionMode = false
        headerTitle.text = "笔记"
        headerLayoutAction.visibility = View.GONE; headerLayoutIcon.visibility = View.VISIBLE
        headerAction.visibility = View.GONE; headerMoreIcon.visibility = View.VISIBLE
        composeBar.visibility = View.VISIBLE; selectionBar.visibility = View.GONE
        setComposeCompact(false)
        cardAdapter.selectionChangedAll()
    }

    private fun setComposeCompact(compact: Boolean) {
        if (composeCompact == compact) return
        composeCompact = compact
        composeAnimator?.cancel()
        val start = composeNoteButton.layoutParams.width
        val end = dp(if (compact) 48 else if (tablet) 105 else 145)
        if (compact) { composeNoteButton.text = "＋"; composeMindButton.text = "⌘" }
        composeAnimator = ValueAnimator.ofInt(start, end).apply {
            duration = if (compact) 170 else 280
            if (!compact) interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { animator ->
                val width = animator.animatedValue as Int
                composeNoteButton.layoutParams = composeNoteButton.layoutParams.apply { this.width = width }
                composeMindButton.layoutParams = composeMindButton.layoutParams.apply { this.width = width }
            }
            if (!compact) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!composeCompact) {
                        composeNoteButton.text = "＋  笔记"; composeMindButton.text = "⌘  灵感笔记"
                    }
                }
            })
            start()
        }
    }

    private fun selectionAction(label: String, icon: String, color: Int, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; background = rounded(if (dark) 0xff30342e.toInt() else 0xfff0f0f0.toInt(), 17)
        tapFeedback(); setOnClickListener { click() }
        addView(NativeIcon(this@MainActivity, icon, color), LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(TextView(this@MainActivity).apply { text = label; textSize = 15f; setTextColor(color) },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    }

    private fun switchGroup(step: Int) {
        if (privateArea || selectionMode) return
        val ids = listOf<String?>(null, "") + allGroups.map { it.id }
        val next = (ids.indexOf(groupId) + step).coerceIn(0, ids.lastIndex)
        if (ids[next] == groupId) return
        groupId = ids[next]
        cards.alpha = 0.45f
        cards.translationX = (if (step > 0) dp(22) else -dp(22)).toFloat()
        renderFilters(); renderCards(); cards.scrollToPosition(0)
        cards.animate().alpha(1f).translationX(0f).setDuration(220).start()
    }

    private fun moveSelected() {
        val names = listOf("未分组") + allGroups.map { it.name }
        showSheet("移动到", names.toTypedArray()) { index ->
            val destination = if (index == 0) null else allGroups[index - 1].id
            val notes = allNotes.filter { it.id in selectedIds }.map { it.copy(groupId = destination) }
            io.execute { notes.forEach(store::save); runOnUiThread { clearSelection(); refresh() } }
        }
    }

    private fun deleteSelected() {
        showSheet("删除 ${selectedIds.size} 篇笔记？", arrayOf("删除笔记")) {
            val ids = selectedIds.toList()
            io.execute { ids.forEach(store::delete); runOnUiThread { clearSelection(); refresh() } }
        }
    }

    private fun refresh() {
        val area = privateArea
        val generation = privateGeneration
        io.execute {
            val groups = store.groups()
            val notes = store.notes(area)
            runOnUiThread {
                if (generation == privateGeneration && area == privateArea) {
                    allGroups = groups; allNotes = notes; renderFilters(); renderCards(); renderTabletSidebar()
                }
            }
        }
    }

    private fun renderFilters() {
        filters.removeAllViews()
        val choices = listOf(null to if (privateArea) "隐私笔记" else "全部") +
            (if (privateArea) emptyList() else listOf("" to "未分组")) +
            (if (privateArea) emptyList() else allGroups.map { it.id to it.name })
        for ((id, label) in choices) {
            filters.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(ink); gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                background = rounded(if (groupId == id) paper else Color.TRANSPARENT, 24)
                setOnClickListener { if (selectionMode) clearSelection(); groupId = id; renderFilters(); renderCards() }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(7) })
        }
    }

    private fun renderCards() {
        val notes = allNotes.filter { (groupId == null || if (groupId == "") it.groupId == null else it.groupId == groupId) &&
            (search.isBlank() || it.title.contains(search, true) || preview(it).contains(search, true)) }
        cardAdapter.submit(notes)
    }

    private fun setListLayout(value: Boolean) {
        listLayout = value
        getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("list_layout", value).apply()
        cards.layoutManager = StaggeredGridLayoutManager(if (tablet || value) 1 else 2, StaggeredGridLayoutManager.VERTICAL)
        headerLayoutButton.removeView(headerLayoutIcon)
        headerLayoutIcon = NativeIcon(this, if (value) "grid" else "list", ink)
        headerLayoutButton.addView(headerLayoutIcon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
    }

    private fun preview(note: NativeNote): String = if (note.kind == "mindmap") {
        val children = JSONObject(note.body).getJSONObject("root").getJSONArray("children")
        (0 until minOf(3, children.length())).joinToString("  ·  ") { children.getJSONObject(it).getString("text") }
    } else RichTextCodec.plain(note.body).replace("\uFFFC", "图片").trim().take(180)

    private fun create(kind: String) {
        io.execute {
            val note = store.create(kind, groupId?.takeIf { it.isNotEmpty() }, privateArea)
            runOnUiThread { allNotes = listOf(note) + allNotes; searchInput.setText(""); renderCards(); cards.scrollToPosition(0); openNote(note, null, true) }
        }
    }

    private fun openNote(note: NativeNote, card: View?, startEditing: Boolean = false) {
        val generation = privateGeneration
        active = note.copy()
        selectedCard = card
        if (note.kind == "richtext") {
            val document = RichTextCodec.decode(note.body, dark) { id -> thumbnails.get(id) ?: imagePlaceholder() }
            showEditor(active!!, document, card, startEditing)
            val imageIds = JSONObject(note.body).optJSONArray("spans")?.let { spans ->
                (0 until spans.length()).mapNotNull { index ->
                    spans.getJSONObject(index).takeIf { it.optString("type") == "image" }?.optString("attachmentId")
                }.distinct()
            }.orEmpty()
            if (imageIds.isNotEmpty()) io.execute {
                if (generation != privateGeneration) return@execute
                val images = imageIds.map { it to imageDrawable(note, it) }
                runOnUiThread {
                    if (generation != privateGeneration || active?.id != note.id) return@runOnUiThread
                    val text = bodyInput?.text ?: return@runOnUiThread
                    for ((id, drawable) in images) {
                        for (span in text.getSpans(0, text.length, ImageSpan::class.java).filter { it.source == id }) {
                            val start = text.getSpanStart(span)
                            val end = text.getSpanEnd(span)
                            text.removeSpan(span)
                            text.setSpan(ImageSpan(drawable, id), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }
        } else showEditor(active!!, null, card, startEditing)
    }

    private fun imagePlaceholder(): Drawable = rounded(if (dark) 0xff30342e.toInt() else 0xffebeeea.toInt(), 12)
        .apply { setBounds(0, 0, dp(240), dp(120)) }

    private fun showEditor(note: NativeNote, document: Spannable?, card: View?, startEditing: Boolean) {
        undoHistory.clear(); redoHistory.clear(); editBurst = false
        editingMode = false; pendingStyles.clear(); suppressedStyles.clear(); pendingParagraph = null
        editor.removeAllViews()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        top.addView(action("‹") { closeEditor() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = EditText(this).apply {
            setSingleLine(true); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            background = null; setText(note.title); clearFocus()
            isFocusableInTouchMode = false; isCursorVisible = false
            setOnTouchListener { view, event ->
                if (!editingMode && event.actionMasked == MotionEvent.ACTION_UP) {
                    val field = view as EditText
                    enterEditing(field, field.getOffsetForPosition(event.x, event.y))
                    true
                } else false
            }
            addTextChangedListener(watcher {
                note.title = it.ifBlank { if (note.kind == "mindmap") "未命名的灵感笔记" else "未命名笔记" }
                mindPanel?.renameRoot(note.title)
                scheduleSave()
            })
        }
        titleInput = title
        top.addView(title, LinearLayout.LayoutParams(0, dp(50), 1f))
        doneButton = iconButton("check") { exitEditing() }.apply { visibility = View.GONE }
        top.addView(doneButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (note.kind == "mindmap") {
            val toggle = ImageView(this).apply {
                setImageResource(R.drawable.ic_mind_tree)
                setColorFilter(ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    setImageResource(if (mindPanel?.toggleMode() == true) R.drawable.ic_mind_list else R.drawable.ic_mind_tree)
                    setColorFilter(ink)
                }
            }
            top.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        top.addView(action("⋯") { noteOptions(note) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        editor.addView(top)
        if (note.kind == "richtext") {
            val input = EditText(this).apply {
                gravity = Gravity.TOP; textSize = 18f; setTextColor(ink); setHintTextColor(secondary)
                background = null; hint = "从这里开始记录…"
                setPadding(dp(24), dp(22), dp(24), dp(24))
                setText(document ?: RichTextCodec.decode(note.body, dark))
                clearFocus()
                isFocusableInTouchMode = false
                isCursorVisible = false
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                        if (!restoringHistory) captureUndo(this@apply)
                    }
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (!restoringHistory) {
                            if (count > 0) pendingStyles.forEach { command ->
                                val spans = this@apply.text.getSpans(start, start + count, Any::class.java)
                                if (spans.none { matchesFormat(it, command) &&
                                        this@apply.text.getSpanStart(it) <= start &&
                                        this@apply.text.getSpanEnd(it) >= start + count }) {
                                    this@apply.text.setSpan(newFormatSpan(command), start, start + count, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                                }
                            }
                            if (count > 0) suppressedStyles.forEach { command ->
                                stripFormat(this@apply.text, start, start + count, command)
                            }
                            if (count > 0 && pendingParagraph != null) {
                                val command = pendingParagraph!!
                                val lineStart = if (start == 0) 0 else this@apply.text.lastIndexOf('\n', start - 1) + 1
                                val lineEnd = this@apply.text.indexOf('\n', start + count).let { if (it < 0) this@apply.length() else it }
                                this@apply.text.setSpan(newFormatSpan(command), lineStart, lineEnd, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                                if (command == "quote") RichTextCodec.styleComment(this@apply.text, lineStart, lineEnd, dark)
                                pendingParagraph = null
                            }
                            scheduleSave(); settleHistory()
                        }
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val field = view as EditText
                        val offset = field.getOffsetForPosition(event.x, event.y)
                        if (!editingMode) { enterEditing(field, offset); return@setOnTouchListener true }
                        val start = if (offset == 0) 0 else field.text.lastIndexOf('\n', offset - 1) + 1
                        if (offset <= start + 1 && start < field.length() && field.text[start] in "☐☑") {
                            field.text.replace(start, start + 1, if (field.text[start] == '☐') "☑" else "☐")
                            scheduleSave()
                            return@setOnTouchListener true
                        }
                    }
                    false
                }
            }
            bodyInput = input
            mindPanel = null
            editor.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
            formatToolbar = formatBar().apply { visibility = View.GONE }
            editor.addView(formatToolbar, LinearLayout.LayoutParams(-1, dp(57)))
        } else {
            bodyInput = null
            formatToolbar = null
            mindPanel = MindMapPanel(this, note, { scheduleSave() }, { enterMindEditing() })
                .also { editor.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        }
        editor.isFocusableInTouchMode = true
        editor.requestFocus()
        if (card != null && !tablet) {
            val transition = MaterialContainerTransform().apply {
                startView = card; endView = editor; addTarget(editor)
                drawingViewId = android.R.id.content; duration = 320
                scrimColor = Color.TRANSPARENT
            }
            TransitionManager.beginDelayedTransition(root, transition)
            card.visibility = View.INVISIBLE
        }
        editor.visibility = View.VISIBLE
        tabletPlaceholder?.visibility = View.GONE
        if (startEditing) {
            if (note.kind == "richtext") inputForEditing() else editor.post { enterMindEditing() }
        }
        if (tablet) { editor.alpha = 0f; editor.translationX = dp(18).toFloat(); editor.animate().alpha(1f).translationX(0f).setDuration(220).start() }
    }

    private fun inputForEditing() { bodyInput?.post { bodyInput?.let { enterEditing(it, it.length()) } } }

    private fun enterEditing(input: EditText, offset: Int) {
        if (editingMode) return
        editingMode = true
        doneButton?.visibility = View.VISIBLE
        formatToolbar?.visibility = View.VISIBLE
        titleInput?.isFocusableInTouchMode = true
        titleInput?.isCursorVisible = true
        bodyInput?.isFocusableInTouchMode = true
        bodyInput?.isCursorVisible = true
        input.isFocusableInTouchMode = true
        input.isCursorVisible = true
        input.requestFocus()
        input.setSelection(offset.coerceIn(0, input.length()))
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun enterMindEditing() {
        if (editingMode) return
        editingMode = true
        doneButton?.visibility = View.VISIBLE
        mindPanel?.setEditing(true)
    }

    private fun exitEditing() {
        if (!editingMode) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
        bodyInput?.clearFocus()
        bodyInput?.isFocusableInTouchMode = false
        bodyInput?.isCursorVisible = false
        titleInput?.clearFocus()
        titleInput?.isFocusableInTouchMode = false
        titleInput?.isCursorVisible = false
        mindPanel?.setEditing(false)
        editor.requestFocus()
        editingMode = false
        doneButton?.visibility = View.GONE
        formatToolbar?.visibility = View.GONE
        pendingStyles.clear(); suppressedStyles.clear(); pendingParagraph = null
        persistNow()
    }

    private fun closeEditor() {
        window.insetsController?.hide(WindowInsets.Type.ime())
        bodyInput?.clearFocus()
        editor.requestFocus()
        persistNow()
        val finished = active?.copy()
        if (finished != null) {
            allNotes = allNotes.map { if (it.id == finished.id) finished else it }
            renderCards()
        }
        val card = selectedCard
        if (card != null && card.parent != null && !tablet) {
            val transition = MaterialContainerTransform().apply {
                startView = editor; endView = card; addTarget(card)
                drawingViewId = android.R.id.content; duration = 270
                scrimColor = Color.TRANSPARENT
            }
            TransitionManager.beginDelayedTransition(root, transition)
            card.visibility = View.VISIBLE
        }
        editor.visibility = View.GONE
        tabletPlaceholder?.visibility = View.VISIBLE
        active = null; bodyInput = null; titleInput = null; mindPanel = null; selectedCard = null
        formatToolbar = null; doneButton = null; editingMode = false; pendingStyles.clear(); suppressedStyles.clear(); pendingParagraph = null
        undoHistory.clear(); redoHistory.clear(); historyTask?.let(handler::removeCallbacks); editBurst = false
        if (!tablet && card != null && card.parent != null) handler.postDelayed({ refresh() }, 280) else refresh()
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
        val commands = listOf(
            "↶" to "undo", "↷" to "redo",
            "B" to "bold", "I" to "italic", "U" to "underline", "" to "strike", "▧" to "highlight",
            "H" to "heading", "❝" to "quote", "•" to "bullet", "☐" to "task", "―" to "divider",
            "≡" to "left", "☰" to "center", "≡›" to "right", "→" to "indentPlus", "←" to "indentMinus",
            "" to "link", "" to "image",
        )
        for ((label, command) in commands) {
            val button = if (command in setOf("strike", "link", "image")) iconButton(command) { format(command) }
                else action(label) { format(command) }
            row.addView(button, LinearLayout.LayoutParams(dp(50), dp(48)))
        }
        scroll.addView(row)
        return scroll
    }

    private fun format(command: String) {
        if (command == "undo") { restoreHistory(undoHistory, redoHistory); return }
        if (command == "redo") { restoreHistory(redoHistory, undoHistory); return }
        if (command == "image") {
            pendingImageNoteId = active?.id
            pendingImagePrivate = active?.private == true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(intent, 700)
            return
        }
        val input = bodyInput ?: return
        val selectionStart = input.selectionStart.coerceAtLeast(0)
        val selectionEnd = input.selectionEnd.coerceAtLeast(selectionStart)
        if (command == "task") {
            val start = if (selectionStart == 0) 0 else input.text.lastIndexOf('\n', selectionStart - 1) + 1
            captureUndo(input)
            when (input.text.getOrNull(start)) {
                '☐', '☑' -> input.text.delete(start, (start + 2).coerceAtMost(input.length()))
                else -> input.text.insert(start, "☐ ")
            }
            scheduleSave(); settleHistory()
            return
        }
        if (command == "divider") {
            captureUndo(input)
            input.text.insert(selectionStart, "\n────────────\n")
            scheduleSave()
            return
        }
        if (command == "link") { linkDialog(input, selectionStart, selectionEnd); return }
        val paragraph = command in setOf("quote", "bullet", "heading", "left", "center", "right", "indentPlus", "indentMinus")
        val start = if (paragraph) (if (selectionStart == 0) -1 else input.text.lastIndexOf('\n', selectionStart - 1)).let { it + 1 } else selectionStart
        val end = if (paragraph) input.text.indexOf('\n', selectionEnd).let { if (it < 0) input.length() else it } else selectionEnd
        if (start == end) {
            if (paragraph) pendingParagraph = if (pendingParagraph == command) null else command
            else {
                val inherited = input.text.getSpans(0, input.length(), Any::class.java).any {
                    matchesFormat(it, command) && input.text.getSpanStart(it) < start && input.text.getSpanEnd(it) >= start
                }
                if (command in pendingStyles) pendingStyles.remove(command)
                else if (command in suppressedStyles) suppressedStyles.remove(command)
                else if (inherited) suppressedStyles.add(command)
                else pendingStyles.add(command)
            }
            return
        }
        if (command == "indentPlus" || command == "indentMinus") {
            captureUndo(input)
            val current = input.text.getSpans(start, end, LeadingMarginSpan.Standard::class.java)
            val margin = current.maxOfOrNull { it.getLeadingMargin(true) } ?: 0
            current.forEach(input.text::removeSpan)
            val next = (margin + if (command == "indentPlus") dp(24) else -dp(24)).coerceIn(0, dp(96))
            if (next > 0) input.text.setSpan(LeadingMarginSpan.Standard(next), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            scheduleSave(); settleHistory()
            return
        }
        captureUndo(input)
        val spans = input.text.getSpans(start, end, Any::class.java).filter { matchesFormat(it, command) &&
            input.text.getSpanStart(it) < end && input.text.getSpanEnd(it) > start }
        val covered = spans.any { input.text.getSpanStart(it) <= start && input.text.getSpanEnd(it) >= end &&
            (command !in setOf("left", "center", "right") || (it as AlignmentSpan).alignment == (newFormatSpan(command) as AlignmentSpan).alignment) }
        stripFormat(input.text, start, end, command)
        if (!covered) {
            input.text.setSpan(newFormatSpan(command), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (command == "quote") RichTextCodec.styleComment(input.text, start, end, dark)
        } else if (command == "quote") RichTextCodec.removeCommentStyle(input.text, start, end, dark)
        scheduleSave(); settleHistory()
    }

    private fun stripFormat(text: Editable, start: Int, end: Int, command: String) {
        val spans = text.getSpans(start, end, Any::class.java).filter {
            matchesFormat(it, command) && text.getSpanStart(it) < end && text.getSpanEnd(it) > start
        }
        for (existing in spans) {
            val oldStart = text.getSpanStart(existing)
            val oldEnd = text.getSpanEnd(existing)
            text.removeSpan(existing)
            if (oldStart < start) text.setSpan(existing, oldStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (oldEnd > end) {
                val continuation = if (existing is AlignmentSpan) AlignmentSpan.Standard(existing.alignment) else newFormatSpan(command)
                text.setSpan(continuation, end, oldEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun matchesFormat(span: Any, command: String): Boolean = when (command) {
        "bold" -> span is StyleSpan && span.style == Typeface.BOLD
        "italic" -> span is StyleSpan && span.style == Typeface.ITALIC
        "underline" -> span is UnderlineSpan
        "strike" -> span is StrikethroughSpan
        "highlight" -> span is BackgroundColorSpan
        "heading" -> span is RelativeSizeSpan
        "quote" -> span is QuoteSpan
        "bullet" -> span is BulletSpan
        "left", "center", "right" -> span is AlignmentSpan
        else -> false
    }

    private fun newFormatSpan(command: String): Any = when (command) {
        "bold" -> StyleSpan(Typeface.BOLD)
        "italic" -> StyleSpan(Typeface.ITALIC)
        "underline" -> UnderlineSpan()
        "strike" -> StrikethroughSpan()
        "highlight" -> BackgroundColorSpan(if (dark) 0xff51451c.toInt() else 0xffffeea3.toInt())
        "heading" -> RelativeSizeSpan(1.35f)
        "quote" -> QuoteSpan(green)
        "bullet" -> BulletSpan(dp(12), green)
        "center" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
        "right" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)
        else -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)
    }

    private fun captureUndo(input: EditText) {
        if (editBurst) return
        undoHistory.addLast(SpannableStringBuilder(input.text))
        if (undoHistory.size > 40) undoHistory.removeFirst()
        redoHistory.clear()
        editBurst = true
    }

    private fun settleHistory() {
        historyTask?.let(handler::removeCallbacks)
        historyTask = Runnable { editBurst = false }.also { handler.postDelayed(it, 450) }
    }

    private fun restoreHistory(from: ArrayDeque<SpannableStringBuilder>, to: ArrayDeque<SpannableStringBuilder>) {
        val input = bodyInput ?: return
        if (from.isEmpty()) return
        historyTask?.let(handler::removeCallbacks); editBurst = false
        to.addLast(SpannableStringBuilder(input.text))
        val previous = from.removeLast()
        val cursor = input.selectionStart.coerceIn(0, previous.length)
        restoringHistory = true
        input.setText(previous)
        input.setSelection(cursor)
        restoringHistory = false
        scheduleSave()
    }

    private fun linkDialog(input: EditText, start: Int, end: Int) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0)
        }
        val label = EditText(this).apply {
            hint = "显示文字"; setSingleLine(true); setText(input.text.substring(start, end))
            setTextColor(ink); setHintTextColor(secondary)
            background = rounded(if (dark) 0xff30342e.toInt() else 0xfff0f0f0.toInt(), 12)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val address = EditText(this).apply {
            hint = "https://example.com"; setSingleLine(true); setTextColor(ink); setHintTextColor(secondary)
            background = rounded(if (dark) 0xff30342e.toInt() else 0xfff0f0f0.toInt(), 12)
            setPadding(dp(14), 0, dp(14), 0)
        }
        fields.addView(label, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(12) })
        fields.addView(address, LinearLayout.LayoutParams(-1, dp(50)))
        showFormSheet("插入链接", fields, "完成") {
                val text = label.text.toString().ifBlank { address.text.toString() }
                val url = address.text.toString().trim()
                if (url.isNotBlank() && text.isNotBlank()) {
                    input.text.replace(start, end, text)
                    input.text.setSpan(URLSpan(url), start, start + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    scheduleSave()
                }
            }
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            io.execute {
                try {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                        it.moveToFirst(); it.getString(0)
                    }
                    val bundle = contentResolver.openInputStream(uri)!!.use { input ->
                        if (name.endsWith(".zip", true)) TransferImport.read(input)
                        else ExternalImport.read(name, input.readBytes())
                    }
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
        if (requestCode == 702) {
            val file = pendingExport
            pendingExport = null
            if (resultCode == Activity.RESULT_OK && file != null && data?.data != null) io.execute {
                contentResolver.openOutputStream(data.data!!, "wt")!!.use { it.write(file.bytes) }
                runOnUiThread { toast("已导出 ${file.filename}") }
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
                            val document = RichTextCodec.decode(note.body, dark) { imageDrawable(note, it) }
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
        val items = arrayOf("移动到", "隐私", "分享与导出", "删除笔记")
        showSheet("笔记选项", items) { index -> when (index) {
            0 -> groupPicker(note)
            1 -> privacyOptions(note)
            2 -> shareOptions(note)
            3 -> showSheet("删除这篇笔记？", arrayOf("删除笔记")) {
                io.execute { store.delete(note.id); runOnUiThread { closeEditor() } }
            }
        } }
    }

    private fun privacyOptions(note: NativeNote) {
        showSheet("隐私设置", arrayOf("普通笔记", "隐私笔记"), if (note.private) 1 else 0) { choice ->
            val next = choice == 1
            if (next != note.private) authenticate {
                persistNow()
                io.execute { store.setPrivacy(note, next); runOnUiThread { closeEditor(); leavePrivate() } }
            }
        }
    }

    private fun contentText(note: NativeNote) = NativeExport.plain(note)

    private fun shareOptions(note: NativeNote) {
        val options = arrayOf("以纯文本分享", "以图片分享", "导出 Markdown", "导出 Word", "导出 PDF")
        showSheet("分享与导出", options) { index ->
            when (index) {
                0 -> { persistNow(); share(note) }
                1 -> exportNote(note, "png")
                2 -> exportNote(note, "md")
                3 -> exportNote(note, "docx")
                4 -> exportNote(note, "pdf")
            }
        }
    }

    private fun exportNote(note: NativeNote, format: String) {
        persistNow()
        val copy = note.copy()
        io.execute {
            try {
                val image: (String) -> ByteArray = { store.image(copy, it) }
                val file = when (format) {
                    "png" -> NativeExport.png(copy, image)
                    "md" -> NativeExport.markdown(copy, image)
                    "docx" -> NativeExport.docx(copy, image)
                    else -> NativeExport.pdf(copy, image)
                }
                runOnUiThread { if (format == "png") shareImage(file) else saveExport(file) }
            } catch (error: Exception) {
                runOnUiThread { toast("导出失败：${error.message}") }
            }
        }
    }

    private fun saveExport(file: ExportedNote) {
        pendingExport = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = file.mimeType
            putExtra(Intent.EXTRA_TITLE, file.filename)
        }, 702)
    }

    private fun shareImage(file: ExportedNote) {
        val directory = File(cacheDir, "shared").apply { mkdirs() }
        val saved = File(directory, file.filename).apply { writeBytes(file.bytes) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", saved)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享笔记图片"))
    }

    private fun share(note: NativeNote) {
        val text = "${note.title}\n\n${contentText(note)}"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(note.title, text))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "分享笔记"))
    }

    private fun groupPicker(note: NativeNote) {
        val names = listOf("未分组") + allGroups.map { it.name }
        showSheet("移动到", names.toTypedArray()) { index ->
            note.groupId = if (index == 0) null else allGroups[index - 1].id
            io.execute { store.save(note.copy()); runOnUiThread { refresh() } }
        }
    }

    private fun listOptions() {
        showSheet("笔记操作", arrayOf("新建分组", "隐私笔记")) { index -> when (index) {
            0 -> groupDialog()
            1 -> enterPrivate()
        } }
    }

    private fun iconFor(label: String) = when {
        label.contains("删除") -> "trash"
        label.contains("隐私") -> "lock"
        label.contains("分组") || label.contains("移动") -> "folder"
        label.contains("图片") -> "image"
        label.contains("导出") || label.contains("下载") || label.contains("导入") || label.contains("更新") -> "download"
        label.contains("分享") -> "share"
        label.contains("复制") -> "document"
        label.contains("主题") || label.contains("外观") -> "palette"
        label.contains("版本") || label.contains("关于") -> "info"
        label.contains("协议") -> "shield"
        label.contains("布局") -> "grid"
        label.contains("设置") -> "settings"
        label.contains("笔记") -> "inbox"
        else -> "document"
    }

    private fun View.tapFeedback() {
        foreground = RippleDrawable(ColorStateList.valueOf(if (dark) 0x6654d81b else 0x4454d81b), null,
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(28).toFloat(); setColor(Color.WHITE) })
    }

    fun showSheet(title: String, items: Array<String>, selectedIndex: Int = -1, selected: (Int) -> Unit) {
        window.insetsController?.hide(WindowInsets.Type.ime())
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = rounded(paper, 28)
        }
        content.addView(TextView(this).apply {
            text = title; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            setPadding(dp(12), dp(12), dp(12), dp(14))
        })
        items.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), 0, dp(17), 0)
                background = rounded(if (dark) 0xff30342e.toInt() else 0xfff4f6f2.toInt(), 16)
                tapFeedback(); setOnClickListener { sheet.dismiss(); selected(index) }
            }
            row.addView(NativeIcon(this, iconFor(label), if (label.startsWith("删除")) 0xffbb4848.toInt() else ink), LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(TextView(this).apply {
                text = label; textSize = 16f; setTextColor(if (label.startsWith("删除")) 0xffbb4848.toInt() else ink)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(14) })
            if (index == selectedIndex) row.addView(NativeIcon(this, "check", green), LinearLayout.LayoutParams(dp(20), dp(20)))
            content.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
        }
        content.addView(TextView(this).apply {
            text = "取消"; textSize = 16f; setTextColor(secondary); gravity = Gravity.CENTER
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        sheet.setContentView(content)
        sheet.show()
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun showFormSheet(title: String, fields: View, actionLabel: String, submit: () -> Unit) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
            background = rounded(paper, 28)
        }
        content.addView(TextView(this).apply {
            text = title; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink)
            setPadding(dp(12), dp(12), dp(12), dp(14))
        })
        content.addView(fields, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        actions.addView(action("取消") { sheet.dismiss() }, LinearLayout.LayoutParams(dp(76), dp(48)))
        actions.addView(action(actionLabel) { sheet.dismiss(); submit() }, LinearLayout.LayoutParams(dp(76), dp(48)))
        content.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))
        sheet.setContentView(content)
        sheet.show()
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }

    fun showTextInputSheet(title: String, hint: String, initial: String = "", submit: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine(true); this.hint = hint; setText(initial); selectAll()
            setTextColor(ink); setHintTextColor(secondary)
            background = rounded(if (dark) 0xff30342e.toInt() else 0xfff4f6f2.toInt(), 14)
            setPadding(dp(16), 0, dp(16), 0)
        }
        showFormSheet(title, input.apply { minimumHeight = dp(52) }, "完成") {
            input.text.toString().trim().takeIf(String::isNotEmpty)?.let(submit)
        }
    }

    private fun pickTransfer() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "text/plain", "text/markdown", "application/octet-stream"))
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
        showTextInputSheet("新建分组", "分组名称") { name ->
            io.execute { store.addGroup(name); runOnUiThread { refresh() } }
        }
    }

    private fun openDrawer() {
        val overlay = FrameLayout(this)
        drawerOverlay = overlay
        val shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; setOnClickListener { closeDrawer() } }
        overlay.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val width = minOf((resources.displayMetrics.widthPixels * .56667f).toInt(), dp(220))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xff171717.toInt() else 0xfff5f5f5.toInt())
            translationX = -width.toFloat()
            val bars = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            setPadding(dp(13), bars?.top ?: 0, dp(13), bars?.bottom ?: 0)
        }
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        panel.addView(TextView(this).apply {
            text = "拾页 · Native"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
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
        fun row(label: String, icon: String, count: String = "", click: () -> Unit): LinearLayout {
            val view = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(8), 0)
                background = rounded(Color.TRANSPARENT, 14); tapFeedback()
                setOnClickListener { closeDrawer(); click() }
            }
            view.addView(NativeIcon(this, icon, ink), LinearLayout.LayoutParams(dp(18), dp(18)))
            view.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(ink); isSingleLine = true },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            if (count.isNotEmpty()) view.addView(TextView(this).apply { text = count; textSize = 12f; setTextColor(secondary) })
            links.addView(view, LinearLayout.LayoutParams(-1, dp(52)))
            return view
        }
        links.addView(TextView(this).apply { text = "笔记空间"; textSize = 12f; setTextColor(secondary); setPadding(dp(12), dp(22), 0, dp(8)) })
        row("全部笔记", "inbox", if (privateArea) "" else "${allNotes.size}") { leavePrivate() }
        row("未分组", "archive", if (privateArea) "" else "${allNotes.count { it.groupId == null }}") {
            leavePrivate(); groupId = ""; renderFilters(); renderCards()
        }
        row("隐私笔记", "lock", if (privateArea) "${allNotes.size}" else "⌑") { enterPrivate() }
        links.addView(TextView(this).apply {
            text = "分组"; textSize = 13f; setTextColor(secondary)
            setPadding(dp(16), dp(22), 0, dp(9))
        })
        for (group in allGroups) row(group.name, "folder", "${allNotes.count { it.groupId == group.id }}") {
            leavePrivate(); groupId = group.id; renderFilters(); renderCards()
        }.setOnLongClickListener { closeDrawer(); groupOptions(group); true }
        row("新建分组", "plus") { groupDialog() }
        panel.addView(TextView(this).apply {
            text = "⚙  设置"; textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener { closeDrawer(); openSettings() }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        shade.animate().alpha(if (dark) 0.7f else 0.38f).setDuration(230).start()
        panel.animate().translationX(0f).setDuration(260).start()
    }

    private fun renderTabletSidebar() {
        val sidebar = tabletSidebar ?: return
        sidebar.removeAllViews()
        sidebar.addView(TextView(this).apply {
            text = "拾页 · Native\nby OpenALC"; textSize = 17f; setTextColor(ink)
            setPadding(dp(22), dp(25), dp(12), dp(26))
        })
        val scroll = ScrollView(this)
        val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        scroll.addView(links)
        sidebar.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(label: String, click: () -> Unit): TextView {
            val view = TextView(this).apply {
                text = label; textSize = 15f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(8), 0); background = rounded(Color.TRANSPARENT, 14)
                setOnClickListener { click() }
            }
            links.addView(view, LinearLayout.LayoutParams(-1, dp(52)))
            return view
        }
        row("全部笔记") { leavePrivate() }
        row("隐私笔记") { enterPrivate() }
        links.addView(TextView(this).apply { text = "分组"; textSize = 13f; setTextColor(secondary); setPadding(dp(16), dp(20), 0, dp(9)) })
        for (group in allGroups) row(group.name) { leavePrivate(); groupId = group.id; renderFilters(); renderCards() }
            .setOnLongClickListener { groupOptions(group); true }
        row("＋  新建分组") { groupDialog() }
        sidebar.addView(TextView(this).apply {
            text = "⚙  设置"; textSize = 15f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), 0, 0, 0); setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun closeDrawer() {
        val overlay = drawerOverlay ?: return
        drawerOverlay = null
        val panel = overlay.getChildAt(1)
        overlay.getChildAt(0).animate().alpha(0f).setDuration(200).start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220).withEndAction { root.removeView(overlay) }.start()
    }

    private fun groupOptions(group: NoteGroup) {
        showSheet(group.name, arrayOf("重命名", "删除分组")) { option ->
            if (option == 0) {
                showTextInputSheet("重命名分组", "分组名称", group.name) { name ->
                    io.execute { store.renameGroup(group.copy(name = name)); runOnUiThread { refresh() } }
                }
            } else showSheet("删除分组？笔记会移到未分组", arrayOf("删除分组")) {
                io.execute { store.removeGroup(group.id); runOnUiThread { groupId = null; refresh() } }
            }
        }
    }

    private fun openSettings() {
        aboutOpen = false
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        page.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(title: String, detail: String = "", click: (() -> Unit)? = null) = settingsRow(content, title, detail, click)
        fun section(title: String) {
            content.addView(TextView(this).apply {
                text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(secondary)
                setPadding(dp(8), dp(18), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(44)))
        }
        section("外观")
        val currentTheme = getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        row("外观", when (currentTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            else -> "跟随系统"
        }) {
            showSheet("主题", arrayOf("跟随系统", "浅色", "深色")) { choice ->
                val mode = when (choice) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("theme", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                closeSettings(); openSettings()
            }
        }
        row("笔记布局", if (listLayout) "列表" else "宫格") {
            showSheet("笔记布局", arrayOf("宫格", "列表")) { choice ->
                setListLayout(choice == 1)
                closeSettings(); openSettings()
            }
        }
        section("安全与隐私")
        row("普通笔记", "直接打开 · 本地加密")
        row("隐私笔记", "支持系统验证") { closeSettings(); enterPrivate() }
        section("数据")
        row("导入笔记") { pickTransfer() }
        section("其他")
        row("关于拾页") { openAbout() }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.alpha = 0f; page.translationX = dp(24).toFloat()
        page.animate().alpha(1f).translationX(0f).setDuration(220).start()
    }

    private fun closeSettings() {
        val page = settingsOverlay ?: return
        if (aboutOpen) {
            aboutOpen = false
            settingsOverlay = settingsParent
            settingsParent = null
            page.animate().alpha(0f).translationX(dp(24).toFloat()).setDuration(190)
                .withEndAction { root.removeView(page) }.start()
            return
        }
        settingsOverlay = null
        page.animate().alpha(0f).translationX(dp(24).toFloat()).setDuration(190)
            .withEndAction { root.removeView(page) }.start()
    }

    private fun settingsRow(content: LinearLayout, title: String, detail: String = "", click: (() -> Unit)? = null) {
        val item = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), 0, dp(15), 0)
            background = rounded(paper, 17)
            if (click != null) { tapFeedback(); setOnClickListener { click() } }
        }
        item.addView(NativeIcon(this, iconFor(title), ink), LinearLayout.LayoutParams(dp(20), dp(20)))
        item.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(ink); isSingleLine = true },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(13) })
        if (detail.isNotEmpty()) item.addView(TextView(this).apply {
            text = detail; textSize = 12f; setTextColor(secondary); maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2))
        if (click != null) item.addView(NativeIcon(this, "next", secondary),
            LinearLayout.LayoutParams(dp(16), dp(16)).apply { leftMargin = dp(7) })
        content.addView(item, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(7) })
    }

    private fun openAbout() {
        settingsParent = settingsOverlay
        aboutOpen = true
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background)
            val bars = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            setPadding(dp(16), bars?.top ?: 0, dp(16), bars?.bottom ?: 0)
        }
        settingsOverlay = page
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹") { closeSettings() }, LinearLayout.LayoutParams(dp(48), dp(54)))
        header.addView(TextView(this).apply { text = "关于拾页"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink) })
        page.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
            addView(TextView(this@MainActivity).apply { text = "拾页 · Native"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink) })
            addView(TextView(this@MainActivity).apply { text = "给想法，留一页空白。"; textSize = 15f; setTextColor(secondary) })
            addView(TextView(this@MainActivity).apply { text = "by OpenALC"; textSize = 12f; setTextColor(secondary) })
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply { text = "应用信息"; textSize = 14f; setTextColor(secondary); setPadding(dp(8), dp(12), 0, dp(6)) })
        settingsRow(content, "当前版本", "v${packageManager.getPackageInfo(packageName, 0).versionName}")
        settingsRow(content, "获取更新") {
            io.execute {
                val message = try {
                    val connection = java.net.URL("https://api.github.com/repos/OpenALC/insp-native/releases/latest").openConnection() as java.net.HttpURLConnection
                    try {
                        val status = connection.responseCode
                        if (status == 404) "尚未发布正式版本" else if (status == 200) {
                            val tag = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("tag_name")
                            "最新版本 $tag"
                        } else "检查失败（HTTP $status）"
                    } finally {
                        connection.disconnect()
                    }
                } catch (error: Exception) {
                    "检查更新失败：${error.localizedMessage ?: "网络不可用"}"
                }
                runOnUiThread { toast(message) }
            }
        }
        settingsRow(content, "GitHub 仓库", "OpenALC/insp-native") { openUrl("https://github.com/OpenALC/insp-native") }
        settingsRow(content, "开源协议", "MIT") { openUrl("https://github.com/OpenALC/insp-native/blob/main/LICENSE") }
        content.addView(TextView(this).apply {
            text = "拾页 Native 使用 Android、HarmonyOS 原生组件构建。完整源码、许可证及更新记录请见 GitHub 仓库。"
            textSize = 13f; setTextColor(secondary); setPadding(dp(8), dp(22), dp(8), 0)
        })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        page.alpha = 0f; page.translationX = dp(24).toFloat()
        page.animate().alpha(1f).translationX(0f).setDuration(220).start()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun leavePrivate() {
        if (privateArea) thumbnails.evictAll()
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
        if (selectionMode) clearSelection()
        persistNow()
        io.submit { }.get()
        io.execute { store.lockPrivate() }
        if (privateArea) {
            privateGeneration++
            thumbnails.evictAll()
            privateArea = false; groupId = null
            editor.visibility = View.GONE; active = null; bodyInput = null; mindPanel = null
            tabletPlaceholder?.visibility = View.VISIBLE
            undoHistory.clear(); redoHistory.clear(); historyTask?.let(handler::removeCallbacks)
            allNotes = emptyList(); renderCards()
            refresh()
        }
    }

    private inner class Cards : RecyclerView.Adapter<Cards.Holder>() {
        private var notes = listOf<NativeNote>()

        inner class Holder(val card: MaterialCardView, val title: TextView, val image: ImageView, val preview: TextView, val mark: TextView) : RecyclerView.ViewHolder(card)

        fun submit(items: List<NativeNote>) {
            val previous = notes
            val next = items.map { it.copy() }
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].id == next[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = previous[oldItemPosition]
                    val new = next[newItemPosition]
                    return old.title == new.title && old.body == new.body && old.groupId == new.groupId && old.private == new.private
                }
            })
            notes = next
            diff.dispatchUpdatesTo(this)
        }

        fun selectionChanged(id: String) {
            val index = notes.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun selectionChangedAll() { if (notes.isNotEmpty()) notifyItemRangeChanged(0, notes.size) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = MaterialCardView(this@MainActivity).apply {
                radius = dp(19).toFloat(); cardElevation = 0f; setCardBackgroundColor(paper)
                strokeWidth = 0; useCompatPadding = false
                rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
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
            val mark = TextView(this@MainActivity).apply { textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE }
            card.addView(mark, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.TOP).apply {
                topMargin = dp(10); rightMargin = dp(10)
            })
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(7), dp(6), dp(7), dp(6)) }
            return Holder(card, title, image, preview, mark)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val note = notes[position]
            holder.mark.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.mark.text = if (note.id in selectedIds) "✓" else ""
            holder.mark.setTextColor(if (note.id in selectedIds) Color.BLACK else secondary)
            holder.mark.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (note.id in selectedIds) green else paper)
                setStroke(dp(1), if (note.id in selectedIds) green else secondary)
            }
            holder.title.text = note.title
            holder.preview.text = if (note.kind == "richtext") {
                RichTextCodec.decode(note.body, dark).apply {
                    for (index in length - 1 downTo 0) if (this[index] == '\uFFFC') replace(index, index + 1, "")
                }.takeIf { it.isNotBlank() } ?: "空白笔记"
            } else preview(note).ifBlank { "点此添加分支" }
            holder.image.tag = note.id
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            val imageId = if (note.kind == "richtext") {
                val spans = JSONObject(note.body).optJSONArray("spans")
                (0 until (spans?.length() ?: 0)).firstNotNullOfOrNull { index ->
                    spans!!.getJSONObject(index).takeIf { it.optString("type") == "image" }?.optString("attachmentId")
                }
            } else null
            if (imageId != null) {
                val cached = thumbnails.get(imageId)
                if (cached != null) { holder.image.setImageDrawable(cached); holder.image.visibility = View.VISIBLE }
                else {
                    val generation = privateGeneration
                    io.execute {
                        val image = decodeImage(store.image(note, imageId), dp(250))
                        runOnUiThread {
                            if (generation == privateGeneration) thumbnails.put(imageId, image)
                            if (generation == privateGeneration && holder.image.tag == note.id) {
                                holder.image.setImageDrawable(image)
                                holder.image.visibility = View.VISIBLE
                                (cards.layoutManager as StaggeredGridLayoutManager).invalidateSpanAssignments()
                            }
                        }
                    }
                }
            }
            holder.card.transitionName = "note-${note.id}"
            holder.card.setOnClickListener { if (selectionMode) toggleSelection(note.id) else openNote(note, holder.card) }
            holder.card.setOnLongClickListener { toggleSelection(note.id); true }
        }

        override fun getItemCount() = notes.size
    }

    private fun action(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; setTextColor(ink); gravity = Gravity.CENTER
        background = rounded(Color.TRANSPARENT, 50)
        tapFeedback()
        setOnClickListener { click() }
    }

    private fun iconButton(icon: String, click: () -> Unit) = FrameLayout(this).apply {
        tapFeedback(); setOnClickListener { click() }
        addView(NativeIcon(this@MainActivity, icon, ink), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink); gravity = Gravity.CENTER
        background = rounded(if (label.contains("灵感")) paper else if (dark) 0xff263f25.toInt() else 0xffe6f8df.toInt(), 28)
        tapFeedback()
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

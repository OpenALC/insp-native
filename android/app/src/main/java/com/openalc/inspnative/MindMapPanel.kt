package com.openalc.inspnative

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextWatcher
import android.text.TextPaint
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MindNode(val id: String, var text: String, val children: MutableList<MindNode> = mutableListOf(), var collapsed: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("collapsed", collapsed).put("children", JSONArray().apply {
        children.forEach { put(it.json()) }
    })

    companion object {
        fun read(json: JSONObject): MindNode = MindNode(json.getString("id"), json.getString("text"), mutableListOf<MindNode>().apply {
            val items = json.getJSONArray("children")
            for (index in 0 until items.length()) add(read(items.getJSONObject(index)))
        }, json.optBoolean("collapsed", false))
    }
}

class MindMapPanel(
    context: Context,
    private val note: NativeNote,
    private val changed: () -> Unit,
    private val beginEditing: () -> Unit,
) : LinearLayout(context) {
    private val rootNode = MindNode.read(JSONObject(note.body).getJSONObject("root"))
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val nodeInputs = mutableMapOf<String, EditText>()
    private val nodeRows = mutableMapOf<String, LinearLayout>()
    private val scroll = ScrollView(context).apply { addView(list) }
    private val treeFrame = FrameLayout(context)
    private val tree = MindTreeView(context, rootNode, { node -> startTreeEdit(node) }, { node -> addChild(node) },
        { node -> selectedId = node.id; toolAction("sibling") }, { node -> node.collapsed = !node.collapsed; update() },
        { placeTreeEditor() })
    private val treeInput = EditText(context).apply {
        setSingleLine(false)
        textSize = 17f
        setTextColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffd4d4d4.toInt() else Color.BLACK)
        background = null
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = GONE
    }
    private var treeEditingNode: MindNode? = null
    private var treeMode = true
    private var selectedId = rootNode.id
    private var editing = false
    private var metaLabel: TextView? = null
    private val toolbar = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(5), dp(6), dp(5))
    }

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        treeFrame.addView(tree, FrameLayout.LayoutParams(-1, -1))
        treeFrame.addView(treeInput, FrameLayout.LayoutParams(dp(180), dp(56)))
        addView(treeFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.visibility = GONE
        treeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                treeEditingNode?.let { node -> node.text = s.toString(); save(); tree.post { placeTreeEditor() } }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        for ((label, icon, action) in listOf(
            Triple("子级", "plus", "child"), Triple("同级", "sibling", "sibling"),
            Triple("升级", "outdent", "outdent"), Triple("降级", "indent", "indent")
        )) {
            val cell = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xff303830.toInt() else Color.WHITE)
                }
                foreground = RippleDrawable(ColorStateList.valueOf(0x4454d81b), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                setOnClickListener { toolAction(action) }
                contentDescription = label
            }
            val color = if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffd4d4d4.toInt() else Color.BLACK
            cell.addView(NativeIcon(context, icon, color), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            toolbar.addView(cell, LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(5); rightMargin = dp(5) })
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        toolbar.visibility = GONE
        renderList()
    }

    fun setEditing(value: Boolean) {
        editing = value
        toolbar.visibility = if (value && !treeMode) VISIBLE else GONE
        nodeInputs.values.forEach { it.isFocusableInTouchMode = value; it.isCursorVisible = value }
        if (!value) {
            finishTreeEdit()
            if (rootNode.text.isBlank()) { rootNode.text = "未命名的灵感笔记"; save(); renderList() }
            nodeInputs.values.forEach(EditText::clearFocus)
            requestFocus()
        }
    }

    fun renameRoot(title: String) {
        if (rootNode.text == title) return
        rootNode.text = title
        save()
        renderList()
    }

    fun toggleMode(): Boolean {
        finishTreeEdit()
        treeMode = !treeMode
        val outgoing = if (treeMode) scroll else treeFrame
        val incoming = if (treeMode) treeFrame else scroll
        outgoing.visibility = GONE
        incoming.alpha = 0f
        incoming.visibility = VISIBLE
        incoming.animate().alpha(1f).setDuration(200).start()
        toolbar.visibility = if (editing && !treeMode) VISIBLE else GONE
        tree.relayout()
        return treeMode
    }

    private fun save() {
        if (note.title != rootNode.text) {
            note.title = rootNode.text
        }
        note.body = JSONObject().put("type", "mindmap").put("root", rootNode.json()).toString()
        metaLabel?.text = metadata()
        changed()
        if (treeMode) tree.relayout()
    }

    private fun update(focusId: String? = null) {
        if (focusId != null) selectedId = focusId
        save(); renderList()
        if (focusId != null && !treeMode) post {
            nodeInputs[focusId]?.apply {
                requestFocus()
                if (text.toString() == "新分支") selectAll() else setSelection(text.length)
            }
        }
    }

    private fun metadata(): String {
        fun count(node: MindNode): Int = node.text.length + node.children.sumOf(::count)
        return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(note.updatedAt)) + "  ·  ${count(rootNode)}字"
    }

    private fun renderList() {
        val previousScroll = scroll.scrollY
        list.removeAllViews()
        nodeInputs.clear()
        nodeRows.clear()
        val header = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        val title = EditText(context).apply {
            setSingleLine(true)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            background = null
            setTextColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffd4d4d4.toInt() else Color.BLACK)
            hint = "请输入中心主题"
            setText(rootNode.text)
            isFocusableInTouchMode = editing
            isCursorVisible = editing
            setOnTouchListener { _, event ->
                if (!editing && event.actionMasked == MotionEvent.ACTION_UP) {
                    beginEditing(); inputFocus(rootNode.id); true
                } else false
            }
            setOnFocusChangeListener { _, focused -> if (focused) selectNode(rootNode.id) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    rootNode.text = s.toString(); save()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        nodeInputs[rootNode.id] = title
        header.addView(title, LayoutParams(-1, dp(54)))
        metaLabel = TextView(context).apply {
            text = metadata(); textSize = 13f
            setTextColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xff9aa79a.toInt() else 0xff929a91.toInt())
        }
        header.addView(metaLabel, LayoutParams(-1, dp(30)))
        list.addView(header)
        if (rootNode.children.isEmpty()) list.addView(TextView(context).apply {
            text = "＋  添加第一个分支"
            textSize = 16f
            setTextColor(0xff40882c.toInt())
            setPadding(dp(38), dp(26), dp(22), dp(26))
            setOnClickListener { addChild(rootNode) }
        })
        rootNode.children.forEach { child -> renderNode(child, rootNode, 0) }
        selectNode(selectedId)
        scroll.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun renderNode(node: MindNode, parent: MindNode, depth: Int) {
        val holder = FrameLayout(context)
        repeat(depth) { level ->
            holder.addView(View(context).apply { setBackgroundColor(0xffd9e2d5.toInt()) },
                FrameLayout.LayoutParams(dp(1), -1).apply { leftMargin = dp(26 + level * 24) })
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20 + depth * 24), dp(2), dp(16), dp(2))
            setOnClickListener { selectNode(node.id); if (!editing) beginEditing(); inputFocus(node.id) }
        }
        row.addView(TextView(context).apply {
            text = if (node.children.isEmpty()) "•" else if (node.collapsed) "▸" else "▾"
            gravity = Gravity.CENTER
            textSize = if (node.children.isEmpty()) 19f else 21f
            setTextColor(0xff879987.toInt())
            setOnClickListener {
                if (node.children.isNotEmpty()) { node.collapsed = !node.collapsed; update() }
                else { if (!editing) beginEditing(); inputFocus(node.id) }
            }
        }, LayoutParams(dp(30), dp(48)))
        val input = EditText(context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(node.text)
            hint = "输入分支"
            textSize = 16f
            background = null
            isFocusableInTouchMode = editing
            isCursorVisible = editing
            setOnTouchListener { _, event ->
                if (!editing && event.actionMasked == MotionEvent.ACTION_UP) {
                    beginEditing()
                    requestFocus()
                    setSelection(length())
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    true
                } else false
            }
            setOnFocusChangeListener { _, focused -> if (focused) selectNode(node.id) }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_NEXT ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    val start = selectionStart.coerceAtLeast(0)
                    val end = selectionEnd.coerceAtLeast(start)
                    val original = text.toString()
                    node.text = original.substring(0, start)
                    val next = MindNode(UUID.randomUUID().toString(), original.substring(end))
                    parent.children.add(parent.children.indexOf(node) + 1, next)
                    update(next.id)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    node.text = s.toString()
                    save()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        nodeInputs[node.id] = input
        row.addView(input, LayoutParams(0, dp(48), 1f))
        nodeRows[node.id] = row
        holder.addView(row, FrameLayout.LayoutParams(-1, -2))
        list.addView(holder, LayoutParams(-1, -2))
        if (!node.collapsed) node.children.forEach { renderNode(it, node, depth + 1) }
    }

    private fun inputFocus(id: String) {
        nodeInputs[id]?.let { input ->
            input.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun selectNode(id: String) {
        selectedId = id
        tree.select(id)
        nodeRows.forEach { (key, row) ->
            row.setBackgroundColor(if (key == id && editing) 0x1054d81b else Color.TRANSPARENT)
        }
    }

    private fun addChild(parent: MindNode) {
        parent.collapsed = false
        val child = MindNode(UUID.randomUUID().toString(), "")
        parent.children.add(child)
        update(child.id)
        if (treeMode) startTreeEdit(child) else {
            if (!editing) beginEditing()
            inputFocus(child.id)
        }
    }

    private fun startTreeEdit(node: MindNode) {
        if (treeEditingNode?.id != node.id) finishTreeEdit()
        if (!editing) beginEditing()
        selectNode(node.id)
        treeEditingNode = null
        treeInput.setText(node.text)
        treeEditingNode = node
        treeInput.visibility = VISIBLE
        tree.setEditingId(node.id)
        tree.post {
            tree.revealNode(node.id)
            placeTreeEditor()
            treeInput.requestFocus()
            if (node.text == "未命名的灵感笔记" || node.text == "新分支") treeInput.selectAll()
            else treeInput.setSelection(treeInput.length())
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(treeInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun placeTreeEditor() {
        val node = treeEditingNode ?: return
        val rect = tree.nodeRect(node.id) ?: return
        treeInput.layoutParams = (treeInput.layoutParams as FrameLayout.LayoutParams).apply {
            width = rect.width().toInt().coerceAtLeast(dp(110))
            height = rect.height().toInt().coerceAtLeast(dp(52))
            leftMargin = rect.left.toInt()
            topMargin = rect.top.toInt()
        }
        treeInput.setTextColor(if (node === rootNode) Color.BLACK else
            if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffd4d4d4.toInt() else Color.BLACK)
    }

    private fun finishTreeEdit() {
        val node = treeEditingNode ?: return
        treeEditingNode = null
        if (node.text.isBlank()) node.text = if (node === rootNode) "未命名的灵感笔记" else "新分支"
        treeInput.clearFocus()
        treeInput.visibility = GONE
        tree.setEditingId(null)
        save()
        renderList()
    }

    private fun toolAction(action: String) {
        val node = if (selectedId == rootNode.id) rootNode else findNode(rootNode, selectedId) ?: rootNode
        val parent = findParent(rootNode, node)
        when (action) {
            "child" -> addChild(node)
            "sibling" -> {
                val destination = parent ?: rootNode
                val sibling = MindNode(UUID.randomUUID().toString(), "")
                destination.children.add(if (parent == null) destination.children.size else destination.children.indexOf(node) + 1, sibling)
                update(sibling.id)
                if (treeMode) startTreeEdit(sibling) else inputFocus(sibling.id)
            }
            "indent" -> if (parent != null) {
                val index = parent.children.indexOf(node)
                if (index > 0) {
                    parent.children.removeAt(index)
                    parent.children[index - 1].apply { collapsed = false; children.add(node) }
                    update(node.id)
                }
            }
            "outdent" -> if (parent != null) {
                val grandparent = findParent(rootNode, parent)
                if (grandparent != null) { parent.children.remove(node); grandparent.children.add(grandparent.children.indexOf(parent) + 1, node); update(node.id) }
            }
            "more" -> if (parent != null) actions(node, parent)
        }
    }

    private fun actions(node: MindNode, parent: MindNode) {
        val options = arrayOf("上移", "下移", "删除分支")
        (context as MainActivity).showSheet("分支操作", options) { choice ->
            val index = parent.children.indexOf(node)
            when (choice) {
                0 -> if (index > 0) { parent.children.removeAt(index); parent.children.add(index - 1, node) }
                1 -> if (index < parent.children.lastIndex) { parent.children.removeAt(index); parent.children.add(index + 1, node) }
                2 -> parent.children.remove(node)
            }
            update(if (choice == 2) null else node.id)
        }
    }

    private fun findParent(current: MindNode, child: MindNode): MindNode? {
        if (current.children.contains(child)) return current
        return current.children.firstNotNullOfOrNull { findParent(it, child) }
    }

    private fun findNode(current: MindNode, id: String): MindNode? {
        if (current.id == id) return current
        return current.children.firstNotNullOfOrNull { findNode(it, id) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class MindTreeView(
    context: Context,
    private val root: MindNode,
    private val edit: (MindNode) -> Unit,
    private val add: (MindNode) -> Unit,
    private val addSibling: (MindNode) -> Unit,
    private val toggleCollapse: (MindNode) -> Unit,
    private val viewportChanged: () -> Unit,
) : View(context) {
    private data class Box(val node: MindNode, val x: Float, var y: Float, val width: Float, val height: Float,
                           val text: StaticLayout, val ink: TextPaint)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxes = mutableListOf<Box>()
    private val density = resources.displayMetrics.density
    private val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private var layoutDirty = true
    private var centered = false
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var selectedId = root.id
    private var editingId: String? = null
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            scale = (scale * detector.scaleFactor).coerceIn(.55f, 2.5f)
            offsetX = detector.focusX - (detector.focusX - offsetX) * scale / oldScale
            offsetY = detector.focusY - (detector.focusY - offsetY) * scale / oldScale
            invalidate()
            viewportChanged()
            return true
        }
    })

    fun select(id: String) { selectedId = id; invalidate() }
    fun setEditingId(id: String?) { editingId = id; invalidate() }
    fun relayout() { layoutDirty = true; invalidate() }

    private fun ensureLayout() {
        if (!layoutDirty) return
        boxes.clear()
        var nextY = 0f
        fun place(node: MindNode, depth: Int): Float {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = if (node === root) 19f else 17f }
            val content = node.text.ifBlank { if (node === root) "请输入中心主题" else "输入分支" }
            val boxWidth = (textPaint.measureText(content) + 28f).coerceIn(100f, 188f)
            val layout = StaticLayout.Builder.obtain(content, 0, content.length, textPaint, (boxWidth - 24f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            val boxHeight = (layout.height + 24f).coerceAtLeast(52f)
            val visibleChildren = if (node.collapsed) emptyList() else node.children
            val centers = visibleChildren.map { place(it, depth + 1) }
            val center = if (centers.isEmpty()) {
                val y = nextY + boxHeight / 2f
                nextY += boxHeight + 32f
                y
            } else (centers.first() + centers.last()) / 2f
            boxes.add(Box(node, depth * 210f, center - boxHeight / 2f, boxWidth, boxHeight, layout, textPaint))
            return center
        }
        val rootCenter = place(root, 0)
        boxes.forEach { it.y -= rootCenter }
        layoutDirty = false
        if (!centered && width > 0 && height > 0) {
            val rootBox = boxes.first { it.node === root }
            offsetX = if (root.children.isEmpty()) width / 2f - rootBox.width * density / 2f else width * .08f
            offsetY = height / 2f - rootBox.height * density / 2f
            centered = true
        }
    }

    fun nodeRect(id: String): RectF? {
        ensureLayout()
        val box = boxes.firstOrNull { it.node.id == id } ?: return null
        val factor = scale * density
        return RectF(offsetX + box.x * factor, offsetY + box.y * factor,
            offsetX + (box.x + box.width) * factor, offsetY + (box.y + box.height) * factor)
    }

    fun revealNode(id: String) {
        val rect = nodeRect(id) ?: return
        val leftLimit = width * .06f
        val rightLimit = width * .84f
        val topLimit = height * .16f
        val bottomLimit = height * .75f
        val dx = when {
            rect.right > rightLimit -> rightLimit - rect.right
            rect.left < leftLimit -> leftLimit - rect.left
            else -> 0f
        }
        val dy = when {
            rect.bottom > bottomLimit -> bottomLimit - rect.bottom
            rect.top < topLimit -> topLimit - rect.top
            else -> 0f
        }
        if (dx == 0f && dy == 0f) return
        val startX = offsetX
        val startY = offsetY
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { animation ->
                val amount = animation.animatedValue as Float
                offsetX = startX + dx * amount
                offsetY = startY + dy * amount
                invalidate()
                viewportChanged()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!centered) relayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureLayout()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale * density, scale * density)
        val byId = boxes.associateBy { it.node.id }
        paint.color = if (dark) 0xff6d9f62.toInt() else 0xff8db783.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        for (parent in boxes) if (!parent.node.collapsed) for (child in parent.node.children) {
            val target = byId[child.id] ?: continue
            val x1 = parent.x + parent.width
            val y1 = parent.y + parent.height / 2f
            val x2 = target.x
            val y2 = target.y + target.height / 2f
            canvas.drawPath(Path().apply {
                moveTo(x1, y1)
                cubicTo(x1 + 27f, y1, x2 - 27f, y2, x2, y2)
            }, paint)
        }
        paint.style = Paint.Style.FILL
        for (box in boxes) {
            paint.color = if (box.node === root) 0xff54d81b.toInt() else
                if (dark) 0xff303830.toInt() else 0xffeef8e9.toInt()
            val bounds = RectF(box.x, box.y, box.x + box.width, box.y + box.height)
            canvas.drawRoundRect(bounds, 15f, 15f, paint)
            if (box.node.id == selectedId) {
                paint.color = 0xff45ba16.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawRoundRect(bounds, 15f, 15f, paint)
                paint.style = Paint.Style.FILL
            }
            if (box.node.id != editingId) {
                box.ink.color = if (dark && box.node !== root) 0xffd4d4d4.toInt() else Color.BLACK
                canvas.save()
                canvas.translate(box.x + 12f, box.y + (box.height - box.text.height) / 2f)
                box.text.draw(canvas)
                canvas.restore()
            }
            if (box.node.collapsed && box.node.children.isNotEmpty()) {
                val count = countHidden(box.node)
                paint.color = if (dark) 0xff3e5b39.toInt() else Color.WHITE
                canvas.drawCircle(box.x + box.width + 15f, box.y + box.height / 2f, 14f, paint)
                paint.color = 0xff54a925.toInt()
                paint.textSize = 13f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(count.toString(), box.x + box.width + 15f, box.y + box.height / 2f + 5f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
            if (box.node.id == selectedId) {
                val plusX = box.x + box.width + if (box.node.collapsed && box.node.children.isNotEmpty()) 47f else 25f
                paint.color = 0xff54d81b.toInt()
                canvas.drawCircle(plusX, box.y + box.height / 2f, 17f, paint)
                paint.color = Color.BLACK
                paint.textSize = 26f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("+", plusX, box.y + box.height / 2f + 9f, paint)
                paint.textAlign = Paint.Align.LEFT
                if (box.node !== root) {
                    paint.color = if (dark) 0xff303830.toInt() else Color.WHITE
                    canvas.drawCircle(plusX, box.y + box.height / 2f + 43f, 17f, paint)
                    paint.color = if (dark) 0xffd4d4d4.toInt() else Color.BLACK
                    paint.textSize = 22f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("↳", plusX, box.y + box.height / 2f + 51f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
            }
        }
        canvas.restore()
    }

    private fun countHidden(node: MindNode): Int = node.children.sumOf { 1 + countHidden(it) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; moved = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 3f) moved = true
                    offsetX += dx; offsetY += dy; invalidate(); viewportChanged()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moved && !detector.isInProgress) {
                val x = (event.x - offsetX) / (scale * density)
                val y = (event.y - offsetY) / (scale * density)
                val selected = boxes.firstOrNull { box ->
                    if (box.node.id != selectedId) false else {
                        val plusX = box.x + box.width + if (box.node.collapsed && box.node.children.isNotEmpty()) 47f else 25f
                        x in (plusX - 20f)..(plusX + 20f) && y in (box.y + box.height / 2f - 20f)..(box.y + box.height / 2f + 20f)
                    }
                }
                if (selected != null) add(selected.node)
                else {
                    val sibling = boxes.firstOrNull { box ->
                        if (box.node === root || box.node.id != selectedId) false else {
                            val plusX = box.x + box.width + if (box.node.collapsed && box.node.children.isNotEmpty()) 47f else 25f
                            x in (plusX - 20f)..(plusX + 20f) && y in (box.y + box.height / 2f + 23f)..(box.y + box.height / 2f + 63f)
                        }
                    }
                    val collapsed = boxes.firstOrNull { box ->
                        box.node.collapsed && box.node.children.isNotEmpty() &&
                            x in (box.x + box.width + 1f)..(box.x + box.width + 29f) &&
                            y in (box.y + box.height / 2f - 15f)..(box.y + box.height / 2f + 15f)
                    }
                    if (sibling != null) addSibling(sibling.node)
                    else if (collapsed != null) toggleCollapse(collapsed.node)
                    else boxes.firstOrNull { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }?.let {
                    selectedId = it.node.id
                    invalidate()
                    edit(it.node)
                    }
                }
            }
        }
        return true
    }
}

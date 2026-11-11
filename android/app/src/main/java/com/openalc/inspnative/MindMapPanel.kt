package com.openalc.inspnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
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

data class MindNode(val id: String, var text: String, val children: MutableList<MindNode> = mutableListOf()) {
    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("children", JSONArray().apply {
        children.forEach { put(it.json()) }
    })

    companion object {
        fun read(json: JSONObject): MindNode = MindNode(json.getString("id"), json.getString("text"), mutableListOf<MindNode>().apply {
            val items = json.getJSONArray("children")
            for (index in 0 until items.length()) add(read(items.getJSONObject(index)))
        })
    }
}

class MindMapPanel(
    context: Context,
    private val note: NativeNote,
    private val changed: () -> Unit,
) : LinearLayout(context) {
    private val rootNode = MindNode.read(JSONObject(note.body).getJSONObject("root"))
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val nodeInputs = mutableMapOf<String, EditText>()
    private val nodeRows = mutableMapOf<String, LinearLayout>()
    private val scroll = ScrollView(context).apply { addView(list) }
    private val tree = MindTreeView(context, rootNode, { node -> selectNode(node.id) }, { node -> editNode(node) })
    private var treeMode = false
    private var selectedId = rootNode.id
    private val toolbar = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(5), dp(6), dp(5))
        setBackgroundColor(if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xff20221f.toInt() else Color.WHITE)
    }

    init {
        orientation = VERTICAL
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(tree, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tree.visibility = GONE
        for ((label, action) in listOf(
            "升级" to "outdent", "降级" to "indent", "子主题" to "child", "同级" to "sibling", "更多" to "more"
        )) {
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                foreground = RippleDrawable(ColorStateList.valueOf(0x4454d81b), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                setOnClickListener { toolAction(action) }
            }
            val color = if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xffd4d4d4.toInt() else Color.BLACK
            cell.addView(NativeIcon(context, if (action == "child") "plus" else if (action == "more") "more" else action, color),
                LayoutParams(dp(19), dp(19)))
            cell.addView(TextView(context).apply { text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(color) },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(22)))
            toolbar.addView(cell, LayoutParams(0, dp(52), 1f))
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(62)))
        renderList()
    }

    fun renameRoot(title: String) {
        rootNode.text = title
        save()
    }

    fun toggleMode(): Boolean {
        treeMode = !treeMode
        val outgoing = if (treeMode) scroll else tree
        val incoming = if (treeMode) tree else scroll
        outgoing.visibility = GONE
        incoming.alpha = 0f
        incoming.visibility = VISIBLE
        incoming.animate().alpha(1f).setDuration(180).start()
        tree.invalidate()
        return treeMode
    }

    private fun save() {
        note.body = JSONObject().put("type", "mindmap").put("root", rootNode.json()).toString()
        changed()
        tree.invalidate()
    }

    private fun update(focusId: String? = null) {
        if (focusId != null) selectedId = focusId
        save(); renderList()
        if (focusId != null) post {
            nodeInputs[focusId]?.apply {
                requestFocus()
                if (text.toString() == "新分支") selectAll() else setSelection(text.length)
            }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        nodeInputs.clear()
        nodeRows.clear()
        if (rootNode.children.isEmpty()) list.addView(TextView(context).apply {
            text = "点击下方“子主题”，从一个想法开始。"
            textSize = 16f
            setPadding(dp(22), dp(30), dp(22), dp(30))
        })
        rootNode.children.forEach { child -> renderNode(child, rootNode, 0) }
        selectNode(selectedId)
    }

    private fun renderNode(node: MindNode, parent: MindNode, depth: Int) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14 + depth * 20), dp(4), dp(8), dp(4))
            setOnClickListener { selectNode(node.id); inputFocus(node.id) }
        }
        val input = EditText(context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(node.text)
            textSize = 16f
            background = null
            setOnFocusChangeListener { _, focused -> if (focused) selectNode(node.id) }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_NEXT ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    val next = MindNode(UUID.randomUUID().toString(), "新分支")
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
        row.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        nodeRows[node.id] = row
        list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        node.children.forEach { renderNode(it, node, depth + 1) }
    }

    private fun inputFocus(id: String) { nodeInputs[id]?.requestFocus() }

    private fun selectNode(id: String) {
        selectedId = id
        nodeRows.forEach { (key, row) ->
            row.setBackgroundColor(if (key == id) 0x2254d81b else Color.TRANSPARENT)
        }
    }

    private fun toolAction(action: String) {
        val node = if (selectedId == rootNode.id) rootNode else findNode(rootNode, selectedId) ?: rootNode
        val parent = findParent(rootNode, node)
        when (action) {
            "child" -> { val child = MindNode(UUID.randomUUID().toString(), "新分支"); node.children.add(child); update(child.id) }
            "sibling" -> {
                val destination = parent ?: rootNode
                val sibling = MindNode(UUID.randomUUID().toString(), "新分支")
                destination.children.add(if (parent == null) destination.children.size else destination.children.indexOf(node) + 1, sibling)
                update(sibling.id)
            }
            "indent" -> if (parent != null) {
                val index = parent.children.indexOf(node)
                if (index > 0) { parent.children.removeAt(index); parent.children[index - 1].children.add(node); update(node.id) }
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

    private fun editNode(node: MindNode) {
        (context as MainActivity).showTextInputSheet("编辑分支", "分支内容", node.text) { value ->
            node.text = value
            update()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class MindTreeView(context: Context, private val root: MindNode, private val select: (MindNode) -> Unit,
                           private val edit: (MindNode) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodes = mutableListOf<Triple<MindNode, Float, Float>>()
    private val density = resources.displayMetrics.density
    private var scale = 1f
    private var offsetX = 60f * density
    private var offsetY = 120f * density
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var selectedId = root.id
    private var lastTapAt = 0L
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(.55f, 2.5f)
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        nodes.clear()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale * density, scale * density)
        var leaf = 0
        fun place(node: MindNode, depth: Int): Float {
            val y = if (node.children.isEmpty()) {
                (leaf++ * 96).toFloat()
            } else {
                val children = node.children.map { place(it, depth + 1) }
                (children.first() + children.last()) / 2f
            }
            nodes += Triple(node, depth * 250f, y)
            return y
        }
        place(root, 0)
        val positions = nodes.associate { it.first.id to (it.second to it.third) }
        paint.color = 0xff9fc796.toInt()
        paint.strokeWidth = 3f
        for ((node, x, y) in nodes) for (child in node.children) {
            val destination = positions.getValue(child.id)
            canvas.drawLine(x + 185, y + 29, destination.first, destination.second + 29, paint)
        }
        for ((node, x, y) in nodes) {
            paint.color = if (node === root) 0xffe8f8de.toInt() else 0xffffffff.toInt()
            canvas.drawRoundRect(RectF(x, y, x + 185, y + 58), 20f, 20f, paint)
            if (node.id == selectedId) {
                paint.color = 0xff54d81b.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                canvas.drawRoundRect(RectF(x, y, x + 185, y + 58), 20f, 20f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = Color.BLACK
            paint.textSize = 20f
            canvas.drawText(node.text.take(12), x + 14, y + 36, paint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; moved = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 3f) moved = true
                    offsetX += dx; offsetY += dy; invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moved) {
                val x = (event.x - offsetX) / (scale * density)
                val y = (event.y - offsetY) / (scale * density)
                nodes.firstOrNull { x in it.second..(it.second + 185f) && y in it.third..(it.third + 58f) }?.first?.let { node ->
                    val now = android.os.SystemClock.uptimeMillis()
                    if (selectedId == node.id && now - lastTapAt < 380) edit(node) else {
                        selectedId = node.id; select(node); invalidate()
                    }
                    lastTapAt = now
                }
            }
        }
        return true
    }
}

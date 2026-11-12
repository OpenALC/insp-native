package com.openalc.inspnative

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Calendar
import java.util.UUID

class TodoPage(
    private val activity: AppCompatActivity,
    private val store: NativeStore,
    private val accent: Int,
    private val dark: Boolean,
    private val onBack: () -> Unit,
) : LinearLayout(activity) {
    private val ink = if (dark) 0xffd4d4d4.toInt() else Color.BLACK
    private val muted = if (dark) 0xffa4afa1.toInt() else 0xff737873.toInt()
    private val surface = if (dark) 0xff20221f.toInt() else Color.WHITE
    private val pageColor = if (dark) 0xff101010.toInt() else 0xfff5f5f5.toInt()
    private val search = EditText(activity)
    private val rows = LinearLayout(activity)
    private var composeDialog: Dialog? = null
    private var pickerDialog: Dialog? = null
    private var draftReminder: Long? = null
    private var draftRepeat = "none"

    init {
        orientation = VERTICAL
        setBackgroundColor(pageColor)
        val header = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(dp(25), dp(18), dp(25), dp(12)) }
        header.addView(text("‹", 32f, ink).apply { setOnClickListener { onBack() } }, LayoutParams(dp(46), dp(48)))
        header.addView(text("待办", 32f, ink, true), LayoutParams(-1, dp(70)))
        addView(header)
        search.apply {
            hint = "搜索待办"; textSize = 16f; setSingleLine(true)
            setTextColor(ink); setHintTextColor(muted)
            background = shape(if (dark) 0xff2a2a2a.toInt() else 0xffe8e8e8.toInt(), 22)
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        addView(search, LayoutParams(-1, dp(50)).apply { setMargins(dp(22), 0, dp(22), dp(12)) })
        val body = FrameLayout(activity)
        val scroll = ScrollView(activity).apply { isFillViewport = true; addView(rows) }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val add = text("+", 34f, Color.BLACK).apply {
            gravity = Gravity.CENTER
            background = shape(accent, 50)
            elevation = dp(6).toFloat()
            contentDescription = "新建待办"
            setOnClickListener { openComposer(null) }
        }
        body.addView(add, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(24), dp(24)) })
        addView(body, LayoutParams(-1, 0, 1f))
        render()
    }

    fun handleBack(): Boolean {
        if (pickerDialog != null) { pickerDialog?.dismiss(); return true }
        if (composeDialog != null) { composeDialog?.dismiss(); return true }
        return false
    }

    private fun render() {
        rows.removeAllViews()
        val todos = store.todos().filter { it.text.contains(search.text.toString(), ignoreCase = true) }
        val pending = todos.filterNot { it.completed }.sortedWith(compareBy<NativeTodo> { it.remindAt ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
        val complete = todos.filter { it.completed }.sortedByDescending { it.updatedAt }
        rows.orientation = VERTICAL
        rows.setPadding(dp(25), dp(12), dp(25), dp(90))
        if (pending.isEmpty() && complete.isEmpty()) {
            val empty = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            empty.addView(text("✓", 43f, Color.WHITE).apply { gravity = Gravity.CENTER; background = shape(muted, 15) }, LayoutParams(dp(68), dp(68)))
            empty.addView(text(if (search.text.isBlank()) "没有待办" else "没有找到待办", 18f, muted).apply { gravity = Gravity.CENTER },
                LayoutParams(-1, dp(60)))
            rows.addView(empty, LayoutParams(-1, dp(500)))
        }
        pending.forEach(::addRow)
        if (complete.isNotEmpty()) {
            rows.addView(text("已完成 ${complete.size}", 14f, muted).apply { setPadding(0, dp(20), 0, dp(10)) })
            complete.forEach(::addRow)
        }
    }

    private fun addRow(todo: NativeTodo) {
        val row = LinearLayout(activity).apply { gravity = Gravity.TOP; setPadding(dp(4), dp(14), dp(4), dp(14)) }
        val box = text(if (todo.completed) "✓" else "", 15f, accent).apply {
            gravity = Gravity.CENTER
            background = shape(if (todo.completed) Accent.mix(accent, surface, .13f) else Color.TRANSPARENT, 6,
                if (todo.completed) accent else muted)
            contentDescription = if (todo.completed) "取消完成" else "完成待办"
            setOnClickListener {
                todo.completed = !todo.completed
                todo.updatedAt = System.currentTimeMillis()
                store.saveTodo(todo)
                TodoReminder.schedule(activity, todo)
                render()
            }
        }
        row.addView(box, LayoutParams(dp(24), dp(24)).apply { topMargin = dp(3); rightMargin = dp(14) })
        val content = LinearLayout(activity).apply { orientation = VERTICAL; setOnClickListener { openComposer(todo) } }
        content.addView(text(todo.text, 17f, if (todo.completed) muted else ink).apply {
            if (todo.completed) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }, LayoutParams(-1, -2))
        if (todo.remindAt != null) content.addView(text("◷  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(todo.remindAt!!)}${if (todo.repeat == "none") "" else " · 重复"}", 12f, muted), LayoutParams(-1, dp(28)))
        row.addView(content, LayoutParams(0, -2, 1f))
        rows.addView(row, LayoutParams(-1, -2))
        rows.addView(View(activity).apply { setBackgroundColor(if (dark) 0xff363a35.toInt() else 0xffdddddd.toInt()) }, LayoutParams(-1, dp(1)))
    }

    private fun openComposer(item: NativeTodo?) {
        draftReminder = item?.remindAt
        draftRepeat = item?.repeat ?: "none"
        val dialog = Dialog(activity)
        composeDialog = dialog
        dialog.setOnDismissListener { composeDialog = null }
        val panel = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(dp(24), dp(26), dp(24), dp(26)); background = shape(surface, 28) }
        val input = EditText(activity).apply {
            setText(item?.text ?: "")
            hint = "回车即可连续添加待办"
            textSize = 17f; setTextColor(ink); setHintTextColor(muted)
            background = null; minLines = 3; maxLines = 4; gravity = Gravity.TOP
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        panel.addView(input, LayoutParams(-1, dp(125)))
        val reminder = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(13), 0)
            background = shape(pageColor, 18)
        }
        reminder.addView(NativeIcon(activity, "clock", ink), LayoutParams(dp(22), dp(22)))
        val reminderLabel = text("设置提醒", 16f, ink).apply { gravity = Gravity.CENTER_VERTICAL }
        reminder.addView(reminderLabel, LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        reminder.addView(NativeIcon(activity, "next", muted), LayoutParams(dp(18), dp(18)))
        val repeatOptions = listOf("none" to "不重复", "daily" to "每天", "weekly" to "每周", "monthly" to "每月")
        val repeatButtons = mutableListOf<TextView>()
        val repeatChoices = LinearLayout(activity)
        val repeatStrip = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(repeatChoices)
        }
        for ((key, label) in repeatOptions) {
            val button = text(label, 13f, ink).apply { gravity = Gravity.CENTER }
            repeatButtons += button
            repeatChoices.addView(button, LayoutParams(dp(76), dp(36)).apply { rightMargin = dp(7) })
        }
        fun updateRepeat() {
            repeatButtons.forEachIndexed { index, button ->
                button.background = shape(if (repeatOptions[index].first == draftRepeat)
                    Accent.mix(accent, surface, .25f) else pageColor, 18)
            }
        }
        repeatButtons.forEachIndexed { index, button ->
            button.setOnClickListener { draftRepeat = repeatOptions[index].first; updateRepeat() }
        }
        updateRepeat()
        fun updateReminderLabel() {
            reminderLabel.text = draftReminder?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it)
            } ?: "设置提醒"
            repeatStrip.visibility = if (draftReminder == null) View.GONE else View.VISIBLE
        }
        repeatChoices.addView(text("清除提醒", 13f, muted).apply {
            gravity = Gravity.CENTER
            setOnClickListener { draftReminder = null; draftRepeat = "none"; updateRepeat(); updateReminderLabel() }
        }, LayoutParams(dp(76), dp(36)))
        updateReminderLabel()
        reminder.setOnClickListener {
            input.clearFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            openPicker { updateReminderLabel() }
        }
        panel.addView(reminder, LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        panel.addView(repeatStrip, LayoutParams(-1, dp(43)).apply { topMargin = dp(12) })
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (item != null) actions.addView(text("删除", 14f, 0xffbc4848.toInt()).apply {
            gravity = Gravity.CENTER; setOnClickListener {
                store.deleteTodo(item.id); TodoReminder.cancel(activity, item.id); render(); dialog.dismiss()
            }
        }, LayoutParams(dp(46), dp(48)).apply { rightMargin = dp(8) })
        actions.addView(text("取消", 16f, ink).apply {
            gravity = Gravity.CENTER; background = shape(pageColor, 24)
            setOnClickListener { dialog.dismiss() }
        }, LayoutParams(0, dp(48), 1f))
        val done = text("完成", 16f, Color.BLACK).apply { gravity = Gravity.CENTER; background = shape(accent, 24) }
        actions.addView(done, LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(16) })
        fun save(keepOpen: Boolean) {
            val value = input.text.toString().trim()
            if (value.isEmpty()) return
            val now = System.currentTimeMillis()
            val todo = item?.copy(text = value, remindAt = draftReminder, repeat = draftRepeat, updatedAt = now)
                ?: NativeTodo(UUID.randomUUID().toString(), value, false, draftReminder, draftRepeat, now, now)
            store.saveTodo(todo)
            TodoReminder.schedule(activity, todo)
            if (draftReminder != null && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 711)
            render()
            if (keepOpen && item == null) { input.setText(""); draftReminder = null; draftRepeat = "none"; updateRepeat(); updateReminderLabel() }
            else dialog.dismiss()
        }
        done.setOnClickListener { save(false) }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_DONE) { save(true); true } else false }
        showBottom(dialog, panel)
        input.requestFocus()
    }

    private fun openPicker(onChanged: () -> Unit) {
        val date = Calendar.getInstance().apply { timeInMillis = draftReminder ?: System.currentTimeMillis() + 3600_000 }
        val year = wheel(2020, 2100, date.get(Calendar.YEAR), "年")
        val month = wheel(1, 12, date.get(Calendar.MONTH) + 1, "月")
        val day = wheel(1, date.getActualMaximum(Calendar.DAY_OF_MONTH), date.get(Calendar.DAY_OF_MONTH), "日")
        fun updateDays() {
            val last = Calendar.getInstance().apply { set(year.value, month.value - 1, 1) }
                .getActualMaximum(Calendar.DAY_OF_MONTH)
            day.displayedValues = null
            day.maxValue = last
            if (day.value > last) day.value = last
            day.displayedValues = Array(last) { "${it + 1}日" }
        }
        year.setOnValueChangedListener { _, _, _ -> updateDays() }
        month.setOnValueChangedListener { _, _, _ -> updateDays() }
        showWheels("选择日期", listOf(year, month, day), "下一步") {
            date.set(year.value, month.value - 1, day.value)
            val hour = wheel(0, 23, date.get(Calendar.HOUR_OF_DAY), "时")
            val minute = wheel(0, 59, date.get(Calendar.MINUTE), "分")
            showWheels("选择时间", listOf(hour, minute), "确定") {
                date.set(Calendar.HOUR_OF_DAY, hour.value)
                date.set(Calendar.MINUTE, minute.value)
                date.set(Calendar.SECOND, 0)
                date.set(Calendar.MILLISECOND, 0)
                draftReminder = date.timeInMillis
                onChanged()
            }
        }
    }

    private fun wheel(min: Int, max: Int, selected: Int, unit: String) = NumberPicker(activity).apply {
        minValue = min
        maxValue = max
        value = selected
        wrapSelectorWheel = max - min > 3
        displayedValues = Array(max - min + 1) { "${min + it}$unit" }
        setTextColor(ink)
        setTextSize(dp(18).toFloat())
        setSelectionDividerHeight(dp(1))
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
    }

    private fun showWheels(title: String, wheels: List<NumberPicker>, confirm: String, accept: () -> Unit) {
        val dialog = Dialog(activity)
        pickerDialog = dialog
        dialog.setOnDismissListener { if (pickerDialog === dialog) pickerDialog = null }
        val panel = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(24))
            background = shape(surface, 28)
        }
        panel.addView(text(title, 21f, ink, true), LayoutParams(-1, dp(48)))
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        wheels.forEach { row.addView(it, LayoutParams(0, dp(214), 1f)) }
        panel.addView(row)
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(text("取消", 16f, ink).apply {
            gravity = Gravity.CENTER; background = shape(pageColor, 24)
            setOnClickListener { dialog.dismiss() }
        }, LayoutParams(0, dp(48), 1f))
        actions.addView(text(confirm, 16f, Color.BLACK).apply {
            gravity = Gravity.CENTER; background = shape(accent, 24)
            setOnClickListener { dialog.dismiss(); accept() }
        }, LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        panel.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        showBottom(dialog, panel)
    }

    private fun showBottom(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.45f)
            setLayout(activity.resources.displayMetrics.widthPixels - dp(20), -2)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - dp(20), -2)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
}

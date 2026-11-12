package com.openalc.inspnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View

class NativeIcon(context: Context, private val name: String, color: Int) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate((width - minOf(width, height)) / 2f, (height - minOf(width, height)) / 2f)
        canvas.scale(minOf(width, height) / 24f, minOf(width, height) / 24f)
        fun line(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            }
            canvas.drawPath(path, pen)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) =
            canvas.drawRoundRect(l, t, r, b, radius, radius, pen)
        fun circle(x: Float, y: Float, radius: Float) = canvas.drawCircle(x, y, radius, pen)
        when (name) {
            "menu" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
            "list" -> { for (y in listOf(6f, 12f, 18f)) { circle(5f, y, .5f); line(9f, y, 20f, y) } }
            "grid" -> { rect(3f, 3f, 10f, 10f); rect(14f, 3f, 21f, 10f); rect(3f, 14f, 10f, 21f); rect(14f, 14f, 21f, 21f) }
            "more" -> { for (x in listOf(5f, 12f, 19f)) circle(x, 12f, 1f) }
            "back" -> line(15f, 4f, 7f, 12f, 15f, 20f)
            "next" -> line(9f, 4f, 17f, 12f, 9f, 20f)
            "check" -> line(4f, 12f, 10f, 18f, 20f, 6f)
            "clock" -> { circle(12f, 12f, 9f); line(12f, 6f, 12f, 12f, 16f, 14f) }
            "indent" -> { line(3f, 6f, 21f, 6f); line(11f, 11f, 21f, 11f); line(11f, 16f, 21f, 16f); line(3f, 11f, 7f, 14f, 3f, 17f) }
            "outdent" -> { line(3f, 6f, 21f, 6f); line(11f, 11f, 21f, 11f); line(11f, 16f, 21f, 16f); line(7f, 11f, 3f, 14f, 7f, 17f) }
            "sibling" -> { line(5f, 5f, 5f, 19f, 11f, 19f); line(10f, 14f, 10f, 21f); line(7f, 17.5f, 13f, 17.5f) }
            "up" -> line(5f, 16f, 12f, 7f, 19f, 16f)
            "down" -> line(5f, 8f, 12f, 17f, 19f, 8f)
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "inbox" -> { line(3f, 8f, 6f, 19f, 18f, 19f, 21f, 8f); line(3f, 13f, 8f, 13f, 10f, 16f, 14f, 16f, 16f, 13f, 21f, 13f) }
            "archive" -> { rect(4f, 7f, 20f, 20f); rect(3f, 4f, 21f, 8f, 1f); line(9f, 12f, 15f, 12f) }
            "folder" -> { line(3f, 6f, 10f, 6f, 12f, 9f, 21f, 9f, 21f, 19f, 3f, 19f, 3f, 6f) }
            "lock" -> { rect(5f, 10f, 19f, 21f); line(8f, 10f, 8f, 7f); canvas.drawArc(8f, 3f, 16f, 11f, 180f, 180f, false, pen) }
            "settings" -> { circle(12f, 12f, 4f); circle(12f, 12f, 9f); line(12f, 1f, 12f, 3f); line(12f, 21f, 12f, 23f); line(1f, 12f, 3f, 12f); line(21f, 12f, 23f, 12f) }
            "palette" -> { circle(12f, 12f, 9f); for ((x,y) in listOf(7f to 9f, 11f to 6f, 16f to 8f, 7f to 15f)) circle(x,y,.7f) }
            "shield" -> line(12f, 2f, 20f, 5f, 19f, 15f, 12f, 22f, 5f, 15f, 4f, 5f, 12f, 2f)
            "download" -> { line(12f, 3f, 12f, 16f); line(7f, 11f, 12f, 16f, 17f, 11f); line(4f, 18f, 4f, 21f, 20f, 21f, 20f, 18f) }
            "info" -> { circle(12f, 12f, 9f); circle(12f, 7f, .7f); line(12f, 11f, 12f, 17f) }
            "share" -> { circle(6f, 12f, 2f); circle(18f, 5f, 2f); circle(18f, 19f, 2f); line(8f, 11f, 16f, 6f); line(8f, 13f, 16f, 18f) }
            "strike" -> {
                canvas.drawText("S", 7f, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = pen.color; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.FILL
                })
                line(3f, 12f, 21f, 12f)
            }
            "link" -> {
                canvas.drawArc(2f, 7f, 14f, 19f, 40f, 260f, false, pen)
                canvas.drawArc(10f, 5f, 22f, 17f, 220f, 260f, false, pen)
                line(8f, 14f, 16f, 10f)
            }
            "trash" -> { rect(6f, 7f, 18f, 21f); line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f); line(10f, 11f, 10f, 17f); line(14f, 11f, 14f, 17f) }
            "image" -> { rect(3f, 3f, 21f, 21f); circle(8f, 8f, 1.5f); line(5f, 18f, 10f, 13f, 13f, 16f, 16f, 12f, 20f, 17f) }
            "document" -> { line(5f, 2f, 15f, 2f, 20f, 7f, 20f, 22f, 5f, 22f, 5f, 2f); line(15f, 2f, 15f, 7f, 20f, 7f); line(8f, 12f, 17f, 12f); line(8f, 16f, 17f, 16f) }
            "checklist" -> { rect(3f, 4f, 9f, 10f, 1f); rect(3f, 14f, 9f, 20f, 1f); line(12f, 7f, 21f, 7f); line(12f, 17f, 21f, 17f) }
        }
        canvas.restore()
    }
}

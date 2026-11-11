package com.openalc.inspnative

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.text.Layout
import org.json.JSONArray
import org.json.JSONObject

object RichTextCodec {
    private class CommentTextSpan(private val color: Int) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) {
            paint.color = color
            paint.textSize *= 0.88f
        }

        override fun updateMeasureState(paint: TextPaint) {
            paint.textSize *= 0.88f
        }
    }

    fun styleComment(text: Spannable, start: Int, end: Int, dark: Boolean) {
        text.setSpan(CommentTextSpan(if (dark) 0xffa4afa1.toInt() else 0xff777e77.toInt()),
            start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun removeCommentStyle(text: Spannable, start: Int, end: Int, dark: Boolean) {
        text.getSpans(start, end, CommentTextSpan::class.java).forEach { span ->
            val oldStart = text.getSpanStart(span)
            val oldEnd = text.getSpanEnd(span)
            text.removeSpan(span)
            if (oldStart < start) styleComment(text, oldStart, start, dark)
            if (oldEnd > end) styleComment(text, end, oldEnd, dark)
        }
    }

    fun empty(): String = JSONObject().put("version", 1).put("text", "").put("spans", JSONArray()).toString()

    fun plain(body: String): String = JSONObject(body).optString("text")

    fun encode(text: Spanned): String {
        val spans = JSONArray()
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            val type = when (span) {
                is StyleSpan -> if (span.style == Typeface.BOLD) "bold" else if (span.style == Typeface.ITALIC) "italic" else continue
                is UnderlineSpan -> "underline"
                is StrikethroughSpan -> "strike"
                is QuoteSpan -> "quote"
                is BulletSpan -> "bullet"
                is AlignmentSpan -> when (span.alignment) {
                    Layout.Alignment.ALIGN_CENTER -> "center"
                    Layout.Alignment.ALIGN_OPPOSITE -> "right"
                    else -> "left"
                }
                is BackgroundColorSpan -> "highlight"
                is RelativeSizeSpan -> "heading"
                is LeadingMarginSpan.Standard -> "indent"
                is URLSpan -> "link"
                is ImageSpan -> "image"
                else -> continue
            }
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val entry = JSONObject().put("type", type).put("start", start).put("end", end)
            if (span is URLSpan) entry.put("url", span.url)
            if (span is ImageSpan) entry.put("attachmentId", span.source)
            if (span is RelativeSizeSpan) entry.put("scale", span.sizeChange.toDouble())
            if (span is LeadingMarginSpan.Standard) entry.put("marginPx", span.getLeadingMargin(true))
            spans.put(entry)
        }
        return JSONObject().put("version", 1).put("text", text.toString()).put("spans", spans).toString()
    }

    fun decode(body: String, dark: Boolean = false, image: (String) -> Drawable? = { null }): SpannableStringBuilder {
        val source = JSONObject(body)
        val result = SpannableStringBuilder(source.optString("text"))
        val spans = source.optJSONArray("spans") ?: JSONArray()
        for (index in 0 until spans.length()) {
            val entry = spans.getJSONObject(index)
            val start = entry.getInt("start")
            val end = entry.getInt("end")
            if (start < 0 || end > result.length || start >= end) continue
            val span = when (entry.getString("type")) {
                "bold" -> StyleSpan(Typeface.BOLD)
                "italic" -> StyleSpan(Typeface.ITALIC)
                "underline" -> UnderlineSpan()
                "strike" -> StrikethroughSpan()
                "quote" -> QuoteSpan(0xff54d81b.toInt())
                "bullet" -> BulletSpan(18, 0xff54d81b.toInt())
                "center" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER)
                "right" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE)
                "left" -> AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL)
                "highlight" -> BackgroundColorSpan(if (dark) 0xff51451c.toInt() else 0xffffeea3.toInt())
                "heading" -> RelativeSizeSpan(entry.getDouble("scale").toFloat())
                "indent" -> LeadingMarginSpan.Standard(entry.getInt("marginPx"))
                "link" -> URLSpan(entry.getString("url"))
                "image" -> image(entry.getString("attachmentId"))?.let { ImageSpan(it, entry.getString("attachmentId")) }
                else -> null
            }
            if (span != null) result.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (entry.getString("type") == "quote") styleComment(result, start, end, dark)
        }
        return result
    }
}

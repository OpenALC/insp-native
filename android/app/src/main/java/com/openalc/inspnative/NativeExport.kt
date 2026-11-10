package com.openalc.inspnative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportedNote(val filename: String, val mimeType: String, val bytes: ByteArray)

object NativeExport {
    private data class Mark(val type: String, val start: Int, val end: Int, val url: String = "")
    private data class Section(val text: String, val marks: List<Mark>)
    private sealed interface Piece {
        val height: Int
        val gap: Int
        fun paint(canvas: Canvas, top: Int)
    }
    private class TextPiece(private val layout: StaticLayout, private val line: Int, override val gap: Int) : Piece {
        private val lineTop = layout.getLineTop(line)
        override val height = layout.getLineBottom(line) - lineTop
        override fun paint(canvas: Canvas, top: Int) {
            canvas.save()
            canvas.clipRect(0, top, layout.width, top + height)
            canvas.translate(0f, (top - lineTop).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
    private class ImagePiece(private val bitmap: Bitmap, private val width: Int, override val height: Int) : Piece {
        override val gap = 15
        override fun paint(canvas: Canvas, top: Int) {
            canvas.drawBitmap(bitmap, null, RectF(0f, top.toFloat(), width.toFloat(), (top + height).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        fun release() = bitmap.recycle()
    }

    fun plain(note: NativeNote): String = if (note.kind == "mindmap") {
        val lines = mutableListOf<String>()
        fun visit(node: JSONObject, depth: Int) {
            lines += "  ".repeat(depth) + node.getString("text")
            val children = node.getJSONArray("children")
            for (index in 0 until children.length()) visit(children.getJSONObject(index), depth + 1)
        }
        visit(JSONObject(note.body).getJSONObject("root"), 0)
        lines.joinToString("\n")
    } else RichTextCodec.plain(note.body).replace("\uFFFC", "[图片]")

    fun markdown(note: NativeNote, image: (String) -> ByteArray): ExportedNote {
        val output = if (note.kind == "mindmap") {
            val lines = mutableListOf<String>()
            fun visit(node: JSONObject, depth: Int) {
                lines += if (depth == 0) "# ${node.getString("text")}" else "  ".repeat(depth - 1) + "- ${node.getString("text")}" 
                val children = node.getJSONArray("children")
                for (index in 0 until children.length()) visit(children.getJSONObject(index), depth + 1)
            }
            visit(JSONObject(note.body).getJSONObject("root"), 0)
            lines.joinToString("\n") + "\n"
        } else {
            val source = JSONObject(note.body)
            val text = source.getString("text")
            val marks = marks(source)
            buildString {
                append("# ").append(note.title).append("\n\n")
                var start = 0
                while (start <= text.length) {
                    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                    val line = text.substring(start, end)
                    if (line == "────────────") append("---")
                    else {
                        val active = marks.filter { it.start < end && it.end > start }
                        val prefix = when {
                            active.any { it.type == "quote" } -> "> "
                            active.any { it.type == "bullet" } -> "- "
                            active.any { it.type == "heading" } -> "## "
                            else -> ""
                        }
                        append(prefix)
                        for (section in sections(text, marks, start, end)) {
                            if (section.text == "\uFFFC") {
                                val id = section.marks.first { it.type == "image" }.url
                                val bytes = image(id)
                                append("![图片](data:").append(mime(bytes)).append(";base64,")
                                    .append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append(')')
                            } else {
                                var value = section.text.replace(Regex("([\\\\`*_{}\\[\\]()<>#+.!|~-])")) { "\\" + it.value }
                                if (section.marks.any { it.type == "bold" }) value = "**$value**"
                                if (section.marks.any { it.type == "italic" }) value = "*$value*"
                                if (section.marks.any { it.type == "strike" }) value = "~~$value~~"
                                if (section.marks.any { it.type == "underline" }) value = "<u>$value</u>"
                                if (section.marks.any { it.type == "highlight" }) value = "<mark>$value</mark>"
                                section.marks.firstOrNull { it.type == "link" }?.let { value = "[$value](${it.url})" }
                                append(value)
                            }
                        }
                    }
                    append('\n')
                    if (end == text.length) break
                    start = end + 1
                }
            }
        }
        return ExportedNote(filename(note, "md"), "text/markdown", output.toByteArray(Charsets.UTF_8))
    }

    fun pdf(note: NativeNote, image: (String) -> ByteArray): ExportedNote {
        val margin = 40
        val width = 595
        val height = 842
        val usable = height - margin * 2
        val pieces = pieces(note, image, width - margin * 2, 17f, usable - 15)
        val pages = mutableListOf<MutableList<Piece>>(mutableListOf())
        var used = 0
        for (piece in pieces) {
            if (used + piece.height > usable && pages.last().isNotEmpty()) {
                pages.add(mutableListOf())
                used = 0
            }
            pages.last() += piece
            used += piece.height + piece.gap
        }
        val document = PdfDocument()
        for (index in pages.indices) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.save()
            page.canvas.clipRect(margin, margin, width - margin, height - margin)
            page.canvas.translate(margin.toFloat(), margin.toFloat())
            drawPieces(page.canvas, pages[index])
            page.canvas.restore()
            document.finishPage(page)
        }
        val output = ByteArrayOutputStream()
        document.writeTo(output); document.close()
        pieces.filterIsInstance<ImagePiece>().forEach(ImagePiece::release)
        return ExportedNote(filename(note, "pdf"), "application/pdf", output.toByteArray())
    }

    fun png(note: NativeNote, image: (String) -> ByteArray): ExportedNote {
        val width = 1080
        val margin = 64
        val pieces = pieces(note, image, width - margin * 2, 28f)
        val height = margin * 2 + pieces.sumOf { it.height + it.gap }
        require(height.toLong() * width <= 16_000_000) { "笔记较长，请改用 PDF 导出" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(margin.toFloat(), margin.toFloat())
        drawPieces(canvas, pieces)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        pieces.filterIsInstance<ImagePiece>().forEach(ImagePiece::release)
        return ExportedNote(filename(note, "png"), "image/png", output.toByteArray())
    }

    fun docx(note: NativeNote, image: (String) -> ByteArray): ExportedNote {
        val media = mutableListOf<Pair<String, ByteArray>>()
        val relations = mutableListOf<String>()
        val paragraphs = mutableListOf<String>()
        paragraphs += "<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr>${wordRun(note.title, listOf(Mark("bold", 0, 0)))}</w:p>"
        if (note.kind == "mindmap") {
            for (line in plain(note).substringAfter('\n', "").lines()) {
                paragraphs += "<w:p>${wordRun(line, emptyList())}</w:p>"
            }
        } else {
            val source = JSONObject(note.body)
            val value = source.getString("text")
            val marks = marks(source)
            var start = 0
            while (start <= value.length) {
                val end = value.indexOf('\n', start).let { if (it < 0) value.length else it }
                val active = marks.filter { it.start < end && it.end > start }
                val properties = buildString {
                    if (active.any { it.type == "heading" }) append("<w:pStyle w:val=\"Heading2\"/>")
                    if (active.any { it.type == "center" }) append("<w:jc w:val=\"center\"/>")
                    if (active.any { it.type == "right" }) append("<w:jc w:val=\"right\"/>")
                    active.firstOrNull { it.type == "indent" }?.let { append("<w:ind w:left=\"${it.url}\"/>") }
                }
                val runs = buildString {
                    if (active.any { it.type == "bullet" }) append(wordRun("• ", emptyList()))
                    for (section in sections(value, marks, start, end)) {
                        if (section.text == "\uFFFC") {
                            val id = section.marks.firstOrNull { it.type == "image" }?.url
                            if (id != null) {
                                val bytes = image(id)
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 1600 }
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                    BitmapFactory.Options().apply { inSampleSize = sample })
                                val output = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                val name = "image${media.size + 1}.png"
                                val relation = "image${media.size + 1}"
                                media += "word/media/$name" to output.toByteArray()
                                relations += "<Relationship Id=\"$relation\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>"
                                val width = minOf(bitmap.width, 500)
                                val height = bitmap.height * width / bitmap.width
                                append(wordImage(relation, media.size, width * 9525L, height * 9525L))
                                bitmap.recycle()
                            }
                        } else append(wordRun(section.text, section.marks))
                    }
                }
                paragraphs += "<w:p><w:pPr>$properties</w:pPr>$runs</w:p>"
                if (end == value.length) break
                start = end + 1
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
             xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
             xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
             xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
             <w:body>${paragraphs.joinToString("")}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""".trimIndent()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
            fun entry(name: String, content: String) = entry(name, content.toByteArray(Charsets.UTF_8))
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Default Extension="png" ContentType="image/png"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""".trimIndent())
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="document" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""".trimIndent())
            entry("word/document.xml", document)
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${relations.joinToString("")}</Relationships>""".trimIndent())
            for ((name, bytes) in media) entry(name, bytes)
        }
        return ExportedNote(filename(note, "docx"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.toByteArray())
    }

    private fun wordRun(value: String, marks: List<Mark>): String {
        if (value.isEmpty()) return ""
        val styles = buildString {
            if (marks.any { it.type == "bold" }) append("<w:b/>")
            if (marks.any { it.type == "italic" }) append("<w:i/>")
            if (marks.any { it.type == "underline" || it.type == "link" }) append("<w:u w:val=\"single\"/>")
            if (marks.any { it.type == "strike" }) append("<w:strike/>")
            if (marks.any { it.type == "highlight" }) append("<w:highlight w:val=\"yellow\"/>")
        }
        return "<w:r><w:rPr>$styles</w:rPr><w:t xml:space=\"preserve\">${xml(value)}</w:t></w:r>"
    }

    private fun wordImage(relation: String, id: Int, width: Long, height: Long) = """
        <w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
        <wp:extent cx="$width" cy="$height"/><wp:docPr id="$id" name="Image $id"/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
        <pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="Image $id"/><pic:cNvPicPr/></pic:nvPicPr>
        <pic:blipFill><a:blip r:embed="$relation"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
        <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$width" cy="$height"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
        </a:graphicData></a:graphic></wp:inline></w:drawing></w:r>
    """.trimIndent()

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun drawPieces(canvas: Canvas, pieces: List<Piece>) {
        var top = 0
        for (piece in pieces) { piece.paint(canvas, top); top += piece.height + piece.gap }
    }

    private fun pieces(note: NativeNote, image: (String) -> ByteArray, width: Int, fontSize: Float,
                       maxImageHeight: Int = Int.MAX_VALUE): List<Piece> {
        val output = mutableListOf<Piece>()
        fun textPiece(value: CharSequence, size: Float) {
            if (value.isEmpty()) return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(5f, 1f).build()
            for (line in 0 until layout.lineCount) output += TextPiece(layout, line, if (line == layout.lineCount - 1) 15 else 0)
        }
        val title = SpannableStringBuilder(note.title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textPiece(title, fontSize * 1.4f)
        if (note.kind == "mindmap") { textPiece(plain(note).substringAfter('\n', ""), fontSize); return output }
        val content = RichTextCodec.decode(note.body)
        val marks = marks(JSONObject(note.body))
        var start = 0
        for (index in content.indices) if (content[index] == '\uFFFC') {
            textPiece(content.subSequence(start, index), fontSize)
            val id = marks.firstOrNull { it.type == "image" && it.start == index }?.url
            if (id != null) {
                val bytes = image(id)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= width * 2 }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
                val imageWidth = minOf(width, bitmap.width,
                    (maxImageHeight.toLong() * bitmap.width / bitmap.height).toInt().coerceAtLeast(1))
                output += ImagePiece(bitmap, imageWidth, bitmap.height * imageWidth / bitmap.width)
            }
            start = index + 1
        }
        textPiece(content.subSequence(start, content.length), fontSize)
        return output
    }

    private fun marks(source: JSONObject): List<Mark> {
        val array = source.optJSONArray("spans") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val type = item.getString("type")
            val extra = when (type) {
                "image" -> item.optString("attachmentId")
                "indent" -> (item.optInt("marginPx") * 15).toString()
                else -> item.optString("url")
            }
            Mark(type, item.getInt("start"), item.getInt("end"), extra)
        }
    }

    private fun sections(text: String, marks: List<Mark>, start: Int, end: Int): List<Section> {
        val bounds = sortedSetOf(start, end)
        for (mark in marks) if (mark.start < end && mark.end > start) {
            bounds += mark.start.coerceIn(start, end)
            bounds += mark.end.coerceIn(start, end)
        }
        return bounds.toList().zipWithNext().map { (from, to) ->
            Section(text.substring(from, to), marks.filter { it.start <= from && it.end >= to })
        }
    }

    private fun mime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
        else -> "image/webp"
    }

    private fun filename(note: NativeNote, extension: String): String =
        note.title.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").take(80).ifBlank { "笔记" } + ".$extension"
}

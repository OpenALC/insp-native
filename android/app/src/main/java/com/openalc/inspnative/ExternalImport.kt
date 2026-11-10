package com.openalc.inspnative

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

object ExternalImport {
    fun read(filename: String, bytes: ByteArray): TransferBundle = when {
        filename.endsWith(".zip", true) -> TransferImport.read(ByteArrayInputStream(bytes))
        filename.endsWith(".md", true) || filename.endsWith(".markdown", true) ->
            TransferBundle(emptyList(), listOf(markdown(filename.substringBeforeLast('.'), bytes.toString(Charsets.UTF_8), emptyMap())))
        filename.endsWith(".txt", true) -> TransferBundle(emptyList(), listOf(plain(filename.substringBeforeLast('.'), bytes.toString(Charsets.UTF_8))))
        else -> error("不支持此文件格式")
    }

    fun fromZip(archive: Map<String, ByteArray>): TransferBundle {
        if (archive.containsKey("metadata.json") && archive.containsKey("content.md")) {
            val metadata = JSONObject(archive.getValue("metadata.json").toString(Charsets.UTF_8))
            val content = archive.getValue("content.md").toString(Charsets.UTF_8)
            val title = metadata.optString("title").ifBlank {
                content.lineSequence().firstOrNull { it.isNotBlank() }?.replace(Regex("[<*>#_`]"), "")?.trim()?.take(48)
                    .orEmpty().ifBlank { "导入的小米笔记" }
            }
            return TransferBundle(emptyList(), listOf(markdown(title, content, archive)))
        }
        val notes = archive.filterKeys { it.endsWith(".md", true) || it.endsWith(".txt", true) }
            .map { (name, content) ->
                val title = name.substringAfterLast('/').substringBeforeLast('.')
                if (name.endsWith(".txt", true)) plain(title, content.toString(Charsets.UTF_8))
                else markdown(title, content.toString(Charsets.UTF_8), archive)
            }
        require(notes.isNotEmpty()) { "ZIP 中没有可导入的笔记" }
        return TransferBundle(emptyList(), notes)
    }

    private fun plain(title: String, content: String): TransferNote {
        val body = JSONObject().put("version", 1).put("text", content).put("spans", JSONArray()).toString()
        return TransferNote(NativeNote(UUID.randomUUID().toString(), title, body, "richtext", null, false,
            System.currentTimeMillis()), emptyList())
    }

    private fun markdown(title: String, content: String, archive: Map<String, ByteArray>): TransferNote {
        val output = StringBuilder()
        val spans = JSONArray()
        val images = mutableListOf<TransferImage>()
        fun span(type: String, start: Int, end: Int, extra: Pair<String, String>? = null) {
            if (end <= start) return
            val item = JSONObject().put("type", type).put("start", start).put("end", end)
            extra?.let { item.put(it.first, it.second) }
            spans.put(item)
        }
        fun inline(line: String) {
            var index = 0
            while (index < line.length) {
                val image = Regex("^!\\[([^]]*)]\\(([^)]+)\\)").find(line, index)?.takeIf { it.range.first == index }
                if (image != null) {
                    val path = image.groupValues[2].substringBefore('?').removePrefix("./")
                    val bytes = archive[path] ?: archive["attachment/${path.substringAfterLast('/')}"]
                    if (bytes != null) {
                        val id = UUID.randomUUID().toString()
                        images += TransferImage(id, bytes, imageMime(bytes))
                        val start = output.length; output.append('\uFFFC'); span("image", start, output.length, "attachmentId" to id)
                    } else output.append("[图片]")
                    index += image.value.length
                    continue
                }
                val link = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(line, index)?.takeIf { it.range.first == index }
                if (link != null) {
                    val start = output.length; output.append(link.groupValues[1])
                    span("link", start, output.length, "url" to link.groupValues[2])
                    index += link.value.length
                    continue
                }
                val wrappers = listOf("**" to "bold", "~~" to "strike", "<u>" to "underline", "<mark>" to "highlight", "*" to "italic")
                val wrapper = wrappers.firstOrNull { line.startsWith(it.first, index) }
                if (wrapper != null) {
                    val closing = when (wrapper.first) { "<u>" -> "</u>"; "<mark>" -> "</mark>"; else -> wrapper.first }
                    val end = line.indexOf(closing, index + wrapper.first.length)
                    if (end > index) {
                        val start = output.length; output.append(line.substring(index + wrapper.first.length, end))
                        span(wrapper.second, start, output.length)
                        index = end + closing.length
                        continue
                    }
                }
                output.append(line[index]); index++
            }
        }
        for (raw in content.replace("\r\n", "\n").lines()) {
            val line = raw.trimStart()
            val heading = Regex("^#{1,3}\\s+").find(line)
            val quote = line.startsWith("> ")
            val bullet = Regex("^(?:[-*+] |\\d+\\. )").find(line)
            val clean = when {
                heading != null -> line.drop(heading.value.length)
                quote -> line.drop(2)
                bullet != null -> line.drop(bullet.value.length)
                else -> raw
            }
            val start = output.length
            inline(clean)
            if (heading != null) { span("heading", start, output.length, "scale" to "1.35"); span("bold", start, output.length) }
            if (quote) span("quote", start, output.length)
            if (bullet != null) span("bullet", start, output.length)
            output.append('\n')
        }
        val body = JSONObject().put("version", 1).put("text", output.toString().trimEnd('\n')).put("spans", spans).toString()
        return TransferNote(NativeNote(UUID.randomUUID().toString(), title, body, "richtext", null, false,
            System.currentTimeMillis()), images)
    }

    private fun imageMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
        else -> "image/webp"
    }
}

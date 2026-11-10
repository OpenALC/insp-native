package com.openalc.inspnative

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

data class TransferImage(val id: String, val bytes: ByteArray, val mimeType: String)
data class TransferNote(val note: NativeNote, val images: List<TransferImage>)
data class TransferBundle(val groups: List<NoteGroup>, val notes: List<TransferNote>)

object TransferImport {
    fun read(input: InputStream): TransferBundle {
        val archive = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        require(output.size() <= 32 * 1024 * 1024) { "迁移文件中的单项内容过大" }
                    }
                    val bytes = output.toByteArray()
                    total += bytes.size
                    require(total <= 256 * 1024 * 1024) { "迁移文件过大" }
                    archive[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }
        if (!archive.containsKey("manifest.json")) return ExternalImport.fromZip(archive)
        val manifest = JSONObject(archive.getValue("manifest.json").toString(Charsets.UTF_8))
        require(manifest.getString("format") == "openalc.insp.transfer.v1")
        val groups = manifest.getJSONArray("groups").objects().map {
            NoteGroup(it.getString("id"), it.getString("name"))
        }
        val notes = manifest.getJSONArray("notes").objects().map { source ->
            val kind = source.getString("kind")
            require(kind == "richtext" || kind == "mindmap")
            val privacy = source.getString("privacy")
            require(privacy == "normal" || privacy == "private")
            val images = mutableListOf<TransferImage>()
            val body = if (kind == "mindmap") source.getJSONObject("content").toString() else {
                richBody(source.getJSONObject("content"), archive, images)
            }
            TransferNote(NativeNote(
                source.getString("id"), source.getString("title"), body, kind,
                if (source.isNull("groupId")) null else source.getString("groupId"),
                privacy == "private",
                Instant.parse(source.getString("updatedAt")).toEpochMilli(),
            ), images)
        }
        return TransferBundle(groups, notes)
    }

    private fun richBody(document: JSONObject, archive: Map<String, ByteArray>, images: MutableList<TransferImage>): String {
        val text = StringBuilder()
        val spans = JSONArray()
        val imageIds = mutableMapOf<String, String>()
        fun span(type: String, start: Int, end: Int, extra: Pair<String, String>? = null) {
            if (end <= start) return
            val item = JSONObject().put("type", type).put("start", start).put("end", end)
            extra?.let { item.put(it.first, it.second) }
            spans.put(item)
        }
        fun visit(node: JSONObject) {
            val start = text.length
            when (node.optString("type")) {
                "text" -> {
                    text.append(node.optString("text"))
                    for (mark in (node.optJSONArray("marks") ?: JSONArray()).objects()) {
                        when (val type = mark.optString("type")) {
                            "bold", "italic", "underline", "strike" -> span(type, start, text.length)
                            "link" -> span("link", start, text.length, "url" to mark.optJSONObject("attrs")?.optString("href").orEmpty())
                        }
                    }
                    return
                }
                "hardBreak" -> { text.append('\n'); return }
                "image" -> {
                    val id = node.optJSONObject("attrs")?.optString("attachmentId").orEmpty()
                    val bytes = archive["attachments/$id"]
                    if (bytes != null) {
                        val newId = imageIds.getOrPut(id) {
                            UUID.randomUUID().toString().also { images += TransferImage(it, bytes, imageMime(bytes)) }
                        }
                        text.append('\uFFFC')
                        span("image", start, text.length, "attachmentId" to newId)
                    } else text.append("[图片]")
                    return
                }
                "horizontalRule" -> { text.append("────────"); text.append('\n'); return }
                "taskItem" -> text.append(if (node.optJSONObject("attrs")?.optBoolean("checked") == true) "☑ " else "☐ ")
            }
            for (child in (node.optJSONArray("content") ?: JSONArray()).objects()) visit(child)
            when (node.optString("type")) {
                "heading" -> span("bold", start, text.length)
                "blockquote" -> span("quote", start, text.length)
                "listItem" -> span("bullet", start, text.length)
            }
            if (node.optString("type") in setOf("paragraph", "heading", "listItem", "blockquote", "codeBlock", "horizontalRule")) text.append('\n')
        }
        visit(document)
        return JSONObject().put("version", 1).put("text", text.toString().trimEnd('\n')).put("spans", spans).toString()
    }

    private fun imageMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
        else -> "image/webp"
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}

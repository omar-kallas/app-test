package com.omarkallas.inkfield.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.DrawingInfo
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local, offline storage. Each drawing is three files in app storage:
 * <id>.json (the document), <id>.meta (gallery info) and <id>.png (thumbnail).
 * Writes are atomic (temp file + rename) so a crash never corrupts a drawing.
 */
class Repository(context: Context) {
    private val dir = File(context.filesDir, "drawings").apply { mkdirs() }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun docFile(id: String) = File(dir, "$id.json")
    private fun metaFile(id: String) = File(dir, "$id.meta")
    fun thumbFile(id: String) = File(dir, "$id.png")

    fun newId(): String = UUID.randomUUID().toString()

    fun list(): List<DrawingInfo> {
        purgeOldTrash()
        return dir.listFiles { f -> f.name.endsWith(".meta") }
            ?.mapNotNull { f -> runCatching { json.decodeFromString<DrawingInfo>(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updated }
            ?: emptyList()
    }

    fun load(id: String): Drawing? =
        runCatching { json.decodeFromString<Drawing>(docFile(id).readText()) }.getOrNull()

    @Synchronized
    fun save(d: Drawing, thumb: Bitmap?) {
        atomicWrite(docFile(d.id), json.encodeToString(Drawing.serializer(), d).toByteArray())
        val old = readMeta(d.id)
        val info = DrawingInfo(d.id, d.name, d.created, d.updated, old?.deletedAt)
        atomicWrite(metaFile(d.id), json.encodeToString(DrawingInfo.serializer(), info).toByteArray())
        if (thumb != null) {
            val tmp = File(dir, "${d.id}.png.tmp")
            tmp.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(thumbFile(d.id))
        }
    }

    fun loadThumb(id: String): Bitmap? {
        val f = thumbFile(id)
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    private fun readMeta(id: String): DrawingInfo? =
        runCatching { json.decodeFromString<DrawingInfo>(metaFile(id).readText()) }.getOrNull()

    private fun writeMeta(info: DrawingInfo) =
        atomicWrite(metaFile(info.id), json.encodeToString(DrawingInfo.serializer(), info).toByteArray())

    fun rename(id: String, name: String) {
        val d = load(id) ?: return
        val nd = d.copy(name = name)
        atomicWrite(docFile(id), json.encodeToString(Drawing.serializer(), nd).toByteArray())
        readMeta(id)?.let { writeMeta(it.copy(name = name)) }
    }

    fun duplicate(id: String): String? {
        val d = load(id) ?: return null
        val nid = newId()
        val now = System.currentTimeMillis()
        val nd = d.copy(id = nid, name = "${d.name} copy", created = now, updated = now)
        atomicWrite(docFile(nid), json.encodeToString(Drawing.serializer(), nd).toByteArray())
        writeMeta(DrawingInfo(nid, nd.name, now, now))
        val t = thumbFile(id)
        if (t.exists()) t.copyTo(thumbFile(nid), overwrite = true)
        return nid
    }

    /** Moves a drawing to Recently Deleted. */
    fun trash(id: String) {
        readMeta(id)?.let { writeMeta(it.copy(deletedAt = System.currentTimeMillis())) }
    }

    fun restore(id: String) {
        readMeta(id)?.let { writeMeta(it.copy(deletedAt = null)) }
    }

    fun deleteForever(id: String) {
        docFile(id).delete(); metaFile(id).delete(); thumbFile(id).delete()
    }

    private fun purgeOldTrash() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { f ->
            val info = runCatching { json.decodeFromString<DrawingInfo>(f.readText()) }.getOrNull() ?: return@forEach
            if (info.deletedAt != null && info.deletedAt < cutoff) deleteForever(info.id)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

package com.omarkallas.inkfield.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.DrawingInfo
import com.omarkallas.inkfield.model.Folder
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

    /** Optional copy of every drawing in a user-chosen device folder. */
    val mirror = Mirror(context)

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
        val info = DrawingInfo(d.id, d.name, d.created, d.updated, old?.deletedAt, old?.folder)
        atomicWrite(metaFile(d.id), json.encodeToString(DrawingInfo.serializer(), info).toByteArray())
        if (thumb != null) {
            val tmp = File(dir, "${d.id}.png.tmp")
            tmp.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(thumbFile(d.id))
        }
    }

    /** Asset file names referenced by any drawing, including ones in Recently Deleted. */
    fun referencedAssets(): Set<String> {
        val rx = Regex("\"asset\":\"([^\"]+)\"")
        val out = HashSet<String>()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching { rx.findAll(f.readText()).forEach { out.add(it.groupValues[1]) } }
        }
        return out
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
        writeMeta(DrawingInfo(nid, nd.name, now, now, folder = readMeta(id)?.folder))
        val t = thumbFile(id)
        if (t.exists()) t.copyTo(thumbFile(nid), overwrite = true)
        return nid
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------
    private val foldersFile = File(dir.parentFile, "folders.json")

    @kotlinx.serialization.Serializable
    data class BackupIndex(val folders: List<Folder> = emptyList(), val placement: Map<String, String> = emptyMap())

    fun folders(): List<Folder> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Folder.serializer()), foldersFile.readText())
    }.getOrDefault(emptyList())

    private fun saveFolders(list: List<Folder>) =
        atomicWrite(foldersFile, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Folder.serializer()), list).toByteArray())

    fun createFolder(name: String, parent: String?): Folder {
        val f = Folder(newId(), name.ifBlank { "New folder" }, parent, created = System.currentTimeMillis())
        saveFolders(folders() + f)
        return f
    }

    fun updateFolder(f: Folder) = saveFolders(folders().map { if (it.id == f.id) f else it })

    /** Deletes a folder; its drawings and subfolders move up to its parent. */
    fun deleteFolder(id: String) {
        val all = folders()
        val f = all.firstOrNull { it.id == id } ?: return
        saveFolders(all.filter { it.id != id }.map { if (it.parent == id) it.copy(parent = f.parent) else it })
        list().filter { it.folder == id }.forEach { moveDrawing(it.id, f.parent) }
    }

    fun moveDrawing(id: String, folder: String?) {
        readMeta(id)?.let { writeMeta(it.copy(folder = folder)) }
    }

    fun foldersJson(): String = json.encodeToString(
        BackupIndex.serializer(),
        BackupIndex(folders(), list().mapNotNull { i -> i.folder?.let { i.id to it } }.toMap()),
    )

    private var importedPlacement: Map<String, String> = emptyMap()

    /** Adds folders from a backup that don't exist yet, remembering where its drawings belong. */
    fun mergeFolders(text: String) {
        val idx = runCatching { json.decodeFromString(BackupIndex.serializer(), text) }.getOrNull() ?: return
        val existing = folders()
        val ids = existing.map { it.id }.toSet()
        saveFolders(existing + idx.folders.filter { it.id !in ids })
        importedPlacement = idx.placement
    }

    fun folderOfImported(drawingId: String): String? = importedPlacement[drawingId]

    /** Saves an imported drawing, giving it a fresh id if one with the same id exists. */
    fun importDrawing(d0: Drawing, folder: String?) {
        val d = if (docFile(d0.id).exists()) d0.copy(id = newId(), name = d0.name + " (imported)") else d0
        val thumb = runCatching {
            com.omarkallas.inkfield.engine.Exporter.thumbnail(d, com.omarkallas.inkfield.engine.StrokeCache())
        }.getOrNull()
        save(d, thumb)
        moveDrawing(d.id, folder)
    }

    /** Moves a drawing to Recently Deleted. */
    fun trash(id: String) {
        readMeta(id)?.let { writeMeta(it.copy(deletedAt = System.currentTimeMillis())) }
    }

    fun restore(id: String) {
        readMeta(id)?.let { writeMeta(it.copy(deletedAt = null)) }
    }

    fun deleteForever(id: String) {
        if (mirror.treeUri != null) load(id)?.let { mirror.remove(it) }
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

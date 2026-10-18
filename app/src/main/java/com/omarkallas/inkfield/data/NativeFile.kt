package com.omarkallas.inkfield.data

import com.omarkallas.inkfield.engine.Assets
import com.omarkallas.inkfield.model.Drawing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The native .inkfield format: a zip holding drawing.json plus every image it uses under assets/.
 * A backup of several drawings is a zip of .inkfield files (plus folders.json for the folder tree).
 */
object NativeFile {
    const val EXT = "inkfield"

    fun write(repo: Repository, d: Drawing, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("drawing.json"))
            z.write(repo.json.encodeToString(Drawing.serializer(), d).toByteArray())
            z.closeEntry()
            val assets = d.strokes.mapNotNull { it.asset }.toSet()
            for (a in assets) {
                val bytes = Assets.bytes(a) ?: continue
                z.putNextEntry(ZipEntry("assets/$a"))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    fun bytes(repo: Repository, d: Drawing): ByteArray = ByteArrayOutputStream().also { write(repo, d, it) }.toByteArray()

    /** Reads an .inkfield stream, restoring its images; returns the drawing (with its original id). */
    fun read(repo: Repository, input: InputStream): Drawing? {
        var drawing: Drawing? = null
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "drawing.json" -> drawing = runCatching { repo.json.decodeFromString(Drawing.serializer(), z.readBytes().decodeToString()) }.getOrNull()
                    name.startsWith("assets/") && !name.contains("..") -> {
                        val f = Assets.file(name.removePrefix("assets/"))
                        if (!f.exists()) runCatching { f.writeBytes(z.readBytes()) }
                    }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return drawing
    }

    /** A backup zip: one .inkfield per drawing, plus the folder list. */
    fun writeBackup(repo: Repository, ids: List<String>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            for (id in ids) {
                val d = repo.load(id) ?: continue
                z.putNextEntry(ZipEntry("${d.id}.$EXT"))
                z.write(bytes(repo, d))
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("folders.json"))
            z.write(repo.foldersJson().toByteArray())
            z.closeEntry()
        }
    }

    /**
     * Imports a single .inkfield or a backup zip. Drawings that already exist get a new id
     * so nothing is overwritten. Returns how many drawings were imported.
     */
    fun importAny(repo: Repository, bytes: ByteArray, folder: String?): Int {
        // A single drawing has drawing.json at the top level.
        val isSingle = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry; var found = false
            while (e != null) { if (e.name == "drawing.json") found = true; e = z.nextEntry }
            found
        }
        if (isSingle) {
            val d = read(repo, ByteArrayInputStream(bytes)) ?: return 0
            repo.importDrawing(d, folder)
            return 1
        }
        var count = 0
        var foldersJson: String? = null
        val drawings = ArrayList<Drawing>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".$EXT")) read(repo, ByteArrayInputStream(z.readBytes()))?.let { drawings.add(it) }
                else if (e.name == "folders.json") foldersJson = z.readBytes().decodeToString()
                z.closeEntry()
                e = z.nextEntry
            }
        }
        foldersJson?.let { repo.mergeFolders(it) }
        for (d in drawings) { repo.importDrawing(d, folder ?: repo.folderOfImported(d.id)); count++ }
        return count
    }
}

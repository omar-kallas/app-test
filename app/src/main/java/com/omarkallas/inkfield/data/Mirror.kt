package com.omarkallas.inkfield.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.omarkallas.inkfield.model.Drawing

/**
 * Optional visible copy of every drawing as an .inkfield file in a folder the user picked
 * (through the system folder picker). Files there survive uninstalling the app and can be
 * opened from other devices. Inkfield writes to the folder; it doesn't watch it for changes.
 */
class Mirror(private val context: Context) {
    private val sp = context.getSharedPreferences("inkfield", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = sp.getString("mirrorTree", null)?.let { Uri.parse(it) }
        set(v) { sp.edit().putString("mirrorTree", v?.toString()).apply() }

    fun folderName(): String? {
        val t = treeUri ?: return null
        return runCatching { DocumentsContract.getTreeDocumentId(t).substringAfterLast(':').ifEmpty { "Selected folder" } }.getOrNull()
    }

    private fun fileName(d: Drawing): String {
        val clean = d.name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "Drawing" }.take(60)
        return "$clean-${d.id.take(8)}.${NativeFile.EXT}"
    }

    /** Child documents of the tree: (documentId, displayName). */
    private fun children(tree: Uri): List<Pair<String, String>> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getString(1)) }
        return out
    }

    /** Writes (or replaces) the drawing's file in the mirror folder. Errors are ignored. */
    fun write(repo: Repository, d: Drawing) {
        val tree = treeUri ?: return
        runCatching {
            val name = fileName(d)
            val suffix = "-${d.id.take(8)}.${NativeFile.EXT}"
            val existing = children(tree)
            // Remove copies under an old name (after a rename).
            existing.filter { it.second.endsWith(suffix) && it.second != name }.forEach {
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first))
            }
            val target = existing.firstOrNull { it.second == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.first) }
                ?: DocumentsContract.createDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                    "application/octet-stream", name,
                )
            target?.let { u -> context.contentResolver.openOutputStream(u, "wt")?.use { NativeFile.write(repo, d, it) } }
        }
    }

    fun remove(d: Drawing) {
        val tree = treeUri ?: return
        runCatching {
            val suffix = "-${d.id.take(8)}.${NativeFile.EXT}"
            children(tree).filter { it.second.endsWith(suffix) }.forEach {
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first))
            }
        }
    }
}

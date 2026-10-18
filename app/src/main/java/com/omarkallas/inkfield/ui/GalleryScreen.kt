package com.omarkallas.inkfield.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.omarkallas.inkfield.data.NativeFile
import com.omarkallas.inkfield.data.Repository
import com.omarkallas.inkfield.engine.Assets
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.DrawingInfo
import com.omarkallas.inkfield.model.Folder
import com.omarkallas.inkfield.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortBy(val label: String) { UPDATED("Date updated"), CREATED("Date created"), NAME("Name") }
private enum class GroupBy(val label: String) { NONE("No grouping"), MONTH("By month") }

private sealed interface GalleryItem {
    data class Header(val title: String) : GalleryItem
    data object NewTile : GalleryItem
    data class FolderTile(val folder: Folder) : GalleryItem
    data class DrawingTile(val info: DrawingInfo) : GalleryItem
}

@Composable
fun GalleryScreen(repo: Repository, onOpen: (String) -> Unit, onNew: (folder: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("inkfield", Context.MODE_PRIVATE) }
    var refresh by remember { mutableIntStateOf(0) }
    var trashView by rememberSaveable { mutableStateOf(false) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(runCatching { SortBy.valueOf(prefs.getString("gSort", "UPDATED")!!) }.getOrDefault(SortBy.UPDATED)) }
    var groupBy by remember { mutableStateOf(runCatching { GroupBy.valueOf(prefs.getString("gGroup", "NONE")!!) }.getOrDefault(GroupBy.NONE)) }
    var tileSize by remember { mutableIntStateOf(prefs.getInt("gTile", 170)) }

    val all by produceState(initialValue = emptyList<DrawingInfo>(), refresh) { value = withContext(Dispatchers.IO) { repo.list() } }
    val folders by produceState(initialValue = emptyList<Folder>(), refresh) { value = withContext(Dispatchers.IO) { repo.folders() } }
    val current = folders.firstOrNull { it.id == folderId }

    var renaming by remember { mutableStateOf<DrawingInfo?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    var notesFolder by remember { mutableStateOf<Folder?>(null) }
    var moving by remember { mutableStateOf<DrawingInfo?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** Makes a new drawing holding one image and opens it. */
    fun newWithImage(bmp: Bitmap?) {
        if (bmp == null) { toast("Couldn't read that image"); return }
        val asset = Assets.save(bmp) ?: return
        val now = System.currentTimeMillis()
        val count = all.count { it.deletedAt == null } + 1
        var d = Drawing.blank(repo.newId(), "Drawing $count", now)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val k = minOf(1f, 1200f / maxOf(w, h))
        val img = Stroke(
            d.nextId, BrushType.IMAGE, 1f, 0xFFFFFFFF.toInt(), 1f, 0f, d.activeLayerId,
            floatArrayOf(0f, 0f, 0.6f, 0f, w * k, 0f, 0.6f, 0f, 0f, h * k, 0.6f, 0f), asset = asset,
        )
        d = d.copy(strokes = listOf(img), nextId = d.nextId + 1, autoLayers = false,
            viewport = com.omarkallas.inkfield.model.Viewport(tx = 60f, ty = 140f))
        repo.save(d, null)
        repo.moveDrawing(d.id, folderId)
        onOpen(d.id)
    }

    // ---- pickers ----
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u -> scope.launch { newWithImage(withContext(Dispatchers.IO) { Assets.decode { context.contentResolver.openInputStream(u) } }) } }
    }
    val cameraFile = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { newWithImage(withContext(Dispatchers.IO) { Assets.decode { cameraFile.inputStream() } }) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            scope.launch {
                val name = u.lastPathSegment?.lowercase() ?: ""
                val mime = context.contentResolver.getType(u) ?: ""
                if (mime.startsWith("image/")) {
                    newWithImage(withContext(Dispatchers.IO) { Assets.decode { context.contentResolver.openInputStream(u) } })
                    return@launch
                }
                val n = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                        NativeFile.importAny(repo, bytes, folderId)
                    }.getOrDefault(0)
                }
                toast(if (n > 0) "Imported $n drawing(s)" else if (name.endsWith(".pdf")) "Open a drawing and use Import to bring in PDFs" else "That file isn't an Inkfield drawing or backup")
                refresh++
            }
        }
    }
    var exportIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { u ->
            val ids = exportIds
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(u)?.use { NativeFile.writeBackup(repo, ids, it) } != null }.getOrDefault(false)
                }
                toast(if (ok) "Backed up ${ids.size} drawing(s)" else "Couldn't write the backup")
            }
        }
    }
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { u ->
            val id = exportIds.firstOrNull() ?: return@let
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val d = repo.load(id) ?: return@runCatching false
                        context.contentResolver.openOutputStream(u)?.use { NativeFile.write(repo, d, it) } != null
                    }.getOrDefault(false)
                }
                toast(if (ok) "Saved" else "Couldn't save the file")
            }
        }
    }
    val pickMirror = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            repo.mirror.treeUri = uri
            scope.launch {
                withContext(Dispatchers.IO) { repo.list().filter { it.deletedAt == null }.forEach { i -> repo.load(i.id)?.let { repo.mirror.write(repo, it) } } }
                toast("Drawings are now also saved to that folder")
            }
            refresh++
        }
    }

    fun shareDrawing(id: String) {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val d = repo.load(id) ?: return@withContext null
                val name = d.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Drawing" }
                File(File(context.cacheDir, "exports").apply { mkdirs() }, "$name.${NativeFile.EXT}").also { out -> out.outputStream().use { NativeFile.write(repo, d, it) } }
            } ?: return@launch
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share drawing"))
        }
    }

    // ---- what's shown ----
    val live = all.filter { it.deletedAt == null }
    val inTrash = all.filter { it.deletedAt != null }
    val drawingsHere = (if (trashView) inTrash else live.filter { it.folder == folderId }).let { list ->
        when (sortBy) {
            SortBy.UPDATED -> list.sortedByDescending { it.updated }
            SortBy.CREATED -> list.sortedByDescending { it.created }
            SortBy.NAME -> list.sortedBy { it.name.lowercase() }
        }
    }
    val subfolders = if (trashView) emptyList() else folders.filter { it.parent == folderId }.sortedBy { it.name.lowercase() }
    val gridItems = buildList<GalleryItem> {
        if (!trashView) add(GalleryItem.NewTile)
        subfolders.forEach { add(GalleryItem.FolderTile(it)) }
        if (groupBy == GroupBy.MONTH && !trashView) {
            val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            drawingsHere.groupBy { fmt.format(Date(if (sortBy == SortBy.CREATED) it.created else it.updated)) }.forEach { (title, list) ->
                add(GalleryItem.Header(title)); list.forEach { add(GalleryItem.DrawingTile(it)) }
            }
        } else drawingsHere.forEach { add(GalleryItem.DrawingTile(it)) }
    }

    Column(Modifier.fillMaxSize().background(Ink.chrome).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null && !trashView) {
                GlyphButton(Glyph.UNDO, { folderId = current.parent }, contentDescription = "Back")
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when { trashView -> "Recently Deleted"; current != null -> current.name; else -> "Inkfield" },
                fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink.text,
                modifier = Modifier.clickable(enabled = current != null && !trashView) { renamingFolder = current },
            )
            Spacer(Modifier.weight(1f))
            Chip("Drawings", !trashView) { trashView = false }
            Spacer(Modifier.width(6.dp))
            Chip("Recently Deleted", trashView) { trashView = true }
            Box {
                GlyphButton(Glyph.MORE, { menu = true }, contentDescription = "Gallery options")
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SortBy.entries.forEach { s ->
                        DropdownMenuItem(text = { Text((if (sortBy == s) "✓ " else "") + "Sort: " + s.label) }, onClick = {
                            sortBy = s; prefs.edit().putString("gSort", s.name).apply(); menu = false
                        })
                    }
                    GroupBy.entries.forEach { g ->
                        DropdownMenuItem(text = { Text((if (groupBy == g) "✓ " else "") + g.label) }, onClick = {
                            groupBy = g; prefs.edit().putString("gGroup", g.name).apply(); menu = false
                        })
                    }
                    DropdownMenuItem(text = { Text("Thumbnails: " + when { tileSize < 150 -> "small"; tileSize < 210 -> "medium"; else -> "large" }) }, onClick = {
                        tileSize = when { tileSize < 150 -> 170; tileSize < 210 -> 240; else -> 130 }; prefs.edit().putInt("gTile", tileSize).apply()
                    })
                    DropdownMenuItem(text = { Text("Back up all drawings…") }, onClick = {
                        menu = false; exportIds = live.map { it.id }; backup.launch("Inkfield backup.zip")
                    })
                    DropdownMenuItem(text = { Text("Import drawings or a backup…") }, onClick = { menu = false; importFile.launch(arrayOf("*/*")) })
                    DropdownMenuItem(
                        text = { Text(repo.mirror.treeUri?.let { "Also saving to “${repo.mirror.folderName()}” — change…" } ?: "Also save drawings to a device folder…") },
                        onClick = { menu = false; pickMirror.launch(null) },
                    )
                    if (repo.mirror.treeUri != null) DropdownMenuItem(text = { Text("Stop saving to the device folder") }, onClick = {
                        menu = false; repo.mirror.treeUri = null; refresh++
                    })
                }
            }
        }
        if (current != null && !trashView) {
            Text(
                current.notes.ifEmpty { "Add notes…" }, color = Ink.muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp).clickable { notesFolder = current },
            )
        }
        if (trashView) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Drawings here are removed after 30 days.", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (drawingsHere.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty", color = Ink.danger) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileSize.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(gridItems, span = { if (it is GalleryItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { item ->
                when (item) {
                    is GalleryItem.Header -> Text(item.title, color = Ink.muted, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    GalleryItem.NewTile -> NewTile(
                        onNew = { onNew(folderId) },
                        onFolder = { newFolder = true },
                        onPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onCamera = {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile)
                            runCatching { camera.launch(uri) }.onFailure { toast("No camera app is available") }
                        },
                        onFile = { importFile.launch(arrayOf("*/*")) },
                        onClipboard = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val u: Uri? = clip?.uri
                            if (u != null) scope.launch { newWithImage(withContext(Dispatchers.IO) { Assets.decode { context.contentResolver.openInputStream(u) } }) }
                            else toast("The clipboard has no image")
                        },
                    )
                    is GalleryItem.FolderTile -> FolderTile(
                        repo, item.folder, live.filter { it.folder == item.folder.id }, refresh,
                        onOpen = { folderId = item.folder.id },
                        onRename = { renamingFolder = item.folder },
                        onNotes = { notesFolder = item.folder },
                        onBackup = { exportIds = live.filter { it.folder == item.folder.id }.map { it.id }; backup.launch("${item.folder.name}.zip") },
                        onDelete = { repo.deleteFolder(item.folder.id); refresh++ },
                    )
                    is GalleryItem.DrawingTile -> {
                        val info = item.info
                        DrawingTile(
                            repo, info, refresh, trashView,
                            onOpen = { onOpen(info.id) },
                            onRename = { renaming = info },
                            onDuplicate = { repo.duplicate(info.id); refresh++ },
                            onMove = { moving = info },
                            onShare = { shareDrawing(info.id) },
                            onSaveFile = { exportIds = listOf(info.id); saveOne.launch("${info.name}.${NativeFile.EXT}") },
                            onDelete = { repo.trash(info.id); refresh++ },
                            onRestore = { repo.restore(info.id); refresh++ },
                            onDeleteForever = { repo.deleteForever(info.id); refresh++ },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { info -> TextDialog("Rename drawing", info.name, onDismiss = { renaming = null }) { repo.rename(info.id, it); refresh++ } }
    renamingFolder?.let { f -> TextDialog("Rename folder", f.name, onDismiss = { renamingFolder = null }) { repo.updateFolder(f.copy(name = it.ifBlank { f.name })); refresh++ } }
    notesFolder?.let { f -> MultilineTextDialog("Folder notes", f.notes, onDismiss = { notesFolder = null }) { repo.updateFolder(f.copy(notes = it)); refresh++ } }
    if (newFolder) TextDialog("New folder", "New folder", onDismiss = { newFolder = false }) { repo.createFolder(it, folderId); refresh++ }
    moving?.let { info ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move to folder") },
            text = {
                Column {
                    FolderChoice("Top level", info.folder == null) { repo.moveDrawing(info.id, null); moving = null; refresh++ }
                    folders.sortedBy { it.name.lowercase() }.forEach { f ->
                        FolderChoice(f.name, info.folder == f.id) { repo.moveDrawing(info.id, f.id); moving = null; refresh++ }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Delete all permanently?") },
            text = { Text("${drawingsHere.size} drawing(s) will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { drawingsHere.forEach { repo.deleteForever(it.id) }; confirmEmpty = false; refresh++ }) { Text("Delete", color = Ink.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderChoice(name: String, current: Boolean, onClick: () -> Unit) {
    Text(
        (if (current) "✓ " else "") + name, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTile(onNew: () -> Unit, onFolder: () -> Unit, onPhoto: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit, onClipboard: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, Ink.outline, RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = onNew, onLongClick = { menu = true }),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.PLUS, Ink.muted, size = 36.dp) }
            GlyphButton(Glyph.MORE, { menu = true }, size = 32.dp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), contentDescription = "Start something new")
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New drawing") }, onClick = { menu = false; onNew() })
                DropdownMenuItem(text = { Text("New folder") }, onClick = { menu = false; onFolder() })
                DropdownMenuItem(text = { Text("From a photo") }, onClick = { menu = false; onPhoto() })
                DropdownMenuItem(text = { Text("Take a picture") }, onClick = { menu = false; onCamera() })
                DropdownMenuItem(text = { Text("From a file (.inkfield, backup or image)") }, onClick = { menu = false; onFile() })
                DropdownMenuItem(text = { Text("From the clipboard") }, onClick = { menu = false; onClipboard() })
            }
        }
        Text("New drawing", color = Ink.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Text(" ", fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    repo: Repository, folder: Folder, contents: List<DrawingInfo>, refresh: Int,
    onOpen: () -> Unit, onRename: () -> Unit, onNotes: () -> Unit, onBackup: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val firstFour = contents.sortedByDescending { it.updated }.take(4)
    Column {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)).background(Ink.activeFill)
                    .border(1.dp, Ink.outline, RoundedCornerShape(14.dp))
                    .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                    .padding(6.dp),
            ) {
                // A 2×2 collage of the newest drawings inside.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 2) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 2) {
                                val info = firstFour.getOrNull(row * 2 + col)
                                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.White)) {
                                    if (info != null) {
                                        val thumb by produceState<Bitmap?>(null, info.id, info.updated, refresh) {
                                            value = withContext(Dispatchers.IO) { repo.loadThumb(info.id) }
                                        }
                                        thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Notes") }, onClick = { menu = false; onNotes() })
                DropdownMenuItem(text = { Text("Back up this folder…") }, onClick = { menu = false; onBackup() })
                DropdownMenuItem(text = { Text("Delete folder (keeps drawings)", color = Ink.danger) }, onClick = { menu = false; onDelete() })
            }
        }
        Text("▸ " + folder.name, color = Ink.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("${contents.size} drawing(s)", color = Ink.muted, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawingTile(
    repo: Repository,
    info: DrawingInfo,
    refresh: Int,
    inTrash: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onSaveFile: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(initialValue = null, info.id, info.updated, refresh) {
        value = withContext(Dispatchers.IO) { repo.loadThumb(info.id) }
    }
    Column {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, Ink.outline, RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { if (inTrash) menu = true else onOpen() },
                        onLongClick = { menu = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    Image(it.asImageBitmap(), contentDescription = info.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (inTrash) {
                    DropdownMenuItem(text = { Text("Restore") }, onClick = { menu = false; onRestore() })
                    DropdownMenuItem(text = { Text("Delete permanently", color = Ink.danger) }, onClick = { menu = false; onDeleteForever() })
                } else {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Move to folder…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Share as .inkfield") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Save .inkfield to device…") }, onClick = { menu = false; onSaveFile() })
                    DropdownMenuItem(text = { Text("Delete", color = Ink.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Text(info.name, color = Ink.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.updated)), color = Ink.muted, fontSize = 12.sp)
    }
}

@Suppress("unused")
private val keepSize = Modifier.size(0.dp)

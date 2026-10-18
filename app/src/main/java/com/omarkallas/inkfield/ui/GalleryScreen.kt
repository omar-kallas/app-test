package com.omarkallas.inkfield.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.data.Repository
import com.omarkallas.inkfield.model.DrawingInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(repo: Repository, onOpen: (String) -> Unit, onNew: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var trashView by remember { mutableStateOf(false) }
    val all by produceState(initialValue = emptyList<DrawingInfo>(), refresh) {
        value = withContext(Dispatchers.IO) { repo.list() }
    }
    val shown = all.filter { (it.deletedAt != null) == trashView }
    var renaming by remember { mutableStateOf<DrawingInfo?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ink.chrome).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (trashView) "Recently Deleted" else "Inkfield", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
            Spacer(Modifier.weight(1f))
            Chip("Drawings", !trashView) { trashView = false }
            Spacer(Modifier.width(6.dp))
            Chip("Recently Deleted", trashView) { trashView = true }
        }
        if (trashView) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Drawings here are removed after 30 days.", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (shown.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty", color = Ink.danger) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!trashView) {
                item(key = "new") { NewTile(onNew) }
            }
            items(shown, key = { it.id }) { info ->
                DrawingTile(
                    repo, info, refresh, trashView,
                    onOpen = { onOpen(info.id) },
                    onRename = { renaming = info },
                    onDuplicate = { repo.duplicate(info.id); refresh++ },
                    onDelete = { repo.trash(info.id); refresh++ },
                    onRestore = { repo.restore(info.id); refresh++ },
                    onDeleteForever = { repo.deleteForever(info.id); refresh++ },
                )
            }
        }
        if (shown.isEmpty() && trashView) {
            Text("Nothing here.", color = Ink.muted, modifier = Modifier.padding(24.dp))
        }
    }

    renaming?.let { info ->
        TextDialog("Rename drawing", info.name, onDismiss = { renaming = null }) { repo.rename(info.id, it); refresh++ }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Delete all permanently?") },
            text = { Text("${shown.size} drawing(s) will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { shown.forEach { repo.deleteForever(it.id) }; confirmEmpty = false; refresh++ }) {
                    Text("Delete", color = Ink.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTile(onNew: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, Ink.outline, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.PLUS, Ink.muted, size = 36.dp) }
        Text("New drawing", color = Ink.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Text(" ", fontSize = 12.sp)
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
                    .background(androidx.compose.ui.graphics.Color.White)
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
                    DropdownMenuItem(text = { Text("Delete", color = Ink.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Text(info.name, color = Ink.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.updated)), color = Ink.muted, fontSize = 12.sp)
    }
}

@Suppress("unused")
private val unusedSpacer = Modifier.height(0.dp).size(0.dp)

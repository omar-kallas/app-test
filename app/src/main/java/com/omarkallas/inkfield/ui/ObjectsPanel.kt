package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.engine.Geometry
import com.omarkallas.inkfield.engine.LibObject
import com.omarkallas.inkfield.engine.ObjPack
import com.omarkallas.inkfield.engine.ObjectLibrary
import com.omarkallas.inkfield.engine.Renderer
import com.omarkallas.inkfield.engine.StrokeCache
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Stroke

private sealed interface ObjRow {
    data class Header(val pack: ObjPack) : ObjRow
    data class Item(val pack: ObjPack, val obj: LibObject) : ObjRow
}

/** The Object Library: tap or drag-free placement at the view center. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ObjectsPanel(
    ctl: EditorController,
    onPlace: (ObjPack, LibObject) -> Unit,
    onNewPack: () -> Unit,
    onAddSelection: (ObjPack) -> Unit,
    onExportPack: (ObjPack) -> Unit,
    onImportPack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var columns by remember { mutableIntStateOf(3) }
    var refresh by remember { mutableIntStateOf(0) }
    refresh.let { } // read so changes to packs recompose
    val packs = ObjectLibrary.allPacks
    val q = query.trim().lowercase()
    val rows = buildList {
        val favs = packs.flatMap { p -> p.objects.filter { "${p.id}/${it.id}" in ObjectLibrary.favorites }.map { p to it } }
        if (favs.isNotEmpty() && q.isEmpty()) {
            add(ObjRow.Header(ObjPack("fav", "Favorites")))
            if ("fav" !in collapsed) favs.forEach { (p, o) -> add(ObjRow.Item(p, o)) }
        }
        for (p in packs) {
            val objs = if (q.isEmpty()) p.objects else p.objects.filter { it.name.lowercase().contains(q) || it.tags.contains(q) || p.name.lowercase().contains(q) }
            if (q.isNotEmpty() && objs.isEmpty()) continue
            add(ObjRow.Header(p))
            if (p.id !in collapsed) objs.forEach { add(ObjRow.Item(p, it)) }
        }
    }
    Panel(modifier.width((columns * 76 + 24).dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Objects", fontWeight = FontWeight.SemiBold, color = Ink.text)
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.PLUS, { columns = (columns % 5) + 2 }, size = 32.dp, contentDescription = "Change columns")
                GlyphButton(Glyph.CLOSE, onClose, size = 32.dp)
            }
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Ink.text),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().background(Ink.activeFill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (query.isEmpty()) Text("Search objects", color = Ink.muted, fontSize = 14.sp)
                        inner()
                    }
                },
                modifier = Modifier.padding(vertical = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rows, span = { if (it is ObjRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { row ->
                    when (row) {
                        is ObjRow.Header -> PackHeader(
                            row.pack, row.pack.id in collapsed,
                            onToggle = { collapsed = if (row.pack.id in collapsed) collapsed - row.pack.id else collapsed + row.pack.id },
                            onAddSelection = if (!row.pack.bundled && row.pack.id != "fav" && ctl.selection.isNotEmpty()) ({ onAddSelection(row.pack); refresh++; Unit }) else null,
                            onExport = if (row.pack.id != "fav") ({ onExportPack(row.pack) }) else null,
                            onDelete = if (!row.pack.bundled && row.pack.id != "fav") ({ ObjectLibrary.deletePack(row.pack.id); refresh++; Unit }) else null,
                        )
                        is ObjRow.Item -> ObjectTile(ctl, row.pack, row.obj, onPlace = { onPlace(row.pack, row.obj) }, onChanged = { refresh++ })
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("New pack", false) { onNewPack(); refresh++; Unit }
                Chip("Import pack", false) { onImportPack() }
            }
        }
    }
}

@Composable
private fun PackHeader(
    pack: ObjPack, collapsed: Boolean, onToggle: () -> Unit,
    onAddSelection: (() -> Unit)?, onExport: (() -> Unit)?, onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (collapsed) "▸ " else "▾ ", color = Ink.muted)
            Column(Modifier.weight(1f)) {
                Text(pack.name, color = Ink.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (pack.description.isNotEmpty()) Text(pack.description, color = Ink.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onAddSelection != null || onExport != null || onDelete != null) GlyphButton(Glyph.MORE, { menu = true }, size = 28.dp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            onAddSelection?.let { f -> DropdownMenuItem(text = { Text("Add selection to this pack") }, onClick = { menu = false; f() }) }
            onExport?.let { f -> DropdownMenuItem(text = { Text("Export pack") }, onClick = { menu = false; f() }) }
            onDelete?.let { f -> DropdownMenuItem(text = { Text("Delete pack", color = Ink.danger) }, onClick = { menu = false; f() }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObjectTile(ctl: EditorController, pack: ObjPack, obj: LibObject, onPlace: () -> Unit, onChanged: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val key = "${pack.id}/${obj.id}"
    val fav = key in ObjectLibrary.favorites
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Ink.outline, RoundedCornerShape(10.dp))
                .background(Color.White)
                .combinedClickable(onClick = onPlace, onLongClick = { menu = true })
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val preview = remember(obj.id, pack.id) {
                val style = Stroke(0, BrushType.FIXED, 1f, 0xFF2A2A2E.toInt(), 1f, 0f, 0, FloatArray(0))
                ObjectLibrary.place(pack, obj, 0f, 0f, 100f, style, 100.0)
            }
            Canvas(Modifier.size(56.dp)) {
                drawIntoCanvas { cv ->
                    val c = cv.nativeCanvas
                    val entries = preview.map { s -> StrokeCache.Entry(s, Geometry.process(s), Geometry.buildPath(s, Geometry.process(s))) }
                    val b = android.graphics.RectF()
                    var any = false
                    for (e in entries) { if (!any) { b.set(e.processed.bounds); any = true } else b.union(e.processed.bounds) }
                    if (!any) return@drawIntoCanvas
                    val k = minOf(size.width / b.width().coerceAtLeast(1e-3f), size.height / b.height().coerceAtLeast(1e-3f)) * 0.86f
                    c.save()
                    c.translate(size.width / 2f, size.height / 2f)
                    c.scale(k, k)
                    c.translate(-b.centerX(), -b.centerY())
                    for (e in entries) Renderer.drawEntry(c, e, k)
                    c.restore()
                }
            }
            Text(obj.name, fontSize = 9.sp, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fav) Text("★", color = Color(0xFFE8A33D), fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Place") }, onClick = { menu = false; onPlace() })
            DropdownMenuItem(text = { Text(if (fav) "Remove from favorites" else "Add to favorites") }, onClick = {
                menu = false; ObjectLibrary.toggleFavorite(key); onChanged()
            })
            if (!pack.bundled) {
                DropdownMenuItem(text = { Text("Delete object", color = Ink.danger) }, onClick = {
                    menu = false
                    ObjectLibrary.updatePack(pack.copy(objects = pack.objects.filter { it.id != obj.id })); onChanged()
                })
            }
        }
    }
}

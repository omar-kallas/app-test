package com.omarkallas.inkfield.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.omarkallas.inkfield.editor.DrawingView
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.engine.Assets
import com.omarkallas.inkfield.engine.BrushLib
import com.omarkallas.inkfield.engine.ExportFormat
import com.omarkallas.inkfield.engine.ExportOptions
import com.omarkallas.inkfield.engine.Exporter
import com.omarkallas.inkfield.engine.ObjPack
import com.omarkallas.inkfield.engine.ObjectLibrary
import com.omarkallas.inkfield.prefs.NamedPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** All "bring something in / send something out" actions of the editor, with their system pickers. */
class Importers(
    val pickPhoto: () -> Unit,
    val takePhoto: () -> Unit,
    val openFile: () -> Unit,
    val paste: () -> Unit,
    val copySelection: () -> Unit,
    val exportBrushes: () -> Unit,
    val importBrushes: () -> Unit,
    val exportPack: (ObjPack) -> Unit,
    val importPack: () -> Unit,
    val exportPalettes: () -> Unit,
    val importPalettes: () -> Unit,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
fun rememberImporters(ctl: EditorController, viewRef: MutableState<DrawingView?>): Importers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun center(): FloatArray = viewRef.value?.viewCenterCanvas() ?: floatArrayOf(0f, 0f, 100f)

    fun placeBitmap(bmp: Bitmap) {
        val asset = Assets.save(bmp) ?: run { toast(context, "Couldn't save the image"); return }
        val c = center()
        // Fit within about six "100 dp" units of the current view.
        ctl.addImage(asset, bmp.width, bmp.height, c[0], c[1], c[2] * 6f, c[2] * 4.5f)
    }

    fun loadUri(uri: Uri) {
        scope.launch {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) {
                importPdf(context, scope, ctl, uri, center())
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) { Assets.decode { context.contentResolver.openInputStream(uri) } }
            if (bmp == null) toast(context, "That file isn't an image Inkfield can read") else placeBitmap(bmp)
        }
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { loadUri(it) } }
    val cameraFile = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch {
            val bmp = withContext(Dispatchers.IO) { Assets.decode { cameraFile.inputStream() } }
            bmp?.let { placeBitmap(it) }
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadUri(it) } }

    // Brush packs
    val brushOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { writeTo(context, scope, it, BrushLib.exportPack(BrushLib.user)) }
    }
    val brushIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
                val n = bytes?.let { BrushLib.importPack(it) } ?: 0
                ctl.refreshBrushes()
                toast(context, if (n > 0) "Added $n brush${if (n == 1) "" else "es"}" else "No brushes found in that file")
            }
        }
    }
    // Object packs
    val pendingPack = remember { androidx.compose.runtime.mutableStateOf<ObjPack?>(null) }
    val packOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingPack.value
        if (uri != null && p != null) writeTo(context, scope, uri, ObjectLibrary.exportPack(p))
    }
    val packIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
                val p = bytes?.let { ObjectLibrary.importPack(it) }
                ctl.refreshBrushes()
                toast(context, if (p != null) "Imported \\"${p.name}\\"" else "That file isn't an object pack")
            }
        }
    }
    // Palettes
    val palOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeTo(context, scope, it, json.encodeToString(ListSerializer(NamedPalette.serializer()), ctl.palettes).toByteArray()) }
    }
    val palIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            scope.launch {
                val list = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } ?: ""
                        json.decodeFromString(ListSerializer(NamedPalette.serializer()), text)
                    }.getOrNull()
                }
                if (list.isNullOrEmpty()) toast(context, "That file isn't a palette file") else { ctl.importPalettes(list); toast(context, "Added ${list.size} palette(s)") }
            }
        }
    }

    return remember {
        Importers(
            pickPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            takePhoto = {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile)
                runCatching { camera.launch(uri) }.onFailure { toast(context, "No camera app is available") }
            },
            openFile = { file.launch(arrayOf("image/*", "application/pdf")) },
            paste = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                when {
                    item == null -> toast(context, "The clipboard is empty")
                    item.uri != null -> loadUri(item.uri)
                    !item.text.isNullOrBlank() -> { val c = center(); ctl.addText(item.text.toString(), c[0], c[1]) }
                    else -> toast(context, "Nothing Inkfield can paste")
                }
            },
            copySelection = {
                scope.launch {
                    val opts = ExportOptions(ExportFormat.PNG, 2f, includeBackground = false, only = ctl.selection)
                    val bytes = Exporter.encode(ctl.drawing, ctl.cache, opts)
                    val f = withContext(Dispatchers.IO) {
                        File(File(context.cacheDir, "exports").apply { mkdirs() }, "selection.png").also { it.writeBytes(bytes) }
                    }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Inkfield selection", uri))
                    toast(context, "Copied as an image")
                }
            },
            exportBrushes = { brushOut.launch("Inkfield brushes.inkbrush") },
            importBrushes = { brushIn.launch(arrayOf("*/*")) },
            exportPack = { p -> pendingPack.value = p; packOut.launch("${p.name}.inkobjects") },
            importPack = { packIn.launch(arrayOf("*/*")) },
            exportPalettes = { palOut.launch("Inkfield palettes.json") },
            importPalettes = { palIn.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
    }
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

private fun writeTo(context: Context, scope: CoroutineScope, uri: Uri, bytes: ByteArray) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        }
        toast(context, if (ok) "Saved" else "Couldn't save the file")
    }
}

/** Renders each PDF page to an image item, stacked top to bottom at true size (PDF points = canvas units). */
private fun importPdf(context: Context, scope: CoroutineScope, ctl: EditorController, uri: Uri, center: FloatArray) {
    scope.launch {
        val pages = withContext(Dispatchers.IO) {
            runCatching {
                val out = ArrayList<Triple<String, Float, Float>>()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        for (i in 0 until minOf(r.pageCount, 50)) {
                            r.openPage(i).use { page ->
                                val k = (2400f / maxOf(page.width, page.height)).coerceIn(1f, 4f)
                                val bmp = Bitmap.createBitmap((page.width * k).toInt(), (page.height * k).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                Assets.save(bmp)?.let { out.add(Triple(it, page.width.toFloat(), page.height.toFloat())) }
                            }
                        }
                    }
                }
                out
            }.getOrNull()
        }
        if (pages.isNullOrEmpty()) { toast(context, "Couldn't open that PDF"); return@launch }
        var y = center[1] - pages[0].third / 2f
        for ((asset, w, h) in pages) {
            ctl.addImageRect(asset, center[0] - w / 2f, y, w, h)
            y += h + 24f
        }
        toast(context, "Imported ${pages.size} page(s)")
    }
}

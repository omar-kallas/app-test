package com.omarkallas.inkfield

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import com.omarkallas.inkfield.data.Repository
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.prefs.Prefs
import com.omarkallas.inkfield.ui.EditorScreen
import com.omarkallas.inkfield.ui.GalleryScreen
import com.omarkallas.inkfield.ui.InkTheme

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs
    private var editor: EditorController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = Repository(applicationContext)
        prefs = Prefs(applicationContext)
        com.omarkallas.inkfield.engine.Assets.init(applicationContext)
        com.omarkallas.inkfield.engine.BrushLib.init(applicationContext)
        com.omarkallas.inkfield.engine.ObjectLibrary.init(applicationContext)
        // Remove image files that no drawing (or object pack) uses any more.
        Thread {
            runCatching {
                val referenced = repo.referencedAssets() + com.omarkallas.inkfield.engine.ObjectLibrary.userPacks
                    .flatMap { p -> p.objects.flatMap { o -> o.strokes.mapNotNull { it.asset } } }
                com.omarkallas.inkfield.engine.Assets.collect(referenced.toSet())
            }
        }.start()
        setContent { InkTheme { App() } }
    }

    @Composable
    private fun App() {
        var openId by rememberSaveable { mutableStateOf<String?>(null) }
        val id = openId
        if (id == null) {
            GalleryScreen(
                repo,
                onOpen = { openId = it },
                onNew = { folder ->
                    val now = System.currentTimeMillis()
                    val count = repo.list().count { it.deletedAt == null } + 1
                    val d = Drawing.blank(repo.newId(), "Drawing $count", now).copy(background = prefs.defaultPaper)
                    repo.save(d, null)
                    repo.moveDrawing(d.id, folder)
                    openId = d.id
                },
            )
        } else {
            key(id) {
                val ctl = remember {
                    repo.load(id)?.let { EditorController(it, prefs) }
                }
                if (ctl == null) {
                    LaunchedEffect(Unit) {
                        Toast.makeText(this@MainActivity, "Couldn't open that drawing", Toast.LENGTH_SHORT).show()
                        openId = null
                    }
                } else {
                    DisposableEffect(ctl) {
                        editor = ctl
                        onDispose { if (editor === ctl) editor = null }
                    }
                    EditorScreen(ctl, repo, onExit = { openId = null })
                }
            }
        }
    }

    /** Keyboard shortcuts for ChromeOS, DeX and tablets with keyboards (editable in Settings). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ctl = editor
        if (ctl == null || !prefs.shortcutsEnabled) return super.dispatchKeyEvent(event)
        val map = prefs.shortcuts
        // Holding the pan key pans with any tool; it ends when the key is released.
        map["PAN"]?.let { spec ->
            val code = KeyEvent.keyCodeFromString("KEYCODE_" + spec.split("+").last())
            if (event.keyCode == code && !event.isCtrlPressed) {
                ctl.tempPan = event.action == KeyEvent.ACTION_DOWN
                return true
            }
        }
        // Let focused text fields have their keys first.
        if (super.dispatchKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val action = map.entries.firstOrNull { com.omarkallas.inkfield.prefs.KeySpec.matches(it.value, event) }?.key ?: return false
        when (action) {
            "UNDO" -> ctl.undo()
            "REDO" -> ctl.redo()
            "DUPLICATE" -> ctl.duplicateSelection()
            "DELETE" -> if (ctl.selection.isNotEmpty()) ctl.deleteSelection() else return false
            "DESELECT" -> if (ctl.selection.isNotEmpty()) ctl.clearSelection() else return false
            "SELECT_ALL" -> ctl.perform(com.omarkallas.inkfield.prefs.GestureAction.SELECT_ALL)
            "TOGGLE_UI" -> ctl.perform(com.omarkallas.inkfield.prefs.GestureAction.TOGGLE_UI)
            "SHOW_LAYERS" -> ctl.perform(com.omarkallas.inkfield.prefs.GestureAction.SHOW_LAYERS)
            "ERASER" -> ctl.perform(com.omarkallas.inkfield.prefs.GestureAction.ERASER)
            "ZOOM_IN" -> ctl.zoomRequest = 1.25f
            "ZOOM_OUT" -> ctl.zoomRequest = 0.8f
            "RESET_VIEW" -> ctl.resetViewRequest++
            else -> return false
        }
        return true
    }
}

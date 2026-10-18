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
                onNew = {
                    val now = System.currentTimeMillis()
                    val count = repo.list().count { it.deletedAt == null } + 1
                    val d = Drawing.blank(repo.newId(), "Drawing $count", now).copy(background = prefs.defaultPaper)
                    repo.save(d, null)
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

    /** Keyboard shortcuts for ChromeOS, DeX and tablets with keyboards. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ctl = editor
        if (ctl != null && event.action == KeyEvent.ACTION_DOWN) {
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            when {
                ctrl && event.keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> { ctl.redo(); return true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_Z -> { ctl.undo(); return true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_Y -> { ctl.redo(); return true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_D -> { ctl.duplicateSelection(); return true }
                (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && ctl.selection.isNotEmpty() -> {
                    ctl.deleteSelection(); return true
                }
                event.keyCode == KeyEvent.KEYCODE_ESCAPE && ctl.selection.isNotEmpty() -> { ctl.clearSelection(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

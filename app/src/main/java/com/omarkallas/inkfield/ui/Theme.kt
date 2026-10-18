package com.omarkallas.inkfield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Neutral, low-contrast chrome so the drawing's own colors dominate. */
object Ink {
    val chrome = Color(0xFFF4F2EE)
    val panel = Color(0xF7FBFAF8)
    val outline = Color(0xFFD9D6D0)
    val text = Color(0xFF2A2A2E)
    val muted = Color(0xFF7B7A78)
    val accent = Color(0xFF2E86C1)
    val activeFill = Color(0xFFE6E3DD)
    val danger = Color(0xFFC0392B)
    val panelShape = RoundedCornerShape(18.dp)
    val panelBorder = BorderStroke(1.dp, outline)
}

@Composable
fun InkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.accent,
            onPrimary = Color.White,
            surface = Ink.panel,
            onSurface = Ink.text,
            background = Ink.chrome,
            onBackground = Ink.text,
            surfaceVariant = Ink.activeFill,
            outline = Ink.outline,
        ),
        content = content,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = Ink.panelShape,
        color = Ink.panel,
        border = Ink.panelBorder,
        shadowElevation = 3.dp,
        content = content,
    )
}

/** Round icon button using a [Glyph]. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ink.text,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Ink.activeFill else Color.Transparent)
            .then(if (active) Modifier.border(1.dp, Ink.outline, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint = if (enabled) tint else tint.copy(alpha = 0.3f), size = size * 0.55f)
    }
}

package com.castpro.core

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

object Cast {
    const val SERVICE_TYPE = "_castpro._tcp."
    const val CONTROL_PORT = 48555
    const val MEDIA_PORT = 48556
    const val SUB_ID = "castpro-ext"
}

/** The CastPro palette. Nothing outside these six colors is used in the UI. */
object CP {
    val Night = Color(0xFF161B2F)
    val Deep = Color(0xFF242F49)
    val Slate = Color(0xFF384358)
    val Peach = Color(0xFFFFA586)
    val Crimson = Color(0xFFB51A2B)
    val Wine = Color(0xFF541A2E)
    val Backdrop = Brush.verticalGradient(0f to Wine, 0.5f to Night, 1f to Night)
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun PlayPauseIcon(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (playing) {
            drawRoundRect(color, Offset(w * .2f, h * .15f), Size(w * .22f, h * .7f), CornerRadius(w * .06f))
            drawRoundRect(color, Offset(w * .58f, h * .15f), Size(w * .22f, h * .7f), CornerRadius(w * .06f))
        } else {
            val p = Path().apply {
                moveTo(w * .28f, h * .12f); lineTo(w * .28f, h * .88f); lineTo(w * .86f, h * .5f); close()
            }
            drawPath(p, color)
        }
    }
}

package com.example.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

data class Label(val rect: Rect, val text: String)

class OverlayView(context: Context) : View(context) {

    private var labels: List<Label> = emptyList()
    private val bg = Paint().apply { color = Color.argb(215, 15, 15, 15) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setLabels(newLabels: List<Label>) {
        labels = newLabels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (l in labels) {
            val r = l.rect
            var size = (r.height() * 0.78f).coerceIn(14f, 64f)
            fg.textSize = size
            var w = fg.measureText(l.text)
            val maxW = (width - r.left).toFloat()
            if (w > maxW && maxW > 0f) {
                size = (size * maxW / w).coerceAtLeast(12f)
                fg.textSize = size
                w = fg.measureText(l.text)
            }
            val right = r.left + maxOf(r.width().toFloat(), w + 10f)
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), right, r.bottom.toFloat(), bg)
            val baseline = r.exactCenterY() - (fg.ascent() + fg.descent()) / 2f
            canvas.drawText(l.text, r.left + 5f, baseline, fg)
        }
    }
}

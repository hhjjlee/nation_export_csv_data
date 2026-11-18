package com.kosub.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/** 검은 테두리가 있는 자막 글자 (어떤 화면 위에서도 잘 보이게). */
class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {
    private var drawingOutline = false
    var outlineWidthPx: Float = resources.displayMetrics.density * 3.2f
    var fillColor: Int = Color.WHITE
        set(value) {
            field = value; invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val p = paint
        // 1) 테두리
        drawingOutline = true
        val oldStyle = p.style
        p.style = Paint.Style.STROKE
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = outlineWidthPx
        setTextColor(Color.argb(235, 0, 0, 0))
        super.onDraw(canvas)
        // 2) 글자
        p.style = oldStyle
        p.strokeWidth = 0f
        setTextColor(fillColor)
        super.onDraw(canvas)
        drawingOutline = false
    }

    override fun invalidate() {
        // onDraw 안에서 글자색을 바꿀 때 생기는 다시 그리기 요청은 무시한다
        if (!drawingOutline) super.invalidate()
    }
}

/** 자막이 만들어진 구간(초록)과 지금 처리 중인 구간(노랑), 재생 위치를 보여 주는 얇은 막대. */
class CoverageBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bg = Paint().apply { color = Color.argb(70, 255, 255, 255) }
    private val covered = Paint().apply { color = Color.argb(200, 76, 175, 80) }
    private val active = Paint().apply { color = Color.argb(230, 255, 193, 7) }
    private val position = Paint().apply { color = Color.WHITE }
    private val rect = RectF()
    private var duration = 0.0
    private var intervals: List<Pair<Double, Double>> = emptyList()
    private var activeRegion: Pair<Double, Double>? = null
    private var pos = 0.0

    fun update(durationSec: Double?, coverage: List<Pair<Double, Double>>, activeRange: Pair<Double, Double>?) {
        duration = durationSec ?: 0.0
        intervals = coverage
        activeRegion = activeRange
        invalidate()
    }

    fun setPosition(seconds: Double) {
        pos = seconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bg)
        if (duration <= 0) return
        fun x(t: Double) = (t / duration * w).toFloat().coerceIn(0f, w)
        for ((a, b) in intervals) {
            rect.set(x(a), 0f, maxOf(x(a) + 1f, x(b)), h)
            canvas.drawRect(rect, covered)
        }
        activeRegion?.let { (a, b) ->
            rect.set(x(a), 0f, maxOf(x(a) + 3f, x(b)), h)
            canvas.drawRect(rect, active)
        }
        val px = x(pos)
        rect.set(px - 2f, 0f, px + 2f, h)
        canvas.drawRect(rect, position)
    }
}

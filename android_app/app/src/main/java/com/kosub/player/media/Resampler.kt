package com.kosub.player.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 윈도우 sinc 보간으로 샘플레이트를 바꾼다 (예: 48 kHz → 16 kHz, 접힘 잡음 방지 저역 통과 포함). */
object Resampler {
    private const val HALF_WIDTH = 8 // 출력 샘플 기준 반폭

    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate <= 0) return FloatArray(0)
        if (srcRate == dstRate) return input.copyOf()
        val ratio = srcRate.toDouble() / dstRate
        val outLen = floor(input.size / ratio).toInt()
        val out = FloatArray(outLen)
        // 내려 샘플링할 때는 차단 주파수를 출력 나이퀴스트에 맞춰 낮춘다
        val cutoff = if (ratio > 1.0) 0.95 / ratio else 0.95
        val half = if (ratio > 1.0) (HALF_WIDTH * ratio).toInt() else HALF_WIDTH
        for (k in 0 until outLen) {
            val x = k * ratio
            val center = floor(x).toInt()
            var acc = 0.0
            var norm = 0.0
            val lo = max(0, center - half + 1)
            val hi = min(input.size - 1, center + half)
            for (i in lo..hi) {
                val d = x - i
                val arg = PI * cutoff * d
                val sinc = if (kotlin.math.abs(d) < 1e-9) 1.0 else sin(arg) / arg
                val w = 0.5 + 0.5 * cos(PI * d / (half + 1)) // Hann 창
                val weight = sinc * w
                acc += input[i] * weight
                norm += weight
            }
            out[k] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** 여러 채널이 섞인(interleaved) 샘플을 모노로. */
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            val base = f * channels
            for (c in 0 until channels) s += interleaved[base + c]
            out[f] = s / channels
        }
        return out
    }
}

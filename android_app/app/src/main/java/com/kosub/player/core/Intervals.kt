package com.kosub.player.core

/** 처리가 끝난 시간 구간(초)을 정렬·병합된 상태로 관리한다. */
class IntervalSet(private val mergeGap: Double = 0.25) {
    private val items = ArrayList<DoubleArray>()

    @Synchronized
    fun add(start: Double, end: Double) {
        if (end <= start) return
        var a = start
        var b = end
        val result = ArrayList<DoubleArray>(items.size + 1)
        var i = 0
        while (i < items.size && items[i][1] + mergeGap < a) {
            result.add(items[i]); i++
        }
        while (i < items.size && items[i][0] - mergeGap <= b) {
            a = minOf(a, items[i][0]); b = maxOf(b, items[i][1]); i++
        }
        result.add(doubleArrayOf(a, b))
        while (i < items.size) {
            result.add(items[i]); i++
        }
        items.clear()
        items.addAll(result)
    }

    @Synchronized
    fun contains(t: Double): Boolean = items.any { it[0] <= t && t < it[1] }

    /** [lo, hi] 안에서 아직 처리되지 않은 구간. */
    @Synchronized
    fun gaps(lo: Double, hi: Double, minLen: Double = 0.0): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var cur = lo
        for (iv in items) {
            val (s, e) = iv[0] to iv[1]
            if (e <= cur) continue
            if (s >= hi) break
            if (s > cur) out.add(cur to minOf(s, hi))
            cur = maxOf(cur, e)
            if (cur >= hi) break
        }
        if (cur < hi) out.add(cur to hi)
        return out.filter { it.second - it.first >= minLen }
    }

    @Synchronized
    fun total(): Double = items.sumOf { it[1] - it[0] }

    @Synchronized
    fun toList(): List<Pair<Double, Double>> = items.map { it[0] to it[1] }

    @Synchronized
    fun maxEnd(): Double = items.maxOfOrNull { it[1] } ?: 0.0

    val size: Int @Synchronized get() = items.size
}

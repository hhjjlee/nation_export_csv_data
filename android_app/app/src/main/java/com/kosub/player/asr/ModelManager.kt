package com.kosub.player.asr

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** whisper.cpp용 음성 인식 모델(ggml) 목록과 내려받기. */
object ModelManager {
    data class Model(val id: String, val file: String, val sizeMb: Int, val label: String)

    val MODELS = listOf(
        Model("tiny", "ggml-tiny-q5_1.bin", 32, "tiny — 가장 빠름, 정확도 낮음"),
        Model("base", "ggml-base-q5_1.bin", 60, "base — 빠름"),
        Model("small", "ggml-small-q5_1.bin", 190, "small — 권장 (속도/정확도 균형)"),
        Model("medium", "ggml-medium-q5_0.bin", 539, "medium — 정확, 느림"),
        Model("turbo", "ggml-large-v3-turbo-q5_0.bin", 574, "large-v3-turbo — 가장 정확, 느림"),
    )
    const val DEFAULT = "small"
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun find(id: String): Model = MODELS.firstOrNull { it.id == id } ?: MODELS.first { it.id == DEFAULT }

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, model: Model): File = File(dir(context), model.file)

    fun isDownloaded(context: Context, model: Model): Boolean {
        val f = file(context, model)
        return f.isFile && f.length() > model.sizeMb * 1_000_000L * 0.9
    }

    /** 모델을 내려받는다 (이어받기 지원). progress(받은 바이트, 전체 바이트). 취소되면 IOException. */
    fun download(context: Context, model: Model, isCancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val target = file(context, model)
        if (isDownloaded(context, model)) return target
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                fetch(model, part, isCancelled, progress)
                break
            } catch (e: IOException) {
                if (isCancelled() || ++attempt >= 4) throw e
                Thread.sleep(2000L * attempt)
            }
        }
        if (!part.renameTo(target)) throw IOException("모델 파일을 저장하지 못했습니다")
        return target
    }

    private fun fetch(model: Model, part: File, isCancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = URL(BASE_URL + model.file).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("모델 다운로드 실패 (HTTP $code)")
            val append = code == 206
            val total = (if (append) existing else 0L) + conn.contentLengthLong.coerceAtLeast(0L)
            var done = if (append) existing else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("취소됨")
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 300) {
                            progress(done, if (total > 0) total else model.sizeMb * 1_000_000L)
                            lastReport = now
                        }
                    }
                }
            }
            progress(done, done)
        } finally {
            conn.disconnect()
        }
    }

    fun delete(context: Context, model: Model) {
        file(context, model).delete()
        File(file(context, model).path + ".part").delete()
    }
}

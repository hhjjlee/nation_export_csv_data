package com.kosub.player.core

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val HAS_LETTER = Regex("\\p{L}")

/** 글자가 없는 줄(♪, 숫자 등)은 번역하지 않고 그대로 둔다. 나머지만 번역기에 넘긴다. */
internal fun translateLettersOnly(
    texts: List<String>,
    block: (List<String>) -> List<String?>,
): List<String?> {
    val todo = texts.indices.filter { HAS_LETTER.containsMatchIn(texts[it]) }
    val out = texts.map { t -> if (HAS_LETTER.containsMatchIn(t)) null else t }.toMutableList()
    if (todo.isNotEmpty()) {
        val results = block(todo.map { texts[it].trim().split(Regex("\\s+")).joinToString(" ") })
        todo.forEachIndexed { k, i -> out[i] = results.getOrNull(k) }
    }
    return out
}

/** 구글 번역 공개 웹 엔드포인트 (무료, API 키 불필요). */
class GoogleTranslator(
    private val http: (String) -> String = ::httpGet,
) : Translator {
    override val name = "google"
    override val label = "구글 번역"
    override val batchSize = 40
    private var lastCall = 0L

    override fun translate(texts: List<String>, srcLang: String, context: List<Pair<String, String>>): List<String?> =
        translateLettersOnly(texts) { lines -> translateAll(lines, Languages.googleCode(srcLang)) }

    private fun translateAll(lines: List<String>, sl: String): List<String?> {
        val out = ArrayList<String?>()
        var batch = ArrayList<String>()
        var size = 0
        for (t in lines) {
            val cost = URLEncoder.encode(t, "UTF-8").length + 3
            if (batch.isNotEmpty() && (size + cost > MAX_QUERY || batch.size >= batchSize)) {
                out.addAll(translateLines(batch, sl)); batch = ArrayList(); size = 0
            }
            batch.add(t); size += cost
        }
        if (batch.isNotEmpty()) out.addAll(translateLines(batch, sl))
        return out
    }

    private fun translateLines(lines: List<String>, sl: String): List<String?> {
        if (lines.size == 1) return listOf(request(lines[0], sl).trim().ifEmpty { null })
        val parts = request(lines.joinToString("\n"), sl).split("\n").map { it.trim() }.dropLastWhile { it.isEmpty() }
        if (parts.size == lines.size) return parts.map { it.ifEmpty { null } }
        // 줄 수가 맞지 않으면 한 줄씩 번역한다
        return lines.map { request(it, sl).trim().ifEmpty { null } }
    }

    private fun request(text: String, sl: String): String {
        val url = "$URL?client=gtx&sl=$sl&tl=ko&dt=t&ie=UTF-8&oe=UTF-8&q=" + URLEncoder.encode(text, "UTF-8")
        var delay = 1000L
        var lastError: Exception? = null
        for (attempt in 0 until 5) {
            val wait = 250 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
            try {
                val body = http(url)
                val arr = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val piece = arr.optJSONArray(i) ?: continue
                    if (!piece.isNull(0)) sb.append(piece.getString(0))
                }
                return sb.toString()
            } catch (e: HttpStatusException) {
                lastError = e
                if (e.code in listOf(429, 500, 502, 503, 504) && attempt < 4) {
                    Thread.sleep(delay); delay *= 2; continue
                }
                throw TranslatorException("구글 번역 응답 오류 (HTTP ${e.code})", cause = e)
            } catch (e: IOException) {
                lastError = e
                if (attempt < 2) {
                    Thread.sleep(delay); delay *= 2; continue
                }
                throw TranslatorException("구글 번역 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요.", cause = e)
            } catch (e: org.json.JSONException) {
                throw TranslatorException("구글 번역 응답을 해석하지 못했습니다", cause = e)
            }
        }
        throw TranslatorException("구글 번역 실패", cause = lastError)
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    companion object {
        private const val URL = "https://translate.googleapis.com/translate_a/single"
        private const val MAX_QUERY = 5000

        fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) KoSubPlayer")
            try {
                val code = conn.responseCode
                if (code != 200) throw HttpStatusException(code)
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

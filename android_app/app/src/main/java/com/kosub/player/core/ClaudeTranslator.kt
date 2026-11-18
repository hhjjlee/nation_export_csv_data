package com.kosub.player.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/** Claude(Anthropic API)로 문맥을 고려해 자연스러운 한국어 자막으로 번역한다. */
class ClaudeTranslator(
    apiKey: String,
    private val model: String = "claude-opus-5-5",
    effort: String = "low",
) : Translator {
    override val name = "claude"
    override val label = "Claude"
    override val batchSize = 30

    private val effortValue = when (effort) {
        "medium" -> BetaOutputConfig.Effort.MEDIUM
        "high" -> BetaOutputConfig.Effort.HIGH
        else -> BetaOutputConfig.Effort.LOW
    }

    private val client: AnthropicClient = try {
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .maxRetries(3)
            .timeout(Duration.ofSeconds(180))
            .build()
    } catch (e: Exception) {
        throw TranslatorException("Claude 클라이언트를 만들 수 없습니다. API 키를 확인하세요.", fatal = true, cause = e)
    }

    override fun translate(texts: List<String>, srcLang: String, context: List<Pair<String, String>>): List<String?> =
        translateLettersOnly(texts) { lines ->
            val out = ArrayList<String?>()
            var ctx = context
            for (chunk in lines.chunked(batchSize)) {
                val res = call(chunk, srcLang, ctx)
                out.addAll(res)
                ctx = (ctx + chunk.zip(res).mapNotNull { (s, k) -> k?.let { s to it } }).takeLast(6)
            }
            out
        }

    private fun call(lines: List<String>, srcLang: String, context: List<Pair<String, String>>): List<String?> {
        val payload = JSONObject()
            .put("source_language", if (srcLang.isNotEmpty()) "${Languages.name(srcLang)} ($srcLang)" else "auto-detect")
            .put("earlier_context", JSONArray().apply {
                context.takeLast(6).forEach { (s, k) -> put(JSONObject().put("source", s).put("korean", k)) }
            })
            .put("lines", JSONArray().apply {
                lines.forEachIndexed { i, t -> put(JSONObject().put("id", i).put("text", t)) }
            })
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM)
            .addBeta("server-side-fallback-2026-07-01")
            // 정책 분류기가 거절하면 서버가 기본 대체 모델로 다시 시도한다
            .fallbacks(BetaFallbacksParam.ofDefault())
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(effortValue)
                    .format(BetaJsonOutputFormat.builder().schema(SCHEMA).build())
                    .build()
            )
            .addUserMessage(payload.toString())
            .build()
        val response = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw TranslatorException("Claude API 키가 올바르지 않습니다. 설정에서 키를 확인하세요.", fatal = true, cause = e)
        } catch (e: PermissionDeniedException) {
            throw TranslatorException("이 API 키로는 선택한 Claude 모델을 쓸 수 없습니다.", fatal = true, cause = e)
        } catch (e: NotFoundException) {
            throw TranslatorException("Claude 모델을 찾을 수 없습니다: $model", fatal = true, cause = e)
        } catch (e: BadRequestException) {
            if (lines.size > 1) {
                val half = lines.size / 2
                return call(lines.subList(0, half), srcLang, context) + call(lines.subList(half, lines.size), srcLang, context)
            }
            throw TranslatorException("Claude 요청 오류: ${e.message}", cause = e)
        } catch (e: RateLimitException) {
            throw TranslatorException("Claude 사용량 한도에 걸렸습니다. 잠시 후 다시 시도합니다.", cause = e)
        } catch (e: AnthropicServiceException) {
            throw TranslatorException("Claude 서버 오류 (${e.statusCode()})", cause = e)
        } catch (e: AnthropicIoException) {
            throw TranslatorException("Claude 서버에 연결하지 못했습니다. 인터넷 연결을 확인하세요.", cause = e)
        }
        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.MAX_TOKENS && lines.size > 1) {
            val half = lines.size / 2
            return call(lines.subList(0, half), srcLang, context) + call(lines.subList(half, lines.size), srcLang, context)
        }
        if (stop == BetaStopReason.REFUSAL) return List(lines.size) { null }
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return parseTranslations(text, lines.size)
    }

    companion object {
        private val SYSTEM = """
            You are a professional subtitle translator. Translate each subtitle line of a video into natural, fluent Korean for on-screen subtitles.

            Rules:
            - Return exactly one Korean translation for every input line, keyed by the same id. Never merge, split, skip or reorder lines.
            - The lines are consecutive subtitles from one video. Use the surrounding lines and the earlier context to resolve meaning, names, gender, tone and politeness, but each output must translate only its own line.
            - Write natural spoken Korean that matches the speaker's tone and relationship (반말/존댓말). Keep it concise and easy to read quickly.
            - Keep names and terminology consistent. Use the commonly used Korean spelling for proper nouns.
            - Sound or music cues such as [Music] or ♪ should become their Korean equivalents (e.g. [음악], ♪).
            - If a line is already Korean, return it unchanged.
        """.trimIndent()

        private val SCHEMA: BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "translations" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "id" to mapOf("type" to "integer"),
                                    "ko" to mapOf("type" to "string"),
                                ),
                                "required" to listOf("id", "ko"),
                                "additionalProperties" to false,
                            ),
                        ),
                    )
                ),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("translations")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

        /** {"translations":[{"id":0,"ko":"..."}]} → 줄 순서대로 (없는 줄은 null) */
        fun parseTranslations(text: String, count: Int): List<String?> {
            val result = MutableList<String?>(count) { null }
            val items = try {
                JSONObject(text).getJSONArray("translations")
            } catch (e: Exception) {
                throw TranslatorException("Claude 응답을 해석하지 못했습니다", cause = e)
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val idx = item.optInt("id", -1)
                if (idx in 0 until count) {
                    val ko = item.optString("ko", "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    result[idx] = ko.ifEmpty { null }
                }
            }
            return result
        }
    }
}

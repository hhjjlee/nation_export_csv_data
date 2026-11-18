package com.kosub.player

import android.app.Application
import android.content.Context
import com.kosub.player.asr.ModelManager
import com.kosub.player.asr.WhisperAsr
import com.kosub.player.core.Asr

class App : Application()

/** 불러온 음성 인식 모델을 동영상이 바뀌어도 재사용한다. */
object AsrHolder {
    private var current: WhisperAsr? = null
    private var currentId: String? = null

    fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    @Synchronized
    fun get(
        context: Context,
        modelId: String,
        isCancelled: () -> Boolean,
        onStatus: (String) -> Unit,
        onDownload: (Long, Long) -> Unit,
    ): Asr {
        current?.let { if (currentId == modelId) return it }
        current?.close()
        current = null
        val model = ModelManager.find(modelId)
        if (!ModelManager.isDownloaded(context, model)) {
            onStatus("음성 인식 모델 내려받는 중 (${model.id}, 약 ${model.sizeMb}MB) — 처음 한 번만")
            try {
                ModelManager.download(context, model, isCancelled, onDownload)
            } catch (e: Exception) {
                throw IllegalStateException("음성 인식 모델을 내려받지 못했습니다. 인터넷 연결을 확인하세요. (${e.message})", e)
            }
        }
        onStatus("음성 인식 모델 불러오는 중 (${model.id})")
        val asr = WhisperAsr(ModelManager.file(context, model).path, model.id, threads())
        current = asr
        currentId = modelId
        return asr
    }
}

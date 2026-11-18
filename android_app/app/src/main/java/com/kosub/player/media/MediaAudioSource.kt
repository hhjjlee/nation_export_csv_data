package com.kosub.player.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.kosub.player.core.AudioSource
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 동영상/음성 파일에서 필요한 구간만 디코딩해 16 kHz 모노로 돌려준다 (MediaExtractor + MediaCodec).
 * 구간마다 탐색해서 읽으므로 긴 영화의 중간으로 바로 이동해도 그 위치부터 자막을 만들 수 있다.
 */
class MediaAudioSource(private val context: Context, private val uri: Uri) : AudioSource {
    private val trackIndex: Int
    private val mime: String
    override val durationSec: Double?

    init {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var idx = -1
            var m = ""
            var dur: Double? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val tm = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (tm.startsWith("audio/")) {
                    idx = i; m = tm
                    if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION) / 1e6
                    break
                }
            }
            if (idx < 0) throw IOException("이 파일에는 오디오 트랙이 없어 자막을 만들 수 없습니다.")
            trackIndex = idx
            mime = m
            durationSec = dur
        } finally {
            ex.release()
        }
    }

    override fun read(start: Double, end: Double): FloatArray {
        val s = max(0.0, start)
        if (end <= s) return FloatArray(0)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            ex.selectTrack(trackIndex)
            val format = ex.getTrackFormat(trackIndex)
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("이 기기에서 이 오디오 형식($mime)을 디코딩할 수 없습니다.", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()
            ex.seekTo(max(0L, ((s - 1.0) * 1e6).toLong()), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val mono = FloatList()
            var firstPtsUs = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val stopAfterUs = ((end + 0.5) * 1e6).toLong()

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t > stopAfterUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val ordered = buf.slice().order(ByteOrder.nativeOrder())
                            val samples: FloatArray = if (pcmFloat) {
                                val fb = ordered.asFloatBuffer()
                                FloatArray(fb.remaining()).also { fb.get(it) }
                            } else {
                                val sb = ordered.asShortBuffer()
                                val tmp = ShortArray(sb.remaining()).also { sb.get(it) }
                                FloatArray(tmp.size) { tmp[it] / 32768f }
                            }
                            mono.addAll(Resampler.downmix(samples, channels))
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        val decodedEnd = if (firstPtsUs >= 0) firstPtsUs / 1e6 + mono.size / srcRate.toDouble() else 0.0
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || decodedEnd > end + 0.3) break
                    }
                }
            }
            if (firstPtsUs < 0) return FloatArray(0)
            val pcm16k = Resampler.resample(mono.toArray(), srcRate, SR)
            // 디코딩한 첫 샘플 시각을 기준으로 [start, end)만 잘라낸다
            val firstSec = firstPtsUs / 1e6
            val wanted = ((end - s) * SR).roundToInt()
            val offset = ((s - firstSec) * SR).roundToInt()
            val out = FloatArray(max(0, min(wanted, pcm16k.size - offset)))
            for (i in out.indices) {
                val j = i + offset
                out[i] = if (j in pcm16k.indices) pcm16k[j] else 0f
            }
            return out
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            ex.release()
        }
    }

    companion object {
        const val SR = 16000
    }
}

/** 크기가 늘어나는 float 배열 (박싱 없이). */
class FloatList(initial: Int = 1 shl 16) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun addAll(values: FloatArray) {
        if (size + values.size > data.size) data = data.copyOf(max(data.size * 2, size + values.size))
        System.arraycopy(values, 0, data, size, values.size)
        size += values.size
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

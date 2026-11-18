package com.kosub.player.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.kosub.player.core.JobState
import com.kosub.player.core.StateStore
import com.kosub.player.core.SubtitleSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** 동영상 파일 정보 (이름, 지문). */
object MediaInfo {
    private const val SAMPLE = 1 shl 20

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    /** 크기 + 앞/뒤 1MiB로 만든 지문: 파일을 옮기거나 이름을 바꿔도 같은 동영상으로 인식한다. */
    fun fingerprint(context: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            md.update(size.toString().toByteArray())
            FileInputStream(pfd.fileDescriptor).use { input ->
                val ch = input.channel
                val buf = java.nio.ByteBuffer.allocate(SAMPLE)
                ch.position(0)
                ch.read(buf)
                md.update(buf.array(), 0, buf.position())
                if (size > 2L * SAMPLE) {
                    buf.clear()
                    ch.position(size - SAMPLE)
                    ch.read(buf)
                    md.update(buf.array(), 0, buf.position())
                }
            }
        } ?: throw IOException("파일을 열 수 없습니다")
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }
}

/** 앱 내부 저장소의 작업 기록 (jobs/지문.json). */
class FileStateStore(private val file: File) : StateStore {
    override fun load(): JobState? = try {
        if (file.isFile) JobState.fromJson(file.readText(Charsets.UTF_8)) else null
    } catch (_: Exception) {
        null // 손상된 기록은 무시하고 새로 만든다
    }

    override fun save(state: JobState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(state.toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(state.toJson(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        fun forFingerprint(context: Context, fingerprint: String) =
            FileStateStore(File(File(context.filesDir, "jobs"), "$fingerprint.json"))
    }
}

/** SRT 파일 내용: UTF-8(BOM) + CRLF — 오래된 PC 플레이어도 한글을 제대로 읽는다. */
private fun srtBytes(content: String): ByteArray =
    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

/** 사용자가 고른 폴더(SAF)에 저장. 동영상이 있는 폴더를 고르면 다른 플레이어도 자막을 자동으로 찾는다. */
class TreeSink(private val context: Context, private val treeUri: Uri) : SubtitleSink {
    override fun write(fileName: String, content: String): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("저장 폴더에 접근할 수 없습니다")
        val file = tree.findFile(fileName) ?: tree.createFile("application/x-subrip", fileName)
            ?: throw IOException("파일을 만들 수 없습니다: $fileName")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(srtBytes(content)) }
            ?: throw IOException("파일에 쓸 수 없습니다: $fileName")
        val folder = tree.name ?: "선택한 폴더"
        return "$folder/${file.name ?: fileName}"
    }
}

/** 기본값: 내장 메모리의 Download/KoSubPlayer 폴더 (MediaStore, 권한 불필요). */
class DownloadsSink(private val context: Context) : SubtitleSink {
    private val relative = Environment.DIRECTORY_DOWNLOADS + "/KoSubPlayer/"

    override fun write(fileName: String, content: String): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, relative),
            null,
        )?.use { c ->
            if (c.moveToFirst()) uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        val target = uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }) ?: throw IOException("다운로드 폴더에 파일을 만들 수 없습니다")
        resolver.openOutputStream(target, "wt")?.use { it.write(srtBytes(content)) }
            ?: throw IOException("파일에 쓸 수 없습니다: $fileName")
        return "Download/KoSubPlayer/$fileName"
    }
}

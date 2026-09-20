package com.overlord.audiorip.data

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object FfmpegExtractorEngine {

    suspend fun transcodeAudio(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        format: OutputAudioFormat,
        startMs: Long = 0L,
        endMs: Long = 0L,
        totalDurationMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        // Resolve SAF URI parameter for FFmpegKit
        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)

        val cmdParts = mutableListOf<String>()
        cmdParts.add("-y")

        if (startMs > 0L) {
            cmdParts.add("-ss")
            cmdParts.add(String.format(java.util.Locale.US, "%.3f", startMs / 1000.0))
        }

        if (endMs > 0L) {
            cmdParts.add("-to")
            cmdParts.add(String.format(java.util.Locale.US, "%.3f", endMs / 1000.0))
        }

        cmdParts.add("-i")
        cmdParts.add(inputPath)
        cmdParts.add("-vn") // Disable video recording

        when (format) {
            OutputAudioFormat.MP3_192 -> {
                cmdParts.add("-c:a")
                cmdParts.add("libmp3lame")
                cmdParts.add("-b:a")
                cmdParts.add("192k")
            }
            OutputAudioFormat.MP3_320 -> {
                cmdParts.add("-c:a")
                cmdParts.add("libmp3lame")
                cmdParts.add("-b:a")
                cmdParts.add("320k")
            }
            OutputAudioFormat.WAV -> {
                cmdParts.add("-c:a")
                cmdParts.add("pcm_s16le")
            }
            OutputAudioFormat.FLAC -> {
                cmdParts.add("-c:a")
                cmdParts.add("flac")
            }
            OutputAudioFormat.M4A_NATIVE -> {
                cmdParts.add("-c:a")
                cmdParts.add("aac")
                cmdParts.add("-b:a")
                cmdParts.add("192k")
            }
        }

        cmdParts.add(outputFile.absolutePath)
        val command = cmdParts.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

        val targetDurationMs = if (endMs > 0L && startMs >= 0L) {
            (endMs - startMs).coerceAtLeast(1000L)
        } else if (totalDurationMs > 0L) {
            totalDurationMs
        } else {
            1000L
        }

        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeAsync(
                command,
                { completedSession ->
                    val returnCode = completedSession.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        onProgress(1.0f)
                        continuation.resume(Result.success(outputFile))
                    } else if (ReturnCode.isCancel(returnCode)) {
                        continuation.resume(Result.failure(Exception("使用者已取消轉碼操作")))
                    } else {
                        val failLog = completedSession.failStackTrace ?: completedSession.allLogsAsString
                        continuation.resume(Result.failure(Exception("FFmpeg 轉碼失敗: $failLog")))
                    }
                },
                { /* log callback if needed */ },
                { statistics ->
                    if (statistics != null) {
                        val timeMs = statistics.time
                        if (timeMs > 0) {
                            val progress = (timeMs.toFloat() / targetDurationMs).coerceIn(0f, 0.99f)
                            onProgress(progress)
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }
    }
}

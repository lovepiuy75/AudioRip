package com.overlord.audiorip.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object NativeExtractorEngine {

    suspend fun extractAudio(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long = 0L,
        endMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            val numTracks = extractor.trackCount
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("該影片不包含任何可識別的音訊軌道"))
            }

            val videoDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            extractor.selectTrack(audioTrackIndex)

            // Setup MediaMuxer output to M4A (MPEG_4 container)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024 // 1MB fallback buffer
            }
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val startUs = startMs * 1000L
            val endUs = if (endMs > 0L) endMs * 1000L else if (videoDurationUs > 0L) videoDurationUs else Long.MAX_VALUE
            val totalSpanUs = (endUs - startUs).coerceAtLeast(1L)

            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            var firstSampleTimeUs = -1L

            while (coroutineContext.isActive) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break // End of stream
                }

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) {
                    break // Reached end of user-selected trimming range
                }

                if (sampleTimeUs >= startUs) {
                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = sampleTimeUs
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    // Normalize presentation timestamp relative to start
                    bufferInfo.presentationTimeUs = sampleTimeUs - firstSampleTimeUs
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                    val progress = ((sampleTimeUs - startUs).toFloat() / totalSpanUs).coerceIn(0f, 1f)
                    onProgress(progress)
                }

                if (!extractor.advance()) {
                    break
                }
            }

            onProgress(1.0f)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {}

            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }
}

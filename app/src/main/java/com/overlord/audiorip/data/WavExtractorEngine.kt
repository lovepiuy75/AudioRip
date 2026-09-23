package com.overlord.audiorip.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object WavExtractorEngine {

    suspend fun extractWav(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long = 0L,
        endMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var fos: FileOutputStream? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                return@withContext Result.failure(IllegalStateException("影片中未找到有效音軌"))
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            extractor.selectTrack(audioTrackIndex)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            fos = FileOutputStream(outputFile)
            // Placeholder 44-byte WAV header, rewritten after decoding completes
            writeWavHeader(fos, 0, 0, sampleRate, channelCount, 16)

            val startUs = startMs * 1000L
            val endUs = if (endMs > 0L) endMs * 1000L else if (durationUs > 0L) durationUs else Long.MAX_VALUE
            val totalSpanUs = (endUs - startUs).coerceAtLeast(1L)

            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var totalPcmBytes = 0L

            while (!sawOutputEOS && coroutineContext.isActive) {
                if (!sawInputEOS) {
                    val inputBufIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: ByteBuffer.allocate(0)
                        inputBuf.clear()
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            if (sampleTime > endUs) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufIndex >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputBufIndex)
                    if (outputBuf != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuf.get(pcmBytes)
                        fos.write(pcmBytes)
                        totalPcmBytes += bufferInfo.size

                        val progress = ((bufferInfo.presentationTimeUs - startUs).toFloat() / totalSpanUs).coerceIn(0f, 0.99f)
                        onProgress(progress)
                    }

                    decoder.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            fos.flush()
            fos.close()
            fos = null

            // Rewrite proper WAV header with actual PCM byte counts
            updateWavHeader(outputFile, totalPcmBytes, sampleRate, channelCount, 16)
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Int,
        channels: Int,
        byteRate: Int
    ) {
        val header = ByteArray(44)
        val bitsPerSample = 16
        val sampleRate = longSampleRate
        val byteRateCalc = (sampleRate * channels * bitsPerSample / 8).toLong()

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte()
        header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRateCalc and 0xffL).toByte()
        header[29] = (byteRateCalc shr 8 and 0xffL).toByte()
        header[30] = (byteRateCalc shr 16 and 0xffL).toByte()
        header[31] = (byteRateCalc shr 24 and 0xffL).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
        header[43] = (totalAudioLen shr 24 and 0xffL).toByte()
        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(byteArrayOf(
                (totalDataLen and 0xffL).toByte(),
                (totalDataLen shr 8 and 0xffL).toByte(),
                (totalDataLen shr 16 and 0xffL).toByte(),
                (totalDataLen shr 24 and 0xffL).toByte()
            ))
            raf.seek(24)
            raf.write(byteArrayOf(
                (sampleRate and 0xff).toByte(),
                (sampleRate shr 8 and 0xff).toByte(),
                (sampleRate shr 16 and 0xff).toByte(),
                (sampleRate shr 24 and 0xff).toByte()
            ))
            raf.seek(28)
            raf.write(byteArrayOf(
                (byteRate and 0xffL).toByte(),
                (byteRate shr 8 and 0xffL).toByte(),
                (byteRate shr 16 and 0xffL).toByte(),
                (byteRate shr 24 and 0xffL).toByte()
            ))
            raf.seek(40)
            raf.write(byteArrayOf(
                (totalAudioLen and 0xffL).toByte(),
                (totalAudioLen shr 8 and 0xffL).toByte(),
                (totalAudioLen shr 16 and 0xffL).toByte(),
                (totalAudioLen shr 24 and 0xffL).toByte()
            ))
        }
    }
}

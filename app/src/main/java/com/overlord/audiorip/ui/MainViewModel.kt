package com.overlord.audiorip.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.overlord.audiorip.data.MediaStoreHelper
import com.overlord.audiorip.data.NativeExtractorEngine
import com.overlord.audiorip.data.OutputAudioFormat
import com.overlord.audiorip.data.TransformerExtractorEngine
import com.overlord.audiorip.data.VideoMetadata
import com.overlord.audiorip.data.WavExtractorEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface ExtractionState {
    data object Idle : ExtractionState
    data class Extracting(val progress: Float, val statusText: String) : ExtractionState
    data class Success(val file: File, val mediaUri: Uri) : ExtractionState
    data class Error(val message: String) : ExtractionState
}

data class UiState(
    val selectedVideo: VideoMetadata? = null,
    val selectedFormat: OutputAudioFormat = OutputAudioFormat.M4A_NATIVE,
    val isTrimmingEnabled: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val customFileName: String = "",
    val extractionState: ExtractionState = ExtractionState.Idle,
    val isAudioPlaying: Boolean = false,
    val audioCurrentPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null
    private var exoPlayer: ExoPlayer? = null
    private var playbackProgressJob: Job? = null

    init {
        initPlayer()
    }

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(getApplication()).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.update { it.copy(isAudioPlaying = isPlaying) }
                    if (isPlaying) {
                        startTrackingPlayback()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _uiState.update { it.copy(isAudioPlaying = false, audioCurrentPositionMs = 0L) }
                    }
                }
            })
        }
    }

    private fun startTrackingPlayback() {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (isActive && exoPlayer?.isPlaying == true) {
                val current = exoPlayer?.currentPosition ?: 0L
                val total = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                _uiState.update {
                    it.copy(audioCurrentPositionMs = current, audioDurationMs = total)
                }
                delay(200)
            }
        }
    }

    fun loadVideo(uri: Uri) {
        viewModelScope.launch {
            stopAudio()
            _uiState.update { it.copy(extractionState = ExtractionState.Idle) }

            val context = getApplication<Application>()
            var fileName = "video"
            var fileSize = 0L

            // Query file name and size from content resolver
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "video"
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            var durationMs = 0L
            var audioMime: String? = null
            var width = 0
            var height = 0

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                audioMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val defaultName = fileName.substringBeforeLast(".")
            val metadata = VideoMetadata(
                uri = uri,
                fileName = fileName,
                durationMs = durationMs,
                fileSizeBytes = fileSize,
                audioMime = audioMime,
                width = width,
                height = height
            )

            _uiState.update {
                it.copy(
                    selectedVideo = metadata,
                    customFileName = defaultName,
                    trimStartMs = 0L,
                    trimEndMs = durationMs
                )
            }
        }
    }

    fun setFormat(format: OutputAudioFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun setTrimmingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isTrimmingEnabled = enabled) }
    }

    fun setTrimRange(startMs: Long, endMs: Long) {
        _uiState.update { it.copy(trimStartMs = startMs, trimEndMs = endMs) }
    }

    fun setCustomFileName(name: String) {
        _uiState.update { it.copy(customFileName = name) }
    }

    fun startExtraction() {
        val state = _uiState.value
        val video = state.selectedVideo ?: return
        val format = state.selectedFormat

        val context = getApplication<Application>()
        val outputDir = File(context.cacheDir, "extracted_audio").apply { if (!exists()) mkdirs() }
        val outputName = "${state.customFileName.ifBlank { "extracted_audio" }}.${format.extension}"
        val tempOutputFile = File(outputDir, outputName)

        val startMs = if (state.isTrimmingEnabled) state.trimStartMs else 0L
        val endMs = if (state.isTrimmingEnabled) state.trimEndMs else 0L

        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(extractionState = ExtractionState.Extracting(0f, "準備提取音訊..."))
            }

            val result: Result<File> = when (format) {
                OutputAudioFormat.M4A_NATIVE -> {
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(0f, "使用原生極速分離中..."))
                    }
                    NativeExtractorEngine.extractAudio(
                        context = context,
                        inputUri = video.uri,
                        outputFile = tempOutputFile,
                        startMs = startMs,
                        endMs = endMs,
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(extractionState = ExtractionState.Extracting(p, "原生極速分離中 ${(p * 100).toInt()}%"))
                            }
                        }
                    )
                }
                OutputAudioFormat.WAV_PCM -> {
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(0f, "無壓縮 PCM 提取中..."))
                    }
                    WavExtractorEngine.extractWav(
                        context = context,
                        inputUri = video.uri,
                        outputFile = tempOutputFile,
                        startMs = startMs,
                        endMs = endMs,
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(extractionState = ExtractionState.Extracting(p, "PCM 提取中 ${(p * 100).toInt()}%"))
                            }
                        }
                    )
                }
                OutputAudioFormat.AAC_TRANSCODE, OutputAudioFormat.MP3_COMPAT -> {
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(0f, "Media3 轉碼中 (${format.displayName})..."))
                    }
                    TransformerExtractorEngine.transcodeAudio(
                        context = context,
                        inputUri = video.uri,
                        outputFile = tempOutputFile,
                        format = format,
                        startMs = startMs,
                        endMs = endMs,
                        onProgress = { p ->
                            _uiState.update {
                                it.copy(extractionState = ExtractionState.Extracting(p, "轉碼進行中 ${(p * 100).toInt()}%"))
                            }
                        }
                    )
                }
            }

            result.fold(
                onSuccess = { extractedFile ->
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(0.99f, "正在儲存至系統音樂目錄..."))
                    }

                    val saveResult = MediaStoreHelper.saveAudioToMusicFolder(
                        context = context,
                        sourceFile = extractedFile,
                        displayName = outputName,
                        mimeType = format.mimeType
                    )

                    saveResult.fold(
                        onSuccess = { mediaUri ->
                            _uiState.update {
                                it.copy(extractionState = ExtractionState.Success(extractedFile, mediaUri))
                            }
                            loadAudioIntoPlayer(extractedFile)
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(extractionState = ExtractionState.Error("儲存至音樂目錄失敗: ${error.message}"))
                            }
                        }
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Error("提取失敗: ${error.message}"))
                    }
                }
            )
        }
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        _uiState.update { it.copy(extractionState = ExtractionState.Idle) }
    }

    private fun loadAudioIntoPlayer(file: File) {
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
    }

    fun toggleAudioPlayback() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekAudio(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun stopAudio() {
        exoPlayer?.stop()
        _uiState.update { it.copy(isAudioPlaying = false, audioCurrentPositionMs = 0L) }
    }

    override fun onCleared() {
        super.onCleared()
        playbackProgressJob?.cancel()
        extractionJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val activityName: String = "",
    val activityTime: String = "",
    val extractionState: ExtractionState = ExtractionState.Idle,
    // Preview playback for trimming
    val isPreviewPlaying: Boolean = false,
    val previewCurrentPositionMs: Long = 0L,
    // Result audio playback
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
                    val isResultState = _uiState.value.extractionState is ExtractionState.Success
                    if (isResultState) {
                        _uiState.update { it.copy(isAudioPlaying = isPlaying) }
                    } else {
                        _uiState.update { it.copy(isPreviewPlaying = isPlaying) }
                    }
                    if (isPlaying) {
                        startTrackingPlayback(isResultState)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val isResultState = _uiState.value.extractionState is ExtractionState.Success
                        if (isResultState) {
                            _uiState.update { it.copy(isAudioPlaying = false, audioCurrentPositionMs = 0L) }
                        } else {
                            _uiState.update { it.copy(isPreviewPlaying = false, previewCurrentPositionMs = 0L) }
                        }
                    }
                }
            })
        }
    }

    private fun startTrackingPlayback(isResultState: Boolean) {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (isActive && exoPlayer?.isPlaying == true) {
                val current = exoPlayer?.currentPosition ?: 0L
                val total = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L

                if (isResultState) {
                    _uiState.update {
                        it.copy(audioCurrentPositionMs = current, audioDurationMs = total)
                    }
                } else {
                    val state = _uiState.value
                    // If trimming is enabled and current position exceeds trimEndMs, pause and loop back to startMs
                    if (state.isTrimmingEnabled && state.trimEndMs > state.trimStartMs && current >= state.trimEndMs) {
                        exoPlayer?.pause()
                        exoPlayer?.seekTo(state.trimStartMs)
                        _uiState.update {
                            it.copy(isPreviewPlaying = false, previewCurrentPositionMs = state.trimStartMs)
                        }
                        break
                    } else {
                        _uiState.update {
                            it.copy(previewCurrentPositionMs = current)
                        }
                    }
                }
                delay(100)
            }
        }
    }

    fun loadMedia(uri: Uri) {
        viewModelScope.launch {
            stopAudio()
            _uiState.update { it.copy(extractionState = ExtractionState.Idle) }

            val context = getApplication<Application>()
            var fileName = "media"
            var fileSize = 0L

            // Query file name and size from content resolver
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "media"
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {
                // In case of file:// uri fallback
                val path = uri.path
                if (path != null) {
                    val f = File(path)
                    if (f.exists()) {
                        fileName = f.name
                        fileSize = f.length()
                    }
                }
            }

            var durationMs = 0L
            var audioMime: String? = null
            var width = 0
            var height = 0
            var isAudioOnly = false

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                audioMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideo == null && (audioMime?.startsWith("audio/") == true || width == 0)) {
                    isAudioOnly = true
                }
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
                height = height,
                isAudioOnly = isAudioOnly
            )

            val currentTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

            _uiState.update {
                it.copy(
                    selectedVideo = metadata,
                    customFileName = defaultName,
                    activityName = defaultName,
                    activityTime = currentTimeStr,
                    trimStartMs = 0L,
                    trimEndMs = durationMs,
                    previewCurrentPositionMs = 0L,
                    isPreviewPlaying = false
                )
            }

            // Prepare player for trimming preview
            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            } catch (_: Exception) {}
        }
    }

    // Trimming preview controls
    fun togglePreviewPlayback() {
        val player = exoPlayer ?: return
        val state = _uiState.value
        if (player.isPlaying) {
            player.pause()
        } else {
            // If trimming is enabled and current position is out of trim range, seek to start
            if (state.isTrimmingEnabled) {
                val current = player.currentPosition
                if (current < state.trimStartMs || (state.trimEndMs > state.trimStartMs && current >= state.trimEndMs)) {
                    player.seekTo(state.trimStartMs)
                }
            }
            player.play()
        }
    }

    fun seekPreview(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _uiState.update { it.copy(previewCurrentPositionMs = positionMs) }
    }

    fun setStartToCurrentPosition() {
        val current = _uiState.value.previewCurrentPositionMs
        val safeEnd = _uiState.value.trimEndMs.coerceAtLeast(current)
        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                trimStartMs = current,
                trimEndMs = safeEnd
            )
        }
    }

    fun setEndToCurrentPosition() {
        val current = _uiState.value.previewCurrentPositionMs
        val safeStart = _uiState.value.trimStartMs.coerceAtMost(current)
        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                trimStartMs = safeStart,
                trimEndMs = current
            )
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

    fun setActivityName(name: String) {
        _uiState.update { it.copy(activityName = name) }
    }

    fun setActivityTime(time: String) {
        _uiState.update { it.copy(activityTime = time) }
    }

    fun getGeminiPrompt(): String {
        val state = _uiState.value
        val time = state.activityTime.ifBlank {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        }
        val name = state.activityName.ifBlank { state.customFileName.ifBlank { "會議活動" } }
        return "請幫我將這段音訊轉成繁體中文逐字稿，並條列出重點摘要與發言重點：\n【活動時間】：$time\n【活動名稱】：$name"
    }

    // Re-edit previously extracted or current audio file
    fun reEditExtractedAudio() {
        val success = _uiState.value.extractionState as? ExtractionState.Success ?: return
        val fileUri = Uri.fromFile(success.file)
        loadMedia(fileUri)
    }

    // Delete extracted audio file
    fun deleteExtractedAudio(onFinished: (Boolean) -> Unit = {}) {
        val success = _uiState.value.extractionState as? ExtractionState.Success ?: return
        viewModelScope.launch {
            stopAudio()
            val deleted = MediaStoreHelper.deleteAudio(
                context = getApplication(),
                mediaUri = success.mediaUri,
                localFile = success.file
            )
            _uiState.update {
                it.copy(
                    extractionState = ExtractionState.Idle,
                    isAudioPlaying = false,
                    audioCurrentPositionMs = 0L
                )
            }
            // Also re-load current input media into player
            val currentMedia = _uiState.value.selectedVideo
            if (currentMedia != null) {
                try {
                    val item = MediaItem.fromUri(currentMedia.uri)
                    exoPlayer?.setMediaItem(item)
                    exoPlayer?.prepare()
                } catch (_: Exception) {}
            }
            onFinished(deleted)
        }
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

        // Stop preview before extraction
        stopAudio()

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
        try {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
        } catch (_: Exception) {}
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
        _uiState.update {
            it.copy(
                isAudioPlaying = false,
                audioCurrentPositionMs = 0L,
                isPreviewPlaying = false,
                previewCurrentPositionMs = 0L
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackProgressJob?.cancel()
        extractionJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}

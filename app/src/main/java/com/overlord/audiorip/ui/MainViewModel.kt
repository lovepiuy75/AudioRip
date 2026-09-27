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
import com.overlord.audiorip.data.MultiCutExportMode
import com.overlord.audiorip.data.NativeExtractorEngine
import com.overlord.audiorip.data.OutputAudioFormat
import com.overlord.audiorip.data.TransformerExtractorEngine
import com.overlord.audiorip.data.TrimSegment
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
    data class Success(val file: File, val mediaUri: Uri, val extraFiles: List<File> = emptyList()) : ExtractionState
    data class Error(val message: String) : ExtractionState
}

data class UiState(
    val selectedVideo: VideoMetadata? = null,
    val selectedFormat: OutputAudioFormat = OutputAudioFormat.M4A_NATIVE,
    val isTrimmingEnabled: Boolean = false,
    val segments: List<TrimSegment> = emptyList(),
    val activeSegmentIndex: Int = 0,
    val exportMode: MultiCutExportMode = MultiCutExportMode.MERGE_CONCAT,
    val customFileName: String = "",
    val activityName: String = "",
    val activityTime: String = "",
    val extractionState: ExtractionState = ExtractionState.Idle,
    // Timeline Playhead & Preview
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
                    val activeSeg = state.segments.getOrNull(state.activeSegmentIndex)
                    if (state.isTrimmingEnabled && activeSeg != null && activeSeg.endMs > activeSeg.startMs && current >= activeSeg.endMs) {
                        exoPlayer?.pause()
                        exoPlayer?.seekTo(activeSeg.startMs)
                        _uiState.update {
                            it.copy(isPreviewPlaying = false, previewCurrentPositionMs = activeSeg.startMs)
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

            val initialSegment = TrimSegment(startMs = 0L, endMs = durationMs)

            _uiState.update {
                it.copy(
                    selectedVideo = metadata,
                    customFileName = defaultName,
                    activityName = defaultName,
                    activityTime = currentTimeStr,
                    segments = listOf(initialSegment),
                    activeSegmentIndex = 0,
                    exportMode = MultiCutExportMode.MERGE_CONCAT,
                    previewCurrentPositionMs = 0L,
                    isPreviewPlaying = false
                )
            }

            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            } catch (_: Exception) {}
        }
    }

    // Timeline and Trimming Controls
    fun setTrimmingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isTrimmingEnabled = enabled) }
    }

    fun setExportMode(mode: MultiCutExportMode) {
        _uiState.update { it.copy(exportMode = mode) }
    }

    fun seekPreview(positionMs: Long) {
        val safePos = positionMs.coerceIn(0L, _uiState.value.selectedVideo?.durationMs ?: 0L)
        exoPlayer?.seekTo(safePos)
        _uiState.update { it.copy(previewCurrentPositionMs = safePos) }
    }

    fun togglePreviewPlayback() {
        val player = exoPlayer ?: return
        val state = _uiState.value
        if (player.isPlaying) {
            player.pause()
        } else {
            val activeSeg = state.segments.getOrNull(state.activeSegmentIndex)
            if (state.isTrimmingEnabled && activeSeg != null) {
                val current = player.currentPosition
                if (current < activeSeg.startMs || (activeSeg.endMs > activeSeg.startMs && current >= activeSeg.endMs)) {
                    player.seekTo(activeSeg.startMs)
                }
            }
            player.play()
        }
    }

    fun setStartToCurrentPosition() {
        val currentMs = _uiState.value.previewCurrentPositionMs
        val curList = _uiState.value.segments.toMutableList()
        val index = _uiState.value.activeSegmentIndex.coerceIn(0, (curList.size - 1).coerceAtLeast(0))

        if (curList.isEmpty()) {
            val total = _uiState.value.selectedVideo?.durationMs ?: 0L
            curList.add(TrimSegment(startMs = currentMs, endMs = total))
        } else {
            val old = curList[index]
            val safeEnd = if (old.endMs <= currentMs) (_uiState.value.selectedVideo?.durationMs ?: currentMs) else old.endMs
            curList[index] = old.copy(startMs = currentMs, endMs = safeEnd)
        }

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = index
            )
        }
    }

    fun setEndToCurrentPosition() {
        val currentMs = _uiState.value.previewCurrentPositionMs
        val curList = _uiState.value.segments.toMutableList()
        val index = _uiState.value.activeSegmentIndex.coerceIn(0, (curList.size - 1).coerceAtLeast(0))

        if (curList.isEmpty()) {
            curList.add(TrimSegment(startMs = 0L, endMs = currentMs))
        } else {
            val old = curList[index]
            val safeStart = if (old.startMs >= currentMs) 0L else old.startMs
            curList[index] = old.copy(startMs = safeStart, endMs = currentMs)
        }

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = index
            )
        }
    }

    fun addNewSegment() {
        val state = _uiState.value
        val totalDuration = state.selectedVideo?.durationMs ?: 0L
        val currentMs = state.previewCurrentPositionMs
        val curList = state.segments.toMutableList()

        val defaultSpan = 15_000L // 15 seconds
        val newStart = currentMs.coerceAtMost((totalDuration - 1000L).coerceAtLeast(0L))
        val newEnd = (newStart + defaultSpan).coerceAtMost(totalDuration)

        val newSeg = TrimSegment(startMs = newStart, endMs = newEnd)
        curList.add(newSeg)

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = curList.size - 1
            )
        }
        seekPreview(newStart)
    }

    fun removeSegment(index: Int) {
        val curList = _uiState.value.segments.toMutableList()
        if (index in curList.indices && curList.size > 1) {
            curList.removeAt(index)
            val nextActive = (index - 1).coerceAtLeast(0)
            _uiState.update {
                it.copy(segments = curList, activeSegmentIndex = nextActive)
            }
        }
    }

    fun selectSegment(index: Int) {
        if (index in _uiState.value.segments.indices) {
            _uiState.update { it.copy(activeSegmentIndex = index) }
            val seg = _uiState.value.segments[index]
            seekPreview(seg.startMs)
        }
    }

    fun updateSegmentRange(index: Int, startMs: Long, endMs: Long) {
        val curList = _uiState.value.segments.toMutableList()
        if (index in curList.indices) {
            curList[index] = curList[index].copy(startMs = startMs, endMs = endMs)
            _uiState.update { it.copy(segments = curList) }
        }
    }

    fun setFormat(format: OutputAudioFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
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

    fun reEditExtractedAudio() {
        val success = _uiState.value.extractionState as? ExtractionState.Success ?: return
        val fileUri = Uri.fromFile(success.file)
        loadMedia(fileUri)
    }

    fun deleteExtractedAudio(onFinished: (Boolean) -> Unit = {}) {
        val success = _uiState.value.extractionState as? ExtractionState.Success ?: return
        viewModelScope.launch {
            stopAudio()
            val deleted = MediaStoreHelper.deleteAudio(
                context = getApplication(),
                mediaUri = success.mediaUri,
                localFile = success.file
            )
            success.extraFiles.forEach { f ->
                try { f.delete() } catch (_: Exception) {}
            }

            _uiState.update {
                it.copy(
                    extractionState = ExtractionState.Idle,
                    isAudioPlaying = false,
                    audioCurrentPositionMs = 0L
                )
            }
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
        val baseName = state.customFileName.ifBlank { "extracted_audio" }

        val effectiveSegments = if (state.isTrimmingEnabled && state.segments.isNotEmpty()) {
            state.segments.sortedBy { it.startMs }
        } else {
            listOf(TrimSegment(startMs = 0L, endMs = video.durationMs))
        }

        stopAudio()
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(extractionState = ExtractionState.Extracting(0f, "準備提取音訊..."))
            }

            // Check if user requested Separate Files vs Concatenation
            if (state.isTrimmingEnabled && state.exportMode == MultiCutExportMode.SEPARATE_FILES && effectiveSegments.size > 1) {
                // Separate files mode
                val extractedFiles = mutableListOf<File>()
                for ((idx, seg) in effectiveSegments.withIndex()) {
                    val partName = "${baseName}_part${idx + 1}.${format.extension}"
                    val partFile = File(outputDir, partName)
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(
                            idx.toFloat() / effectiveSegments.size,
                            "正在導出第 ${idx + 1}/${effectiveSegments.size} 段..."
                        ))
                    }

                    val segResult = when (format) {
                        OutputAudioFormat.M4A_NATIVE -> NativeExtractorEngine.extractAudioSegments(context, video.uri, partFile, listOf(seg)) {}
                        OutputAudioFormat.WAV_PCM -> WavExtractorEngine.extractWavSegments(context, video.uri, partFile, listOf(seg)) {}
                        else -> TransformerExtractorEngine.transcodeAudioSegments(context, video.uri, partFile, format, listOf(seg)) {}
                    }

                    segResult.onSuccess { f ->
                        extractedFiles.add(f)
                        MediaStoreHelper.saveAudioToMusicFolder(context, f, partName, format.mimeType)
                    }
                }

                if (extractedFiles.isNotEmpty()) {
                    val firstFile = extractedFiles.first()
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Success(
                            file = firstFile,
                            mediaUri = Uri.fromFile(firstFile),
                            extraFiles = extractedFiles.drop(1)
                        ))
                    }
                    loadAudioIntoPlayer(firstFile)
                } else {
                    _uiState.update { it.copy(extractionState = ExtractionState.Error("多段導出失敗")) }
                }
            } else {
                // Merge Concat mode (Default)
                val outputName = "$baseName.${format.extension}"
                val tempOutputFile = File(outputDir, outputName)

                val result: Result<File> = when (format) {
                    OutputAudioFormat.M4A_NATIVE -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "無損多段極速拼接分離中..."))
                        }
                        NativeExtractorEngine.extractAudioSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            segments = effectiveSegments,
                            onProgress = { p ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Extracting(p, "極速拼接處理中 ${(p * 100).toInt()}%"))
                                }
                            }
                        )
                    }
                    OutputAudioFormat.WAV_PCM -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "無壓縮 PCM 多段無縫拼接中..."))
                        }
                        WavExtractorEngine.extractWavSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            segments = effectiveSegments,
                            onProgress = { p ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Extracting(p, "PCM 拼接中 ${(p * 100).toInt()}%"))
                                }
                            }
                        )
                    }
                    OutputAudioFormat.AAC_TRANSCODE, OutputAudioFormat.MP3_COMPAT -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "Media3 多段拼接轉碼中 (${format.displayName})..."))
                        }
                        TransformerExtractorEngine.transcodeAudioSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            format = format,
                            segments = effectiveSegments,
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

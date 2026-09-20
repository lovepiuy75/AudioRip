package com.overlord.audiorip.data

enum class OutputAudioFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val isNativeSupported: Boolean,
    val description: String
) {
    M4A_NATIVE(
        displayName = "M4A (原生極速 · 推薦)",
        extension = "m4a",
        mimeType = "audio/mp4",
        isNativeSupported = true,
        description = "直接分離無損音軌，2秒極速完成，零品質損耗"
    ),
    MP3_192(
        displayName = "MP3 (通用高音質 192k)",
        extension = "mp3",
        mimeType = "audio/mpeg",
        isNativeSupported = false,
        description = "廣泛相容各大播放器與舊設備"
    ),
    MP3_320(
        displayName = "MP3 (極致音質 320k)",
        extension = "mp3",
        mimeType = "audio/mpeg",
        isNativeSupported = false,
        description = "頂級 MP3 位元率，音質細節最豐富"
    ),
    WAV(
        displayName = "WAV (無壓縮 PCM)",
        extension = "wav",
        mimeType = "audio/wav",
        isNativeSupported = false,
        description = "廣播級純淨無壓縮格式，體積較大"
    ),
    FLAC(
        displayName = "FLAC (無損壓縮)",
        extension = "flac",
        mimeType = "audio/flac",
        isNativeSupported = false,
        description = "發燒友無損壓縮格式，兼顧音質與體積"
    )
}

data class VideoMetadata(
    val uri: android.net.Uri,
    val fileName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val audioMime: String?,
    val width: Int,
    val height: Int
)

data class ExtractionParams(
    val inputUri: android.net.Uri,
    val outputFormat: OutputAudioFormat,
    val customFileName: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L // 0 means entire duration
)

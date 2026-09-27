package com.overlord.audiorip.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiTranscriptionService {

    private const val PREFS_NAME = "gemini_config"
    private const val KEY_API_KEY = "gemini_api_key"

    fun getSavedApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun saveApiKey(context: Context, apiKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    suspend fun transcribeAudio(
        context: Context,
        audioFile: File,
        mimeType: String,
        promptText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getSavedApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("請先設定 Gemini API Key，或改用下方的「免 API 系統分享」"))
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("音訊檔案不存在或為空"))
        }

        // Limit for direct inline base64 is approx 20MB
        if (audioFile.length() > 20 * 1024 * 1024) {
            return@withContext Result.failure(IllegalStateException("音訊檔案大於 20MB，建議先進行時間剪輯，或改用「免 API 系統分享」直接上傳"))
        }

        try {
            // Read file into base64
            val fileBytes = ByteArray(audioFile.length().toInt())
            FileInputStream(audioFile).use { it.read(fileBytes) }
            val base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

            // Construct payload
            val rootJson = JSONObject()
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()

            // Part 1: Instruction prompt
            val textPart = JSONObject().put("text", promptText)
            partsArray.put(textPart)

            // Part 2: Inline audio data
            val inlineDataObj = JSONObject().apply {
                put("mime_type", if (mimeType.isNotBlank()) mimeType else "audio/mp4")
                put("data", base64Data)
            }
            val audioPart = JSONObject().put("inline_data", inlineDataObj)
            partsArray.put(audioPart)

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            rootJson.put("contents", contentsArray)

            // API Endpoint: using gemini-1.5-flash for high-speed & multimodal transcription
            val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                doInput = true
                connectTimeout = 30000
                readTimeout = 120000 // 2 minutes for processing audio
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(rootJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val respJson = JSONObject(responseText)
                val candidates = respJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val resultText = parts.getJSONObject(0).optString("text", "")
                        if (resultText.isNotBlank()) {
                            return@withContext Result.success(resultText)
                        }
                    }
                }
                Result.failure(Exception("Gemini 回傳內容為空"))
            } else {
                val errorText = try {
                    BufferedReader(InputStreamReader(conn.errorStream, "UTF-8")).use { it.readText() }
                } catch (_: Exception) {
                    "HTTP $responseCode"
                }

                val userMessage = when (responseCode) {
                    400 -> "請求格式有誤或音訊格式不支援: $errorText"
                    401, 403 -> "API Key 無效或無權限，請檢查 API Key 設定"
                    429 -> "Gemini API 配額已用盡或請求過於頻繁，建議稍後再試或改用「免 API 系統分享」"
                    else -> "連線失敗 ($responseCode): $errorText"
                }
                Result.failure(Exception(userMessage))
            }
        } catch (e: Exception) {
            Result.failure(Exception("生成逐字稿時發生網路異常: ${e.localizedMessage ?: e.message}"))
        }
    }
}

package com.leslie.tvhome

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small OpenAI-compatible client used by the remote-voice pipeline. No third-party SDK is needed. */
internal object VoiceNetwork {
    private const val JSON = "application/json; charset=utf-8"

    fun transcribe(ctx: Context, audio: File, prefs: Prefs): String {
        val endpoint = requireHttp(prefs.voiceSttUrl, "语音识别 API 地址")
        val boundary = "----TigrisVoice${System.nanoTime()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            authorize(prefs.voiceSttKey)
        }
        try {
            connection.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("model", prefs.voiceSttModel.ifBlank { Prefs.VOICE_STT_MODEL_DEFAULT })
                if (prefs.voiceSttLanguage.isNotBlank()) field("language", prefs.voiceSttLanguage)
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"speech.m4a\"\r\n".toByteArray())
                out.write("Content-Type: audio/mp4\r\n\r\n".toByteArray())
                audio.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val body = readBody(connection)
            if (connection.responseCode !in 200..299) throw VoiceHttpException(httpError(connection.responseCode, body))
            val json = runCatching { JSONObject(body) }.getOrNull()
            return json?.optString("text")?.takeIf { it.isNotBlank() }
                ?: json?.optString("result")?.takeIf { it.isNotBlank() }
                ?: body.trim().takeIf { it.isNotBlank() }
                ?: throw VoiceHttpException("语音识别没有返回文字")
        } finally {
            connection.disconnect()
        }
    }

    fun chat(text: String, prefs: Prefs): String {
        val endpoint = requireHttp(prefs.voiceLlmUrl, "大模型 API 地址")
        return if (endpoint.substringBefore('?').trimEnd('/').endsWith("/responses", ignoreCase = true)) {
            responses(endpoint, text, prefs)
        } else {
            chatCompletions(endpoint, text, prefs)
        }
    }

    private fun chatCompletions(endpoint: String, text: String, prefs: Prefs): String {
        val messages = JSONArray().apply {
            if (prefs.voiceSystemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", prefs.voiceSystemPrompt))
            put(JSONObject().put("role", "user").put("content", text))
        }
        val payload = JSONObject()
            .put("model", prefs.voiceLlmModel.ifBlank { Prefs.VOICE_LLM_MODEL_DEFAULT })
            .put("messages", messages)
            .put("temperature", 0.4)
        val body = postJson(endpoint, prefs.voiceLlmKey, payload.toString())
        val json = JSONObject(body)
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("") { i -> content.optJSONObject(i)?.optString("text").orEmpty() }
            else -> json.optString("output_text")
        }.trim().ifBlank { throw VoiceHttpException("大模型没有返回文字") }
    }

    private fun responses(endpoint: String, text: String, prefs: Prefs): String {
        val input = JSONArray().apply {
            if (prefs.voiceSystemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", JSONArray().put(
                    JSONObject().put("type", "input_text").put("text", prefs.voiceSystemPrompt))))
            }
            put(JSONObject().put("role", "user").put("content", JSONArray().put(
                JSONObject().put("type", "input_text").put("text", text))))
        }
        val payload = JSONObject()
            .put("model", prefs.voiceLlmModel.ifBlank { Prefs.VOICE_LLM_MODEL_DEFAULT })
            .put("input", input)
            .put("store", false)
        val body = postJson(endpoint, prefs.voiceLlmKey, payload.toString())
        val json = JSONObject(body)
        val direct = json.optString("output_text")
        if (direct.isNotBlank()) return direct.trim()
        val output = json.optJSONArray("output") ?: throw VoiceHttpException("大模型没有返回文字")
        val answer = buildString {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i)?.takeIf { it.optString("type") == "message" } ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text" || part.optString("type") == "text") {
                        append(part.optString("text"))
                    }
                }
            }
        }.trim()
        if (answer.isBlank()) throw VoiceHttpException("大模型没有返回文字")
        return answer
    }

    fun speech(ctx: Context, text: String, prefs: Prefs): File {
        val endpoint = requireHttp(prefs.voiceTtsUrl, "TTS API 地址")
        val payload = JSONObject()
            .put("model", prefs.voiceTtsModel.ifBlank { Prefs.VOICE_TTS_MODEL_DEFAULT })
            .put("input", text)
            .put("voice", prefs.voiceTtsVoice.ifBlank { Prefs.VOICE_TTS_VOICE_DEFAULT })
            .put("response_format", "mp3")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", JSON)
            setRequestProperty("Accept", "audio/mpeg, application/json")
            authorize(prefs.voiceTtsKey)
        }
        val bytes: ByteArray
        val contentType: String
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val errorBytes = if (code !in 200..299) connection.errorStream?.readBytes() ?: ByteArray(0) else ByteArray(0)
            if (code !in 200..299) throw VoiceHttpException(httpError(code, String(errorBytes, Charsets.UTF_8)))
            contentType = connection.contentType.orEmpty()
            bytes = connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (bytes.isEmpty()) throw VoiceHttpException("TTS 返回了空音频")
        if (contentType.contains("json", ignoreCase = true)) {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val encoded = json.optString("audio").ifBlank { json.optString("audio_base64") }
                .ifBlank { json.optString("data") }
            if (encoded.isBlank()) throw VoiceHttpException("TTS JSON 中没有音频")
            return writeAudio(ctx, Base64.decode(encoded.substringAfter(',', encoded), Base64.DEFAULT))
        }
        return writeAudio(ctx, bytes)
    }

    private fun postJson(endpoint: String, key: String, payload: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", JSON)
            setRequestProperty("Accept", "application/json")
            authorize(key)
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = readBody(connection)
            if (connection.responseCode !in 200..299) throw VoiceHttpException(httpError(connection.responseCode, body))
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.authorize(key: String) {
        if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${key.trim()}")
    }

    private fun requireHttp(value: String, label: String): String {
        val url = value.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw VoiceHttpException("请先配置$label")
        return url
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
    }

    private fun httpError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        val detail = when {
            message.startsWith("{") -> runCatching { JSONObject(message).optString("message") }.getOrDefault(message)
            else -> message
        }
        return detail.takeIf { it.isNotBlank() }?.let { "API $code：$it" } ?: "API 请求失败（$code）"
    }

    private fun writeAudio(ctx: Context, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "answer-${System.nanoTime()}.mp3")
        file.writeBytes(bytes)
        return file
    }
}

internal class VoiceHttpException(message: String) : Exception(message)
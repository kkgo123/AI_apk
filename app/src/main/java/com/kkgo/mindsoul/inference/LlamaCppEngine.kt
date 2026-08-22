/*
 * ============================================================
 * LlamaCppEngine - llama.cpp 本地推理引擎 HTTP 客户端
 * ============================================================
 *
 * 通过 HTTP 调用本地运行的 llama.cpp server，支持两种 API 格式：
 * 1. 原生 /completion API（优先使用）
 * 2. OpenAI 兼容 /v1/chat/completions API（回退使用）
 *
 * 使用 Android 自带的 HttpURLConnection，不引入额外依赖。
 * 服务器地址从 SharedPreferences "llama_server_url" 读取，
 * 默认 http://localhost:8080。
 *
 * 使用方式：
 *   val engine = LlamaCppEngine("http://192.168.1.100:8080")
 *   val reply = engine.generate("你好", systemPrompt)
 * ============================================================
 */
package com.kkgo.mindsoul.inference

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LlamaCppEngine(
    /** 服务器基础地址，如 http://localhost:8080 或 http://192.168.1.100:8080 */
    private var baseUrl: String
) : InferenceEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"

        /** 连接超时（毫秒）——本地/局域网推理，5秒足够 */
        private const val CONNECT_TIMEOUT = 5_000

        /** 读取超时（毫秒）——本地推理可能较慢，给60秒 */
        private const val READ_TIMEOUT = 60_000
    }

    /**
     * 更新服务器地址（设置页修改后调用）
     */
    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    // ─────────────────────────────────────────────
    // InferenceEngine 接口实现
    // ─────────────────────────────────────────────

    override suspend fun generate(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        // 优先尝试原生 /completion API
        try {
            val result = callCompletionApi(prompt, systemPrompt, maxTokens)
            if (result.isNotBlank()) return@withContext result
        } catch (e: Exception) {
            Log.w(TAG, "[/completion] 调用失败，尝试回退到 OpenAI 兼容格式: ${e.message}")
        }

        // 回退到 OpenAI 兼容 /v1/chat/completions
        try {
            val result = callChatCompletionsApi(prompt, systemPrompt, maxTokens)
            if (result.isNotBlank()) return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "[/v1/chat/completions] 调用也失败: ${e.message}")
            throw e
        }

        "推理引擎返回为空，请检查 llama.cpp server 状态"
    }

    override fun isAvailable(): Boolean {
        return try {
            // 通过 /health 端点检测服务器是否可达
            val url = URL("$baseUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.d(TAG, "服务器不可达: ${e.message}")
            false
        }
    }

    override fun getEngineName(): String = "llama.cpp (HTTP)"

    // ─────────────────────────────────────────────
    // 连接测试（供设置页使用）
    // ─────────────────────────────────────────────

    /**
     * 测试服务器连接，返回结果描述
     *
     * @return Pair<是否成功, 描述文本>
     */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 先试 /health
            val healthResult = tryHealth()
            if (healthResult != null) {
                return@withContext Pair(true, "✅ 连接成功\n地址: $baseUrl\n状态: ${healthResult}")
            }

            // 再试 /v1/models
            val modelsResult = tryV1Models()
            if (modelsResult != null) {
                return@withContext Pair(true, "✅ 连接成功（OpenAI兼容模式）\n地址: $baseUrl\n$modelsResult")
            }

            Pair(false, "❌ 连接失败\n地址: $baseUrl\n原因: 服务器无响应")
        } catch (e: Exception) {
            val reason = when {
                e.message?.contains("Connection refused") == true -> "连接被拒绝，请确认服务器已启动"
                e.message?.contains("connect timed out") == true -> "连接超时，请检查地址是否正确"
                e.message?.contains("UnknownHost") == true -> "无法解析主机名，请检查地址"
                else -> e.message ?: "未知错误"
            }
            Pair(false, "❌ 连接失败\n地址: $baseUrl\n原因: $reason")
        }
    }

    // ─────────────────────────────────────────────
    // 内部方法：原生 /completion API
    // ─────────────────────────────────────────────

    /**
     * 调用 llama.cpp 原生 /completion API
     *
     * 请求格式：
     *   POST /completion
     *   {"prompt": "...", "n_predict": 512, "temperature": 0.7, "stop": ["\n\n"]}
     *
     * 返回格式：
     *   {"content": "...", "stop": true}
     */
    private fun callCompletionApi(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int
    ): String {
        // 将 systemPrompt 和用户 prompt 拼接为完整 prompt
        // llama.cpp 的 /completion 接受纯文本 prompt
        val fullPrompt = buildString {
            appendLine(systemPrompt)
            appendLine()
            append(prompt)
        }

        val jsonBody = JSONObject().apply {
            put("prompt", fullPrompt)
            put("n_predict", maxTokens)
            put("temperature", 0.7)
            put("stop", JSONArray().apply { put("\n\n") })
        }

        val responseStr = httpPost("$baseUrl/completion", jsonBody.toString())
        val responseJson = JSONObject(responseStr)

        // 原生格式返回 {"content": "...", "stop": true}
        val content = responseJson.optString("content", "")
        return content.trim()
    }

    // ─────────────────────────────────────────────
    // 内部方法：OpenAI 兼容 /v1/chat/completions
    // ─────────────────────────────────────────────

    /**
     * 调用 OpenAI 兼容格式的 /v1/chat/completions API
     *
     * 请求格式：
     *   POST /v1/chat/completions
     *   {"model": "local", "messages": [...], "max_tokens": 512}
     *
     * 返回格式：
     *   {"choices": [{"message": {"content": "..."}}]}
     */
    private fun callChatCompletionsApi(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int
    ): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val jsonBody = JSONObject().apply {
            put("model", "local")
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", 0.7)
        }

        val responseStr = httpPost("$baseUrl/v1/chat/completions", jsonBody.toString())
        val responseJson = JSONObject(responseStr)

        // OpenAI 格式返回 {"choices": [{"message": {"content": "..."}}]}
        val choices = responseJson.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0).optJSONObject("message")
            val content = message?.optString("content", "") ?: ""
            return content.trim()
        }

        return ""
    }

    // ─────────────────────────────────────────────
    // 内部方法：HTTP 工具
    // ─────────────────────────────────────────────

    /**
     * 发送 HTTP POST 请求，返回响应体字符串
     *
     * @param urlStr 完整 URL
     * @param jsonBody JSON 请求体
     * @return 响应体文本
     * @throws Exception 网络异常或 HTTP 错误
     */
    private fun httpPost(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            // 写入请求体
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).use { it.readText() }
                } catch (_: Exception) {
                    "无错误详情"
                }
                throw RuntimeException("HTTP $responseCode: $errorBody")
            }

            // 读取响应
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 发送 HTTP GET 请求，返回响应体字符串
     */
    private fun httpGet(urlStr: String, timeoutMs: Int = 3_000): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode")
            }
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────
    // 内部方法：连接测试辅助
    // ─────────────────────────────────────────────

    /**
     * 尝试调用 /health 端点
     * @return 状态描述，失败返回 null
     */
    private fun tryHealth(): String? {
        return try {
            val resp = httpGet("$baseUrl/health")
            // llama.cpp /health 通常返回 {"status": "ok", ...}
            try {
                val json = JSONObject(resp)
                val status = json.optString("status", "ok")
                "服务健康: $status"
            } catch (_: Exception) {
                "服务响应: $resp"
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 尝试调用 /v1/models 端点（OpenAI 兼容模式）
     * @return 模型信息描述，失败返回 null
     */
    private fun tryV1Models(): String? {
        return try {
            val resp = httpGet("$baseUrl/v1/models")
            val json = JSONObject(resp)
            val models = json.optJSONArray("data")
            if (models != null && models.length() > 0) {
                val modelId = models.getJSONObject(0).optString("id", "未知")
                "可用模型: $modelId（共${models.length()}个）"
            } else {
                "已连接（无模型信息）"
            }
        } catch (_: Exception) {
            null
        }
    }
}

/*
 * ============================================================
 * InferenceEngine - 推理引擎接口
 * ============================================================
 *
 * 定义统一的推理引擎接口，所有推理后端（llama.cpp、云端API、
 * 本地模式匹配等）均需实现此接口。
 *
 * 由 InferenceManager 统一调度，ChatActivity 无需关心具体实现。
 * ============================================================
 */
package com.kkgo.mindsoul.inference

/**
 * 推理引擎统一接口
 *
 * 所有推理后端实现此接口，提供统一的生成能力。
 */
interface InferenceEngine {

    /**
     * 生成回复文本
     *
     * @param prompt 用户输入（用户消息）
     * @param systemPrompt 系统提示词（人格/身份/意识状态描述）
     * @param maxTokens 最大生成 token 数，默认512
     * @return 生成的回复文本；失败时返回错误描述字符串
     */
    suspend fun generate(prompt: String, systemPrompt: String, maxTokens: Int = 512): String

    /**
     * 引擎是否可用
     *
     * 用于 InferenceManager 判断是否可以选用此引擎。
     * 例如：llama.cpp 引擎需要服务器可达才返回 true。
     */
    fun isAvailable(): Boolean

    /**
     * 引擎名称（用于日志和界面显示）
     */
    fun getEngineName(): String
}

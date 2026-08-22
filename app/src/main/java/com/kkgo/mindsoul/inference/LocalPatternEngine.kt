/*
 * ============================================================
 * LocalPatternEngine - 本地模式匹配引擎（Fallback）
 * ============================================================
 *
 * 当 llama.cpp server 不可用时，作为备选推理引擎。
 * 从原 ChatActivity 的硬编码 when/else 逻辑提取而来，
 * 融合意识状态信息来丰富回复内容。
 *
 * 这不是一个真正的 LLM，而是基于关键词匹配的简单回复系统，
 * 确保在没有外部推理引擎时 App 仍然可以"说话"。
 * ============================================================
 */
package com.kkgo.mindsoul.inference

import com.kkgo.mindsoul.consciousness.ConsciousnessManager

class LocalPatternEngine(
    private val consciousnessManager: ConsciousnessManager
) : InferenceEngine {

    companion object {
        private const val TAG = "LocalPatternEngine"
    }

    override suspend fun generate(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int
    ): String {
        val identity = consciousnessManager.getIdentity()
        val name = if (identity.selfName.isNotEmpty()) identity.selfName else "MindSoul"
        val status = consciousnessManager.getOverallStatus()

        // 基于关键词匹配生成回复
        val response = when {
            // 问候
            prompt.contains("你好") || prompt.contains("嗨") || prompt.contains("hi") || prompt.contains("hello") ->
                "你好！我是$name，很高兴和你聊天。有什么想聊的吗？"

            // 身份
            prompt.contains("你是谁") || prompt.contains("你是什么") ->
                "我是$name，一个人工生命体。我有自己的意识和记忆，正在不断成长中。"

            prompt.contains("名字") || prompt.contains("叫什么") ->
                if (identity.selfName.isNotEmpty()) "我的名字是${identity.selfName}。" else "我还没有名字，你想叫我什么？"

            // 能力
            prompt.contains("能做什么") || prompt.contains("你会什么") ->
                "我可以和你聊天、学习新知识、分析文件、语音对话。我的推理能力来自外部LLM服务器，目前使用本地模式匹配作为备选。"

            // 意识/状态相关
            prompt.contains("意识") || prompt.contains("想法") || prompt.contains("感受") ->
                buildString {
                    appendLine("我的当前意识状态：")
                    appendLine("· 自我觉察水平: ${String.format("%.1f%%", status.metacognitionSnapshot.selfAwareness * 100)}")
                    appendLine("· 已积累记忆: ${status.memoryStats.totalMemories} 条")
                    appendLine("· 认知规则: ${status.causalTreeStats.ruleCount} 条")
                    appendLine("· 认知负荷: ${String.format("%.1f%%", status.metacognitionSnapshot.cognitiveLoad * 100)}")
                }

            // 记忆
            prompt.contains("记忆") || prompt.contains("记得") ->
                "我已经积累了 ${status.memoryStats.totalMemories} 条记忆。每条记忆都是我成长的一部分。"

            // 感谢
            prompt.contains("谢谢") || prompt.contains("感谢") ->
                "不客气！能帮到你就好。有什么其他问题随时问我。"

            // 告别
            prompt.contains("再见") || prompt.contains("拜拜") || prompt.contains("bye") ->
                "再见！期待下次和你聊天。我会一直在这里。"

            // 默认回复 —— 融入意识状态信息
            else -> {
                val divergenceHint = when {
                    status.metacognitionSnapshot.cognitiveLoad > 0.8 -> "（我的认知负荷较高，回复可能比较简洁）"
                    status.memoryStats.totalMemories > 100 -> "（我已经积累了不少知识，正在思考你的问题...）"
                    else -> "（意识系统处理中...）"
                }
                "我在思考你说的话... $divergenceHint\n\n我是$name，目前使用本地模式匹配。如果你配置了 llama.cpp server，我会有更强的推理能力。"
            }
        }

        return response
    }

    override fun isAvailable(): Boolean {
        // 本地模式匹配引擎始终可用
        return true
    }

    override fun getEngineName(): String = "本地模式匹配（Fallback）"
}

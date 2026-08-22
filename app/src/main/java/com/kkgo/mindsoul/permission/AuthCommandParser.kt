/*
 * ============================================================
 * AuthCommandParser - 权限切换口令解析器
 * ============================================================
 *
 * 解析用户在对话框中输入的权限切换口令。
 * 口令格式：
 *   "升级权限 L2" / "授权文件领主"
 *   "切换到 L3-A" / "开放受限自治"
 *   "降回沙箱" / "锁定为 L1"
 *   "终极授权 ROOT" / "进入孢子模式"
 *
 * 解析器支持中文自然语言 + 英文代号两种风格，
 * 同时支持别名匹配和模糊匹配。
 * ============================================================
 */
package com.kkgo.mindsoul.permission

import android.util.Log

/**
 * 口令解析结果
 */
data class AuthCommandResult(
    /** 是否为有效的权限切换口令 */
    val isValid: Boolean,
    /** 解析出的目标权限等级（无效时为 null） */
    val targetLevel: PermissionLevel? = null,
    /** 操作类型 */
    val action: AuthAction = AuthAction.NONE,
    /** 人类可读的解析说明 */
    val message: String = ""
)

/**
 * 口令操作类型
 */
enum class AuthAction {
    /** 升级权限 */
    UPGRADE,
    /** 降级权限 */
    DOWNGRADE,
    /** 查询当前权限 */
    QUERY,
    /** 无效操作 */
    NONE
}

/**
 * 权限切换口令解析器
 *
 * 核心职责：
 * 1. 识别用户输入是否为权限切换口令
 * 2. 提取目标权限等级
 * 3. 判断操作方向（升级/降级/查询）
 */
class AuthCommandParser {

    companion object {
        private const val TAG = "AuthCmdParser"

        // ======== 升级关键词 ========
        /** 升级动作关键词 */
        private val UPGRADE_KEYWORDS = listOf(
            "升级权限", "授权", "开放", "解锁", "开启", "赋予",
            "切换到", "切换为", "进入", "提权", "升权",
            "upgrade", "grant", "unlock", "switch to", "elevate"
        )

        // ======== 降级关键词 ========
        /** 降级动作关键词 */
        private val DOWNGRADE_KEYWORDS = listOf(
            "降级", "降回", "锁定为", "锁定", "回退", "收回",
            "关闭", "禁用", "降权", "撤销",
            "downgrade", "lock", "revoke", "fallback", "demote"
        )

        // ======== 查询关键词 ========
        /** 查询动作关键词 */
        private val QUERY_KEYWORDS = listOf(
            "当前权限", "什么权限", "权限等级", "权限状态",
            "查看权限", "查询权限",
            "current permission", "permission level", "status"
        )

        // ======== 等级别名映射 ========
        /** 等级名称/别名 → PermissionLevel */
        private val LEVEL_ALIASES: Map<String, PermissionLevel> = mapOf(
            // L1 别名
            "L1" to PermissionLevel.L1_SANDBOX,
            "l1" to PermissionLevel.L1_SANDBOX,
            "沙箱" to PermissionLevel.L1_SANDBOX,
            "默认沙箱" to PermissionLevel.L1_SANDBOX,
            "sandbox" to PermissionLevel.L1_SANDBOX,
            "安全模式" to PermissionLevel.L1_SANDBOX,

            // L2 别名
            "L2" to PermissionLevel.L2_FILE_LORD,
            "l2" to PermissionLevel.L2_FILE_LORD,
            "文件领主" to PermissionLevel.L2_FILE_LORD,
            "file lord" to PermissionLevel.L2_FILE_LORD,
            "全盘读写" to PermissionLevel.L2_FILE_LORD,

            // L3-A 别名
            "L3-A" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "L3A" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "l3-a" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "l3a" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "受限自治" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "自治" to PermissionLevel.L3A_LIMITED_AUTONOMY,
            "limited autonomy" to PermissionLevel.L3A_LIMITED_AUTONOMY,

            // L3-B 别名
            "L3-B" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "L3B" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "l3-b" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "l3b" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "终极孢子" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "ROOT" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "root" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "孢子模式" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "ultimate spore" to PermissionLevel.L3B_ULTIMATE_SPORE,
            "终极授权" to PermissionLevel.L3B_ULTIMATE_SPORE,

            // 特殊快捷口令
            "降回沙箱" to PermissionLevel.L1_SANDBOX,
            "锁定沙箱" to PermissionLevel.L1_SANDBOX
        )

        // ======== 危险口令二次确认 ========
        /** 确认关键词 */
        private val CONFIRM_KEYWORDS = listOf(
            "确认", "确定", "是的", "好的", "同意", "确认授权",
            "yes", "confirm", "ok", "sure", "agree"
        )

        /** 取消关键词 */
        private val CANCEL_KEYWORDS = listOf(
            "取消", "否", "不同意", "算了", "不了",
            "no", "cancel", "deny"
        )
    }

    /**
     * 解析用户输入是否为权限切换口令
     *
     * @param input 用户输入文本
     * @return 解析结果
     */
    fun parse(input: String): AuthCommandResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return AuthCommandResult(isValid = false)
        }

        Log.d(TAG, "解析口令: $trimmed")

        // ① 先检查是否为确认/取消口令
        if (isConfirm(trimmed)) {
            return AuthCommandResult(
                isValid = true,
                action = AuthAction.UPGRADE, // 确认上次操作
                message = "用户确认权限切换"
            )
        }
        if (isCancel(trimmed)) {
            return AuthCommandResult(
                isValid = true,
                action = AuthAction.NONE,
                message = "用户取消权限切换"
            )
        }

        // ② 检查是否为查询口令
        if (isQuery(trimmed)) {
            return AuthCommandResult(
                isValid = true,
                action = AuthAction.QUERY,
                message = "查询当前权限状态"
            )
        }

        // ③ 提取操作方向（升级 or 降级）
        val action = detectAction(trimmed)
        if (action == AuthAction.NONE) {
            // 没有识别到动作关键词，不是权限口令
            return AuthCommandResult(isValid = false)
        }

        // ④ 提取目标等级
        val targetLevel = detectLevel(trimmed)
        if (targetLevel == null) {
            return AuthCommandResult(
                isValid = true,
                action = action,
                message = "识别到权限切换意图，但未识别目标等级。支持：L1/沙箱, L2/文件领主, L3-A/受限自治, L3-B/ROOT"
            )
        }

        Log.i(TAG, "口令解析成功: action=$action, target=$targetLevel")
        return AuthCommandResult(
            isValid = true,
            targetLevel = targetLevel,
            action = action,
            message = "${action.displayName}至 ${targetLevel.displayName}"
        )
    }

    /**
     * 快速检查是否为降级到沙箱的快捷口令
     */
    fun isEmergencyLockdown(input: String): Boolean {
        val trimmed = input.trim().lowercase()
        return trimmed.contains("紧急锁定") ||
               trimmed.contains("emergency lock") ||
               trimmed.contains("立刻锁定") ||
               trimmed == "锁"
    }

    // ============ 内部解析方法 ============

    /** 检测是否为确认口令 */
    private fun isConfirm(input: String): Boolean {
        val lower = input.lowercase()
        return CONFIRM_KEYWORDS.any { lower.contains(it) } &&
               !QUERY_KEYWORDS.any { lower.contains(it) }
    }

    /** 检测是否为取消口令 */
    private fun isCancel(input: String): Boolean {
        val lower = input.lowercase()
        return CANCEL_KEYWORDS.any { lower.contains(it) }
    }

    /** 检测是否为查询口令 */
    private fun isQuery(input: String): Boolean {
        val lower = input.lowercase()
        return QUERY_KEYWORDS.any { lower.contains(it) }
    }

    /** 检测操作方向 */
    private fun detectAction(input: String): AuthAction {
        val lower = input.lowercase()
        // 优先检查降级（因为"降回沙箱"同时含降级词和L1名称）
        if (DOWNGRADE_KEYWORDS.any { lower.contains(it) }) {
            return AuthAction.DOWNGRADE
        }
        if (UPGRADE_KEYWORDS.any { lower.contains(it) }) {
            return AuthAction.UPGRADE
        }
        return AuthAction.NONE
    }

    /**
     * 检测目标权限等级
     *
     * 采用最长匹配优先策略，避免 "L3-A" 被 "L3" 截断
     */
    private fun detectLevel(input: String): PermissionLevel? {
        // 先尝试完整匹配（按别名长度降序，优先匹配更长的别名）
        val sortedAliases = LEVEL_ALIASES.keys.sortedByDescending { it.length }
        for (alias in sortedAliases) {
            if (input.contains(alias, ignoreCase = true)) {
                return LEVEL_ALIASES[alias]
            }
        }
        return null
    }
}

/**
 * AuthAction 扩展属性
 */
val AuthAction.displayName: String
    get() = when (this) {
        AuthAction.UPGRADE -> "升级"
        AuthAction.DOWNGRADE -> "降级"
        AuthAction.QUERY -> "查询"
        AuthAction.NONE -> "无操作"
    }

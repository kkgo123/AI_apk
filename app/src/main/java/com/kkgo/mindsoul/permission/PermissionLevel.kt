/*
 * ============================================================
 * PermissionLevel - 权限等级定义
 * ============================================================
 *
 * 定义 MindSoul 人工生命的三级权限体系：
 *   L1 默认沙箱    → 仅可读写自有数据盘目录
 *   L2 文件领主    → 解锁全盘读写、自主采集
 *   L3-A 受限自治  → 全开文件、局域网、无障碍、逆向
 *   L3-B 终极孢子  → ROOT全开系统权限
 *
 * 每个等级携带能力集（Capability），上层模块通过
 * 查询能力集来决定是否放行操作。
 * ============================================================
 */
package com.kkgo.mindsoul.permission

/**
 * 权限等级枚举
 *
 * 等级越高风险越大，切换需要口令验证。
 */
enum class PermissionLevel(
    /** 等级编号，用于序列化 */
    val levelId: Int,
    /** 人类可读名称 */
    val displayName: String,
    /** 简短描述 */
    val description: String
) {
    /**
     * L1 默认沙箱
     * 仅可读写字盘自有目录（/data/data/... 及外部存储自有目录）
     * 最安全的默认状态
     */
    L1_SANDBOX(1, "L1 默认沙箱", "仅自有目录读写，最安全默认状态"),

    /**
     * L2 文件领主
     * 解锁全盘读写权限，可自主采集文件系统数据
     */
    L2_FILE_LORD(2, "L2 文件领主", "全盘读写 + 自主文件采集"),

    /**
     * L3-A 受限自治
     * 全开文件权限 + 局域网访问 + 无障碍服务 + 逆向能力
     */
    L3A_LIMITED_AUTONOMY(3, "L3-A 受限自治", "文件全开 + 局域网 + 无障碍 + 逆向"),

    /**
     * L3-B 终极孢子 ROOT
     * 全开系统权限，包括 ROOT 操作
     * 最高危险等级
     */
    L3B_ULTIMATE_SPORE(4, "L3-B 终极孢子ROOT", "全开系统权限，ROOT级操作");

    companion object {
        /**
         * 根据 levelId 反查枚举
         */
        fun fromId(id: Int): PermissionLevel {
            return entries.firstOrNull { it.levelId == id } ?: L1_SANDBOX
        }
    }
}

/**
 * 能力枚举
 *
 * 细粒度的操作能力标识，每个权限等级对应一组能力。
 */
enum class Capability(
    /** 能力描述 */
    val description: String
) {
    /** 读写自有数据目录 */
    OWN_DIR_RW("读写自有数据目录"),
    /** 全盘文件读取 */
    GLOBAL_FILE_READ("全盘文件读取"),
    /** 全盘文件写入 */
    GLOBAL_FILE_WRITE("全盘文件写入"),
    /** 自主文件采集（遍历、扫描） */
    AUTONOMOUS_COLLECT("自主文件采集"),
    /** 局域网访问 */
    LAN_ACCESS("局域网访问"),
    /** 无障碍服务 */
    ACCESSIBILITY("无障碍服务"),
    /** 逆向工程能力 */
    REVERSE_ENGINEERING("逆向工程能力"),
    /** ROOT 系统操作 */
    ROOT_OPERATION("ROOT 系统操作"),
    /** 网络完全访问 */
    FULL_NETWORK("网络完全访问"),
    /** 安装/卸载应用 */
    INSTALL_UNINSTALL("安装/卸载应用")
}

/**
 * 权限等级 → 能力集 映射
 */
object PermissionCapabilityMap {

    /** 各等级对应的能力集合 */
    private val capabilityMap: Map<PermissionLevel, Set<Capability>> = mapOf(
        PermissionLevel.L1_SANDBOX to setOf(
            Capability.OWN_DIR_RW
        ),
        PermissionLevel.L2_FILE_LORD to setOf(
            Capability.OWN_DIR_RW,
            Capability.GLOBAL_FILE_READ,
            Capability.GLOBAL_FILE_WRITE,
            Capability.AUTONOMOUS_COLLECT
        ),
        PermissionLevel.L3A_LIMITED_AUTONOMY to setOf(
            Capability.OWN_DIR_RW,
            Capability.GLOBAL_FILE_READ,
            Capability.GLOBAL_FILE_WRITE,
            Capability.AUTONOMOUS_COLLECT,
            Capability.LAN_ACCESS,
            Capability.ACCESSIBILITY,
            Capability.REVERSE_ENGINEERING,
            Capability.FULL_NETWORK
        ),
        PermissionLevel.L3B_ULTIMATE_SPORE to setOf(
            Capability.OWN_DIR_RW,
            Capability.GLOBAL_FILE_READ,
            Capability.GLOBAL_FILE_WRITE,
            Capability.AUTONOMOUS_COLLECT,
            Capability.LAN_ACCESS,
            Capability.ACCESSIBILITY,
            Capability.REVERSE_ENGINEERING,
            Capability.FULL_NETWORK,
            Capability.ROOT_OPERATION,
            Capability.INSTALL_UNINSTALL
        )
    )

    /**
     * 检查某等级是否具备指定能力
     *
     * @param level 权限等级
     * @param cap 要检查的能力
     * @return true 表示具备
     */
    fun has(level: PermissionLevel, cap: Capability): Boolean {
        return capabilityMap[level]?.contains(cap) ?: false
    }

    /**
     * 获取某等级的全部能力
     */
    fun capabilitiesOf(level: PermissionLevel): Set<Capability> {
        return capabilityMap[level] ?: emptySet()
    }
}

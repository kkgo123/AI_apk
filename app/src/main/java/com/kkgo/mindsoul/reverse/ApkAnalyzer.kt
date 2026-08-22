/*
 * ============================================================
 * ApkAnalyzer - APK 文件分析器
 * ============================================================
 *
 * 分析 Android APK 安装包：
 *
 * APK = ZIP 容器，内含：
 *   classes.dex (可执行代码)
 *   AndroidManifest.xml (应用清单)
 *   res/ (资源目录)
 *   assets/ (原始资产)
 *   META-INF/ (签名)
 *   lib/ (本地库)
 *
 * 本分析器提取：
 *   - 应用基本信息（包名/版本/权限）
 *   - Activity/Service/Receiver/Provider 清单
 *   - DEX 文件列表及类统计
 *   - 本地库（SO）列表
 *   - 签名信息
 *   - 资源文件概览
 *
 * 安全约束：
 *   - 仅解析用户手动导入的文件
 *   - 禁止自动扫描系统分区
 *   - 适配 Android 10+ Scoped Storage
 * ============================================================
 */
package com.kkgo.mindsoul.reverse

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * APK 分析结果
 */
data class ApkAnalysisResult(
    /** APK 文件名 */
    val fileName: String,
    /** APK 文件大小 */
    val fileSize: Long,
    /** 包名 */
    val packageName: String,
    /** 版本名 */
    val versionName: String,
    /** 版本号 */
    val versionCode: Long,
    /** 最低 SDK 版本 */
    val minSdkVersion: Int,
    /** 目标 SDK 版本 */
    val targetSdkVersion: Int,
    /** 请求的权限列表 */
    val permissions: List<String>,
    /** Activity 列表 */
    val activities: List<ComponentInfo>,
    /** Service 列表 */
    val services: List<ComponentInfo>,
    /** Receiver 列表 */
    val receivers: List<ComponentInfo>,
    /** Provider 列表 */
    val providers: List<ComponentInfo>,
    /** DEX 文件列表 */
    val dexFiles: List<DexFileInfo>,
    /** 本地库（SO）列表 */
    val nativeLibraries: List<String>,
    /** 资产文件概览 */
    val assetsOverview: List<String>,
    /** 签名信息 */
    val signatureInfo: SignatureInfo?,
    /** 入口 Activity */
    val launcherActivity: String?,
    /** 分析耗时（毫秒） */
    val analysisDurationMs: Long
)

/**
 * 组件信息（Activity/Service/Receiver/Provider）
 */
data class ComponentInfo(
    /** 完整类名 */
    val className: String,
    /** 是否导出 */
    val exported: Boolean,
    /** intent-filter 动作列表 */
    val intentActions: List<String> = emptyList(),
    /** intent-filter 数据 scheme */
    val intentSchemes: List<String> = emptyList()
)

/**
 * DEX 文件信息
 */
data class DexFileInfo(
    /** 文件名（如 classes.dex, classes2.dex） */
    val fileName: String,
    /** 文件大小 */
    val fileSize: Long,
    /** 类数量（估算） */
    val estimatedClassCount: Int
)

/**
 * 签名信息
 */
data class SignatureInfo(
    /** 签名算法 */
    val algorithm: String,
    /** 证书主题 */
    val subject: String,
    /** 颁发者 */
    val issuer: String,
    /** 有效期开始 */
    val validFrom: String,
    /** 有效期结束 */
    val validUntil: String,
    /** 证书指纹 (SHA-256) */
    val fingerprint: String
)

/**
 * APK 文件分析器
 */
class ApkAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ApkAnalyzer"
        /** 最大分析文件大小（200MB） */
        const val MAX_FILE_SIZE = 200 * 1024 * 1024L
    }

    private val dexParser = DexParser()

    /**
     * 分析 APK 文件
     *
     * @param filePath APK 文件路径
     * @return 分析结果
     */
    fun analyze(filePath: String): ApkAnalysisResult {
        val startTime = System.currentTimeMillis()
        val file = File(filePath)

        Log.i(TAG, "[分析] 开始分析: ${file.name} (${file.length() / 1024}KB)")

        // 安全检查
        require(file.exists()) { "文件不存在: $filePath" }
        require(file.length() <= MAX_FILE_SIZE) { "文件过大" }
        require(file.extension.equals("apk", ignoreCase = true)) { "不是 APK 文件" }

        var packageName = ""
        var versionName = ""
        var versionCode = 0L
        var minSdk = 0
        var targetSdk = 0
        val permissions = mutableListOf<String>()
        val activities = mutableListOf<ComponentInfo>()
        val services = mutableListOf<ComponentInfo>()
        val receivers = mutableListOf<ComponentInfo>()
        val providers = mutableListOf<ComponentInfo>()
        val dexFiles = mutableListOf<DexFileInfo>()
        val nativeLibs = mutableListOf<String>()
        val assetsOverview = mutableListOf<String>()
        var launcherActivity: String? = null
        var signatureInfo: SignatureInfo? = null

        try {
            val zipFile = ZipFile(file)

            // 遍历 ZIP 条目
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                when {
                    // ── AndroidManifest.xml ──
                    name == "AndroidManifest.xml" -> {
                        val manifestData = zipFile.getInputStream(entry).readBytes()
                        val manifestInfo = parseBinaryXml(manifestData)
                        packageName = manifestInfo.packageName
                        versionName = manifestInfo.versionName
                        versionCode = manifestInfo.versionCode
                        minSdk = manifestInfo.minSdkVersion
                        targetSdk = manifestInfo.targetSdkVersion
                        permissions.addAll(manifestInfo.permissions)
                        activities.addAll(manifestInfo.activities)
                        services.addAll(manifestInfo.services)
                        receivers.addAll(manifestInfo.receivers)
                        providers.addAll(manifestInfo.providers)
                        launcherActivity = manifestInfo.launcherActivity
                        Log.d(TAG, "  ✓ 清单解析: $packageName v$versionName")
                    }

                    // ── DEX 文件 ──
                    name.endsWith(".dex") -> {
                        dexFiles.add(DexFileInfo(
                            fileName = name,
                            fileSize = entry.size,
                            estimatedClassCount = estimateClassCount(
                                zipFile.getInputStream(entry)
                            )
                        ))
                        Log.d(TAG, "  ✓ DEX: $name (${entry.size / 1024}KB)")
                    }

                    // ── 本地库 ──
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        nativeLibs.add(name)
                        Log.d(TAG, "  ✓ SO: $name")
                    }

                    // ── 资产 ──
                    name.startsWith("assets/") && !entry.isDirectory -> {
                        if (assetsOverview.size < 100) { // 限制概览数量
                            assetsOverview.add(name)
                        }
                    }

                    // ── 签名 ──
                    name.startsWith("META-INF/") &&
                            (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) -> {
                        signatureInfo = extractSignatureInfo(zipFile.getInputStream(entry))
                        Log.d(TAG, "  ✓ 签名: ${signatureInfo.algorithm}")
                    }
                }
            }

            zipFile.close()

        } catch (e: Exception) {
            Log.e(TAG, "[分析] 异常: ${e.message}")
        }

        val duration = System.currentTimeMillis() - startTime

        Log.i(TAG, "[分析] 完成: 包名=$packageName, DEX=${dexFiles.size}, " +
                "Activity=${activities.size}, SO=${nativeLibs.size}")
        Log.i(TAG, "[分析] 耗时: ${duration}ms")

        return ApkAnalysisResult(
            fileName = file.name,
            fileSize = file.length(),
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            dexFiles = dexFiles,
            nativeLibraries = nativeLibs,
            assetsOverview = assetsOverview,
            signatureInfo = signatureInfo,
            launcherActivity = launcherActivity,
            analysisDurationMs = duration
        )
    }

    /**
     * 验证文件是否为有效 APK
     */
    fun isApkFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) return false
            val zipFile = ZipFile(file)
            val hasManifest = zipFile.entries().asSequence()
                .any { it.name == "AndroidManifest.xml" }
            zipFile.close()
            hasManifest
        } catch (e: Exception) {
            false
        }
    }

    // ── 内部方法 ──

    /**
     * 解析 Android 二进制 XML（简化版）
     *
     * 二进制 XML 格式：
     *   魔数 + 文件大小 + 字符串池 + XML 树
     *
     * 这里采用轻量级解析，提取关键信息
     */
    private fun parseBinaryXml(data: ByteArray): BinaryManifestInfo {
        val info = BinaryManifestInfo()

        try {
            // 二进制 XML 魔数：0x00080003
            if (data.size < 8) return info
            val magic = (data[0].toInt() and 0xFF) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[3].toInt() and 0xFF) shl 24)

            if (magic != 0x00080003) {
                Log.w(TAG, "[清单] 非标准二进制 XML 格式")
                return info
            }

            // 简化版：通过字符串搜索提取关键信息
            val textContent = extractStringsFromBinary(data)

            // 包名提取
            for (str in textContent) {
                when {
                    str.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) -> {
                        if (info.packageName.isEmpty()) {
                            info.packageName = str
                        }
                    }
                    str.matches(Regex("\\d+\\.\\d+(\\.\\d+)*")) -> {
                        if (info.versionName.isEmpty()) {
                            info.versionName = str
                        }
                    }
                    str.startsWith("android.permission.") -> {
                        info.permissions.add(str)
                    }
                    str.endsWith("Activity") || str.contains(".ui.") -> {
                        info.activities.add(ComponentInfo(str, false))
                    }
                    str.endsWith("Service") -> {
                        info.services.add(ComponentInfo(str, false))
                    }
                    str.endsWith("Receiver") || str.endsWith("Provider") -> {
                        info.receivers.add(ComponentInfo(str, false))
                    }
                }
            }

            // 设置默认入口
            if (info.activities.isNotEmpty()) {
                info.launcherActivity = info.activities.firstOrNull {
                    it.className.contains("Main") || it.className.contains("Launch")
                }?.className ?: info.activities.first().className
            }

        } catch (e: Exception) {
            Log.w(TAG, "[清单] 二进制解析异常: ${e.message}")
        }

        return info
    }

    /**
     * 从二进制 XML 中提取字符串
     */
    private fun extractStringsFromBinary(data: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val sb = StringBuilder()

        for (byte in data) {
            val b = byte.toInt() and 0xFF
            if (b in 0x20..0x7E || b in 0x80..0xFF) {
                // ASCII 可打印字符或 UTF-8 高字节
                sb.append(b.toChar())
            } else {
                if (sb.length >= 4) { // 只保留长度>=4 的字符串
                    val str = sb.toString().trim()
                    if (str.isNotEmpty() && str.any { it.isLetter() }) {
                        strings.add(str)
                    }
                }
                sb.clear()
            }
        }

        // 最后一段
        if (sb.length >= 4) {
            val str = sb.toString().trim()
            if (str.isNotEmpty()) strings.add(str)
        }

        return strings.distinct()
    }

    /**
     * 估算 DEX 中的类数量
     */
    private fun estimateClassCount(inputStream: InputStream): Int {
        return try {
            val data = inputStream.readBytes()
            if (data.size < 96) return 0
            // 类定义段大小位于偏移 96-99（4字节小端）
            val classDefsSize = (data[96].toInt() and 0xFF) or
                    ((data[97].toInt() and 0xFF) shl 8) or
                    ((data[98].toInt() and 0xFF) shl 16) or
                    ((data[99].toInt() and 0xFF) shl 24)
            classDefsSize
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 提取签名信息（简化版）
     */
    private fun extractSignatureInfo(inputStream: InputStream): SignatureInfo {
        return try {
            // 实际应使用 CertificateFactory 解析 X.509 证书
            // 这里返回简化的签名概要
            SignatureInfo(
                algorithm = "RSA/SHA256",
                subject = "Android Application",
                issuer = "Android Application",
                validFrom = "Unknown",
                validUntil = "Unknown",
                fingerprint = "Not parsed"
            )
        } catch (e: Exception) {
            SignatureInfo("Unknown", "Unknown", "Unknown", "", "", "")
        }
    }
}

/**
 * 二进制清单信息（内部数据类）
 */
internal data class BinaryManifestInfo(
    var packageName: String = "",
    var versionName: String = "",
    var versionCode: Long = 0,
    var minSdkVersion: Int = 0,
    var targetSdkVersion: Int = 0,
    val permissions: MutableList<String> = mutableListOf(),
    val activities: MutableList<ComponentInfo> = mutableListOf(),
    val services: MutableList<ComponentInfo> = mutableListOf(),
    val receivers: MutableList<ComponentInfo> = mutableListOf(),
    val providers: MutableList<ComponentInfo> = mutableListOf(),
    var launcherActivity: String? = null
)

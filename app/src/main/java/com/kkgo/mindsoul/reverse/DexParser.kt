/*
 * ============================================================
 * DexParser - DEX 文件解析器
 * ============================================================
 *
 * 解析 Android DEX (Dalvik Executable) 文件：
 *
 * DEX 文件结构：
 *   Header（魔数/校验/偏移表）
 *   → String IDs（字符串索引）
 *   → Type IDs（类型索引）
 *   → Proto IDs（方法原型）
 *   → Field IDs（字段索引）
 *   → Method IDs（方法索引）
 *   → Class Defs（类定义）
 *   → Data Section（数据区：代码/调试信息）
 *
 * 本解析器提取：
 *   - 所有类名
 *   - 方法签名
 *   - 字段定义
 *   - 字符串常量
 *   - 调用关系（简化版）
 *
 * 安全约束：
 *   - 仅解析用户手动导入的文件
 *   - 禁止自动扫描系统分区
 * ============================================================
 */
package com.kkgo.mindsoul.reverse

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEX 文件头部
 */
data class DexHeader(
    /** 魔数 "dex\n" */
    val magic: String,
    /** 版本号（如 "035\0"、"037\0"） */
    val version: String,
    /** 文件校验和 (Adler32) */
    val checksum: Long,
    /** SHA-1 签名 */
    val signature: ByteArray,
    /** 文件大小 */
    val fileSize: Long,
    /** 头部大小（通常 0x70） */
    val headerSize: Long,
    /**  endian 标记 */
    val endianTag: Long,
    /** 各段偏移和大小 */
    val stringIdsSize: Int,
    val stringIdsOff: Long,
    val typeIdsSize: Int,
    val typeIdsOff: Long,
    val protoIdsSize: Int,
    val protoIdsOff: Long,
    val fieldIdsSize: Int,
    val fieldIdsOff: Long,
    val methodIdsSize: Int,
    val methodIdsOff: Long,
    val classDefsSize: Int,
    val classDefsOff: Long,
    val dataSize: Int,
    val dataOff: Long
)

/**
 * DEX 类定义
 */
data class DexClassDef(
    /** 类名索引 */
    val classIdx: Int,
    /** 访问标志 */
    val accessFlags: Int,
    /** 父类索引 */
    val superclassIdx: Int,
    /** 接口偏移 */
    val interfacesOff: Long,
    /** 源文件名索引 */
    val sourceFileIdx: Int,
    /** 注解偏移 */
    val annotationsOff: Long,
    /** 类数据偏移 */
    val classDataOff: Long,
    /** 静态值偏移 */
    val staticValuesOff: Long
)

/**
 * DEX 方法 ID
 */
data class DexMethodId(
    /** 所属类索引 */
    val classIdx: Int,
    /** 原型索引 */
    val protoIdx: Int,
    /** 名称索引 */
    val nameIdx: Int
)

/**
 * DEX 字段 ID
 */
data class DexFieldId(
    /** 所属类索引 */
    val classIdx: Int,
    /** 类型索引 */
    val typeIdx: Int,
    /** 名称索引 */
    val nameIdx: Int
)

/**
 * DEX 解析结果
 */
data class DexParseResult(
    /** 文件名 */
    val fileName: String,
    /** DEX 版本 */
    val version: String,
    /** 文件大小 */
    val fileSize: Long,
    /** 类名列表 */
    val classNames: List<String>,
    /** 方法签名列表 */
    val methodSignatures: List<String>,
    /** 字段列表 */
    val fieldDefinitions: List<String>,
    /** 字符串常量列表 */
    val stringConstants: List<String>,
    /** 类→方法映射 */
    val classMethods: Map<String, List<String>>,
    /** 解析耗时（毫秒） */
    val parseDurationMs: Long
)

/**
 * DEX 文件解析器
 */
class DexParser {

    companion object {
        private const val TAG = "DexParser"
        /** DEX 魔数 */
        const val DEX_MAGIC = "dex\n"
        /** DEX 文件扩展名 */
        const val DEX_EXTENSION = ".dex"
        /** 最大解析文件大小（50MB） */
        const val MAX_FILE_SIZE = 50 * 1024 * 1024L
    }

    /**
     * 解析 DEX 文件
     *
     * @param filePath DEX 文件路径
     * @return 解析结果
     */
    fun parse(filePath: String): DexParseResult {
        val startTime = System.currentTimeMillis()
        val file = File(filePath)

        Log.i(TAG, "[解析] 开始解析: ${file.name}")

        // 安全检查
        require(file.exists()) { "文件不存在: $filePath" }
        require(file.length() <= MAX_FILE_SIZE) { "文件过大: ${file.length()} bytes" }

        val buffer = RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        // 验证魔数
        val magic = readDexString(buffer, 0, 4)
        require(magic == DEX_MAGIC) { "不是有效的 DEX 文件: magic=$magic" }

        // 解析头部
        val header = parseHeader(buffer)

        // 解析各段
        val strings = parseStrings(buffer, header)
        val types = parseTypes(buffer, header)
        val methodIds = parseMethodIds(buffer, header)
        val fieldIds = parseFieldIds(buffer, header)
        val classDefs = parseClassDefs(buffer, header)

        // 构建结果
        val classNames = classDefs.map { idx ->
            if (idx.classIdx < types.size) types[idx.classIdx] else "unknown"
        }

        val classMethods = mutableMapOf<String, MutableList<String>>()
        val allMethods = mutableListOf<String>()

        for (method in methodIds) {
            val className = if (method.classIdx < types.size) types[method.classIdx] else "unknown"
            val methodName = if (method.nameIdx < strings.size) strings[method.nameIdx] else "unknown"
            val signature = "$className.$methodName"
            allMethods.add(signature)

            classMethods.getOrPut(className) { mutableListOf() }.add(methodName)
        }

        val allFields = fieldIds.map { field ->
            val className = if (field.classIdx < types.size) types[field.classIdx] else "unknown"
            val fieldName = if (field.nameIdx < strings.size) strings[field.nameIdx] else "unknown"
            val fieldType = if (field.typeIdx < types.size) types[field.typeIdx] else "unknown"
            "$className.$fieldName : $fieldType"
        }

        val duration = System.currentTimeMillis() - startTime

        Log.i(TAG, "[解析] 完成: ${classNames.size} 类, ${allMethods.size} 方法, " +
                "${allFields.size} 字段, ${strings.size} 字符串")
        Log.i(TAG, "[解析] 耗时: ${duration}ms")

        return DexParseResult(
            fileName = file.name,
            version = header.version,
            fileSize = header.fileSize,
            classNames = classNames,
            methodSignatures = allMethods,
            fieldDefinitions = allFields,
            stringConstants = strings,
            classMethods = classMethods,
            parseDurationMs = duration
        )
    }

    /**
     * 验证文件是否为 DEX 格式
     */
    fun isDexFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists() || file.length() < 8) return false
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.read(magic)
                String(magic) == DEX_MAGIC
            }
        } catch (e: Exception) {
            false
        }
    }

    // ── 内部解析方法 ──

    private fun parseHeader(buffer: ByteBuffer): DexHeader {
        buffer.position(0)
        val magicBytes = ByteArray(4)
        buffer.get(magicBytes)
        val versionBytes = ByteArray(4)
        buffer.get(versionBytes)

        val checksum = buffer.int.toLong() and 0xFFFFFFFFL
        val signature = ByteArray(20)
        buffer.get(signature)
        val fileSize = buffer.int.toLong() and 0xFFFFFFFFL
        val headerSize = buffer.int.toLong() and 0xFFFFFFFFL
        val endianTag = buffer.int.toLong() and 0xFFFFFFFFL

        val stringIdsSize = buffer.int
        val stringIdsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val typeIdsSize = buffer.int
        val typeIdsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val protoIdsSize = buffer.int
        val protoIdsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val fieldIdsSize = buffer.int
        val fieldIdsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val methodIdsSize = buffer.int
        val methodIdsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val classDefsSize = buffer.int
        val classDefsOff = buffer.int.toLong() and 0xFFFFFFFFL
        val dataSize = buffer.int
        val dataOff = buffer.int.toLong() and 0xFFFFFFFFL

        return DexHeader(
            magic = String(magicBytes),
            version = String(versionBytes),
            checksum = checksum,
            signature = signature,
            fileSize = fileSize,
            headerSize = headerSize,
            endianTag = endianTag,
            stringIdsSize = stringIdsSize,
            stringIdsOff = stringIdsOff,
            typeIdsSize = typeIdsSize,
            typeIdsOff = typeIdsOff,
            protoIdsSize = protoIdsSize,
            protoIdsOff = protoIdsOff,
            fieldIdsSize = fieldIdsSize,
            fieldIdsOff = fieldIdsOff,
            methodIdsSize = methodIdsSize,
            methodIdsOff = methodIdsOff,
            classDefsSize = classDefsSize,
            classDefsOff = classDefsOff,
            dataSize = dataSize,
            dataOff = dataOff
        )
    }

    private fun parseStrings(buffer: ByteBuffer, header: DexHeader): List<String> {
        val strings = mutableListOf<String>()
        if (header.stringIdsSize == 0) return strings

        try {
            for (i in 0 until header.stringIdsSize) {
                buffer.position(header.stringIdsOff.toInt() + i * 4)
                val stringDataOff = buffer.int.toLong() and 0xFFFFFFFFL
                if (stringDataOff < buffer.limit().toLong()) {
                    buffer.position(stringDataOff.toInt())
                    // 读取 ULEB128 编码的字符串长度
                    val utf16Size = readULEB128(buffer)
                    val str = readMUTF8String(buffer)
                    strings.add(str)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[字符串] 解析异常(已获取 ${strings.size}): ${e.message}")
        }

        return strings
    }

    private fun parseTypes(buffer: ByteBuffer, header: DexHeader): List<String> {
        val types = mutableListOf<String>()
        if (header.typeIdsSize == 0) return types

        // 需要先获取字符串列表
        val strings = parseStrings(buffer, header)

        try {
            for (i in 0 until header.typeIdsSize) {
                buffer.position(header.typeIdsOff.toInt() + i * 4)
                val descriptorIdx = buffer.int
                if (descriptorIdx < strings.size) {
                    types.add(prettyTypeDescriptor(strings[descriptorIdx]))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[类型] 解析异常: ${e.message}")
        }

        return types
    }

    private fun parseMethodIds(buffer: ByteBuffer, header: DexHeader): List<DexMethodId> {
        val methods = mutableListOf<DexMethodId>()
        if (header.methodIdsSize == 0) return methods

        try {
            for (i in 0 until header.methodIdsSize) {
                buffer.position(header.methodIdsOff.toInt() + i * 8)
                val classIdx = buffer.short.toInt() and 0xFFFF
                val protoIdx = buffer.short.toInt() and 0xFFFF
                val nameIdx = buffer.int
                methods.add(DexMethodId(classIdx, protoIdx, nameIdx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[方法] 解析异常: ${e.message}")
        }

        return methods
    }

    private fun parseFieldIds(buffer: ByteBuffer, header: DexHeader): List<DexFieldId> {
        val fields = mutableListOf<DexFieldId>()
        if (header.fieldIdsSize == 0) return fields

        try {
            for (i in 0 until header.fieldIdsSize) {
                buffer.position(header.fieldIdsOff.toInt() + i * 8)
                val classIdx = buffer.short.toInt() and 0xFFFF
                val typeIdx = buffer.short.toInt() and 0xFFFF
                val nameIdx = buffer.int
                fields.add(DexFieldId(classIdx, typeIdx, nameIdx))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[字段] 解析异常: ${e.message}")
        }

        return fields
    }

    private fun parseClassDefs(buffer: ByteBuffer, header: DexHeader): List<DexClassDef> {
        val classes = mutableListOf<DexClassDef>()
        if (header.classDefsSize == 0) return classes

        try {
            for (i in 0 until header.classDefsSize) {
                buffer.position(header.classDefsOff.toInt() + i * 32)
                val classIdx = buffer.int
                val accessFlags = buffer.int
                val superclassIdx = buffer.int
                val interfacesOff = buffer.int.toLong() and 0xFFFFFFFFL
                val sourceFileIdx = buffer.int
                val annotationsOff = buffer.int.toLong() and 0xFFFFFFFFL
                val classDataOff = buffer.int.toLong() and 0xFFFFFFFFL
                val staticValuesOff = buffer.int.toLong() and 0xFFFFFFFFL

                classes.add(DexClassDef(
                    classIdx, accessFlags, superclassIdx, interfacesOff,
                    sourceFileIdx, annotationsOff, classDataOff, staticValuesOff
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[类] 解析异常: ${e.message}")
        }

        return classes
    }

    // ── 工具方法 ──

    private fun readDexString(buffer: ByteBuffer, offset: Int, length: Int): String {
        buffer.position(offset)
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes)
    }

    /**
     * 读取 ULEB128 编码的整数
     */
    private fun readULEB128(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        var byte: Int
        do {
            byte = buffer.get().toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        return result
    }

    /**
     * 读取 Modified UTF-8 字符串
     */
    private fun readMUTF8String(buffer: ByteBuffer): String {
        val sb = StringBuilder()
        try {
            while (buffer.hasRemaining()) {
                val b = buffer.get().toInt() and 0xFF
                if (b == 0) break  // null terminator
                when {
                    b < 0x80 -> sb.append(b.toChar())
                    b < 0xE0 -> {
                        val b2 = buffer.get().toInt() and 0xFF
                        sb.append(((b and 0x1F shl 6) or (b2 and 0x3F)).toChar())
                    }
                    else -> {
                        val b2 = buffer.get().toInt() and 0xFF
                        val b3 = buffer.get().toInt() and 0xFF
                        sb.append(((b and 0x0F shl 12) or (b2 and 0x3F shl 6) or (b3 and 0x3F)).toChar())
                    }
                }
            }
        } catch (e: Exception) {
            // 截断处理
        }
        return sb.toString()
    }

    /**
     * 美化类型描述符
     *
     * "Ljava/lang/String;" → "java.lang.String"
     * "I" → "int"
     * "[B" → "byte[]"
     */
    private fun prettyTypeDescriptor(descriptor: String): String {
        if (descriptor.isEmpty()) return descriptor

        return when {
            descriptor.startsWith("L") && descriptor.endsWith(";") -> {
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            }
            descriptor.startsWith("[") -> {
                prettyTypeDescriptor(descriptor.substring(1)) + "[]"
            }
            else -> when (descriptor) {
                "V" -> "void"
                "Z" -> "boolean"
                "B" -> "byte"
                "S" -> "short"
                "C" -> "char"
                "I" -> "int"
                "J" -> "long"
                "F" -> "float"
                "D" -> "double"
                else -> descriptor
            }
        }
    }
}

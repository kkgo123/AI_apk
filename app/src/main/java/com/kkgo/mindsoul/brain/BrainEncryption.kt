/*
 * ============================================================
 * BrainEncryption - .brain 文件加密引擎
 * ============================================================
 *
 * 使用 Android Keystore + AES-256-GCM 实现数据加密保护。
 * 
 * 加密策略：
 * - GUID身份数据：始终加密（最敏感）
 * - 神经元权重数据：始终加密（意识核心）
 * - 元认知状态：始终加密
 * - 因果三元组：可选加密
 * - 公理层数据：明文存储（频繁访问，性能优先）
 * - 世界模型：可选加密
 * 
 * 密钥管理：
 * - 使用 Android Keystore 硬件安全模块存储主密钥
 * - 主密钥永不离开硬件安全区域
 * - 每次加密生成独立IV，防止密文分析
 * ============================================================
 */
package com.kkgo.mindsoul.brain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * .brain 文件加密引擎
 * 
 * 提供基于 Android Keystore 的硬件级数据加密能力
 */
class BrainEncryption {
    
    companion object {
        private const val TAG = "BrainEncryption"
        
        /** Android Keystore 中的密钥别名 */
        private const val KEYSTORE_ALIAS = "mindsoul_brain_key"
        
        /** Keystore Provider */
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        
        /** AES-GCM 加密算法全名 */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        
        /** GCM 认证标签位数 */
        private const val GCM_TAG_BITS = 128
    }
    
    // 加密密钥引用
    private var secretKey: SecretKey? = null
    
    /**
     * 初始化加密引擎
     * 
     * 从 Android Keystore 加载或生成 AES-256 主密钥
     */
    fun initialize() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            
            // 检查密钥是否已存在
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                // 从 Keystore 加载已有密钥
                val entry = keyStore.getEntry(KEYSTORE_ALIAS, null)
                if (entry is KeyStore.SecretKeyEntry) {
                    secretKey = entry.secretKey
                    Log.d(TAG, "已从 Keystore 加载加密密钥")
                }
            } else {
                // 生成新的 AES-256 密钥
                secretKey = generateNewKey()
                Log.i(TAG, "已生成新的 AES-256 加密密钥")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加密引擎初始化失败: ${e.message}", e)
            throw RuntimeException("无法初始化.brain加密引擎", e)
        }
    }
    
    /**
     * 生成新的 AES-256 密钥并存储到 Android Keystore
     */
    private fun generateNewKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // 要求用户认证（可选，根据安全级别调整）
            // .setUserAuthenticationRequired(true)
            .build()
        
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
    
    /**
     * 加密数据块
     * 
     * 输出格式：[IV(12字节)] + [密文] + [GCM标签(16字节)]
     * 
     * @param plaintext 明文数据
     * @param aad 附加认证数据（可选，用于绑定数据上下文）
     * @return 加密后的数据（含IV和认证标签）
     */
    fun encrypt(plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val key = secretKey ?: throw IllegalStateException("加密引擎未初始化")
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        
        // 生成随机12字节IV
        val iv = ByteArray(BrainFileFormat.GCM_IV_SIZE)
        java.security.SecureRandom().nextBytes(iv)
        
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        // 设置附加认证数据（绑定块类型等上下文信息）
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        
        val ciphertext = cipher.doFinal(plaintext)
        
        // 组装：IV + 密文（含GCM标签）
        val result = ByteBuffer.allocate(iv.size + ciphertext.size)
        result.put(iv)
        result.put(ciphertext)
        
        return result.array()
    }
    
    /**
     * 解密数据块
     * 
     * 输入格式：[IV(12字节)] + [密文] + [GCM标签(16字节)]
     * 
     * @param encryptedData 加密数据
     * @param aad 附加认证数据（加密时使用的相同AAD）
     * @return 解密后的明文数据
     */
    fun decrypt(encryptedData: ByteArray, aad: ByteArray? = null): ByteArray {
        val key = secretKey ?: throw IllegalStateException("加密引擎未初始化")
        
        val buffer = ByteBuffer.wrap(encryptedData)
        
        // 提取IV
        val iv = ByteArray(BrainFileFormat.GCM_IV_SIZE)
        buffer.get(iv)
        
        // 提取密文（含GCM标签）
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * 判断某个块类型是否需要加密
     */
    fun shouldEncrypt(blockType: Int): Boolean {
        return when (blockType) {
            // 始终加密的块类型
            BrainFileFormat.BlockType.TYPE_GUID,
            BrainFileFormat.BlockType.TYPE_NEURON,
            BrainFileFormat.BlockType.TYPE_METACOGNITION -> true
            // 可选加密
            BrainFileFormat.BlockType.TYPE_CAUSAL,
            BrainFileFormat.BlockType.TYPE_WORLDMODEL,
            BrainFileFormat.BlockType.TYPE_MEMORY,
            BrainFileFormat.BlockType.TYPE_ASSOCIATION -> false
            // 公理层不加密（性能优先）
            BrainFileFormat.BlockType.TYPE_AXIOM,
            BrainFileFormat.BlockType.TYPE_HEBB -> false
            else -> false
        }
    }
    
    /**
     * 销毁密钥引用（安全清理）
     */
    fun destroy() {
        secretKey = null
        Log.d(TAG, "加密密钥引用已清除")
    }
}

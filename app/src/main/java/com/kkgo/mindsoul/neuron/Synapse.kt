/*
 * ============================================================
 * Synapse - 突触连接管理
 * ============================================================
 *
 * 模拟生物突触的连接管理：
 * 
 * 突触是神经元之间的信息传递通道，具有：
 * - 连接强度（权重）：决定信号传递效率
 * - 可塑性：权重可以根据赫布学习规则动态调整
 * - 延迟：信号传递存在时间延迟
 * 
 * 赫布学习规则（Hebbian Learning）：
 *   "一起激活的神经元连接在一起"
 *   Δw = η · pre_output · post_output
 * 
 * 其中：
 *   η = 学习率
 *   pre_output = 突触前神经元输出
 *   post_output = 突触后神经元输出
 * ============================================================
 */
package com.kkgo.mindsoul.neuron

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 突触连接
 * 
 * 表示两个神经元之间的有向连接
 */
class Synapse(
    /** 突触ID */
    val id: Int,
    /** 突触前神经元ID */
    val preNeuronId: Int,
    /** 突触后神经元ID */
    val postNeuronId: Int,
    /** 突触连接权重 */
    var weight: Double = 0.0
) : Serializable {
    
    companion object {
        private const val serialVersionUID = 1L
        
        /** 最大权重值（防止权重爆炸） */
        const val MAX_WEIGHT = 10.0
        /** 最小权重值 */
        const val MIN_WEIGHT = -10.0
    }
    
    /** 突触延迟（毫秒），模拟生物突触的信号传递延迟 */
    var delay: Long = 0L
    
    /** 信号传递队列（延迟缓冲） */
    private val signalBuffer = mutableListOf<Pair<Long, Double>>()
    
    /** 最近一次信号传递时间 */
    var lastTransmitTime: Long = 0L
    
    /** 突触使用频率（用于突触修剪） */
    var usageCount: Long = 0L
    
    /**
     * 传递信号
     * 
     * 将突触前神经元的输出传递给突触后神经元
     * 考虑突触延迟
     * 
     * @param signal 信号值
     * @param currentTime 当前时间戳
     * @return 经过延迟后到达的信号（可能为空）
     */
    fun transmit(signal: Double, currentTime: Long): Double? {
        usageCount++
        lastTransmitTime = currentTime
        
        // 将信号加入延迟缓冲
        signalBuffer.add(Pair(currentTime + delay, signal * weight))
        
        // 检查是否有信号到达
        val arrived = signalBuffer.filter { it.first <= currentTime }
        signalBuffer.removeAll(arrived.toSet())
        
        // 返回最早到达的信号（如果存在）
        return arrived.minByOrNull { it.first }?.second
    }
    
    /**
     * 赫布学习规则更新权重
     * 
     * Δw = η · pre · post
     * 
     * 当突触前和突触后神经元同时激活时，加强连接
     * 这模拟了"fire together, wire together"的生物学原理
     * 
     * @param preOutput 突触前神经元输出
     * @param postOutput 突触后神经元输出
     * @param learningRate 学习率 η
     */
    fun hebbianUpdate(preOutput: Double, postOutput: Double, learningRate: Double) {
        // 赫布学习规则：Δw = η · pre · post
        val delta = learningRate * preOutput * postOutput
        
        // 更新权重并裁剪
        weight = (weight + delta).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    }
    
    /**
     * Oja学习规则（赫布规则的归一化版本）
     * 
     * Δw = η · post · (pre - post · w)
     * 
     * Oja规则在赫布规则基础上增加权重衰减项，
     * 防止权重无限增长，实现稳定的在线学习
     * 
     * @param preOutput 突触前神经元输出
     * @param postOutput 突触后神经元输出
     * @param learningRate 学习率
     */
    fun ojaUpdate(preOutput: Double, postOutput: Double, learningRate: Double) {
        // Oja规则：Δw = η · post · (pre - post · w)
        val delta = learningRate * postOutput * (preOutput - postOutput * weight)
        weight = (weight + delta).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    }
    
    /**
     * STDP（脉冲时序依赖可塑性）简化版
     * 
     * 如果突触前神经元先于突触后激活 → 加强连接（因果）
     * 如果突触后神经元先于突触前激活 → 减弱连接（反因果）
     * 
     * Δw = η · exp(-|Δt|/τ) · sign(Δt)
     * 
     * @param preTime 突触前激活时间
     * @param postTime 突触后激活时间
     * @param learningRate 学习率
     * @param tau 时间常数（毫秒）
     */
    fun stdpUpdate(preTime: Long, postTime: Long, learningRate: Double, tau: Double = 20.0) {
        val dt = (postTime - preTime).toDouble()
        // Δw = η · exp(-|Δt|/τ) · sign(Δt)
        val sign = if (dt >= 0) 1.0 else -1.0
        val magnitude = Math.exp(-Math.abs(dt) / tau)
        val delta = learningRate * magnitude * sign
        
        weight = (weight + delta).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    }
    
    /**
     * 序列化突触数据
     */
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 4 + 4 + 8 + 8 + 8)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(id)
        buffer.putInt(preNeuronId)
        buffer.putInt(postNeuronId)
        buffer.putDouble(weight)
        buffer.putDouble(delay.toDouble())
        buffer.putLong(usageCount)
        return buffer.array()
    }
}

/**
 * 突触连接图
 * 
 * 管理所有突触连接，提供高效的查询和遍历
 */
class SynapseGraph {
    
    /** 所有突触的列表 */
    private val synapses = mutableListOf<Synapse>()
    
    /** 突触ID计数器 */
    private var nextId = 0
    
    /** 快速查找：突触前神经元ID → 出突触列表 */
    private val outSynapses = mutableMapOf<Int, MutableList<Synapse>>()
    
    /** 快速查找：突触后神经元ID → 入突触列表 */
    private val inSynapses = mutableMapOf<Int, MutableList<Synapse>>()
    
    /**
     * 创建新的突触连接
     */
    fun addSynapse(preNeuronId: Int, postNeuronId: Int, initialWeight: Double = 0.0): Synapse {
        val synapse = Synapse(
            id = nextId++,
            preNeuronId = preNeuronId,
            postNeuronId = postNeuronId,
            weight = initialWeight
        )
        synapses.add(synapse)
        
        // 更新索引
        outSynapses.getOrPut(preNeuronId) { mutableListOf() }.add(synapse)
        inSynapses.getOrPut(postNeuronId) { mutableListOf() }.add(synapse)
        
        return synapse
    }
    
    /**
     * 获取某个神经元的所有出突触
     */
    fun getOutSynapses(neuronId: Int): List<Synapse> {
        return outSynapses[neuronId] ?: emptyList()
    }
    
    /**
     * 获取某个神经元的所有入突触
     */
    fun getInSynapses(neuronId: Int): List<Synapse> {
        return inSynapses[neuronId] ?: emptyList()
    }
    
    /**
     * 获取所有突触
     */
    fun getAllSynapses(): List<Synapse> = synapses.toList()
    
    /**
     * 突触修剪：移除权重过小或长期不用的突触
     * 
     * 模拟生物大脑的突触修剪过程
     * 
     * @param weightThreshold 权重绝对值阈值
     * @param usageThreshold 使用次数阈值
     * @return 被移除的突触数量
     */
    fun prune(weightThreshold: Double = 0.01, usageThreshold: Long = 0): Int {
        val toRemove = synapses.filter { 
            Math.abs(it.weight) < weightThreshold || 
            (usageThreshold > 0 && it.usageCount < usageThreshold) 
        }
        
        for (synapse in toRemove) {
            synapses.remove(synapse)
            outSynapses[synapse.preNeuronId]?.remove(synapse)
            inSynapses[synapse.postNeuronId]?.remove(synapse)
        }
        
        return toRemove.size
    }
    
    /**
     * 获取突触总数
     */
    fun size(): Int = synapses.size
    
    /**
     * 序列化所有突触数据
     */
    fun serializeAll(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + synapses.size * 40) // 每个突触40字节
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(synapses.size)
        for (synapse in synapses) {
            buffer.put(synapse.serialize())
        }
        return buffer.array()
    }
}

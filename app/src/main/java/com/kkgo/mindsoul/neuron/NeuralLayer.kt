/*
 * ============================================================
 * NeuralLayer - 神经网络层
 * ============================================================
 *
 * 一层神经元集合，实现全连接层的前向传播。
 * 
 * 前向传播公式（矩阵形式）：
 *   Z = X · W^T + b     （加权求和）
 *   A = activation(Z)    （激活函数）
 * 
 * 其中：
 *   X = 输入向量 [1×n]
 *   W = 权重矩阵 [m×n]（m个神经元，每个有n个输入）
 *   b = 偏置向量 [1×m]
 *   Z = 加权输入 [1×m]
 *   A = 激活输出 [1×m]
 * ============================================================
 */
package com.kkgo.mindsoul.neuron

import java.io.Serializable

/**
 * 神经网络层
 * 
 * 包含一组同构神经元，实现一层的前向传播
 */
class NeuralLayer(
    /** 层序号（从0开始） */
    val layerIndex: Int,
    /** 本层神经元数量 */
    val neuronCount: Int,
    /** 输入维度（上一层神经元数量） */
    val inputDim: Int,
    /** 激活函数类型 */
    val activationType: ActivationType = ActivationType.SIGMOID
) : Serializable {
    
    companion object {
        private const val serialVersionUID = 1L
    }
    
    /** 本层所有神经元 */
    val neurons: Array<Neuron> = Array(neuronCount) { i ->
        Neuron(
            id = layerIndex * 10000 + i,  // 编码层级信息
            inputCount = inputDim,
            activationType = activationType
        )
    }
    
    /** 权重矩阵 W [neuronCount × inputDim] */
    var weightMatrix: Array<DoubleArray> = Array(neuronCount) { neurons[it].weights }
    
    /** 偏置向量 b [neuronCount] */
    var biasVector: DoubleArray = DoubleArray(neuronCount) { neurons[it].bias }
    
    /** 最近一次的加权输入 Z [neuronCount] */
    var lastZ: DoubleArray = DoubleArray(neuronCount)
    
    /** 最近一次的激活输出 A [neuronCount] */
    var lastOutput: DoubleArray = DoubleArray(neuronCount)
    
    /**
     * 前向传播
     * 
     * 对本层每个神经元执行：
     *   z_i = w_i · input + b_i
     *   a_i = activation(z_i)
     * 
     * @param input 输入向量
     * @return 本层输出向量
     */
    fun forward(input: DoubleArray): DoubleArray {
        require(input.size == inputDim) {
            "输入维度不匹配: 期望$inputDim, 实际${input.size}"
        }
        
        for (i in neurons.indices) {
            // 每个神经元独立计算
            lastOutput[i] = neurons[i].forward(input)
            lastZ[i] = neurons[i].lastZ
        }
        
        return lastOutput.copyOf()
    }
    
    /**
     * 计算本层的误差梯度（反向传播）
     * 
     * 公式：
     *   δ_i = (Σⱼ δⱼ_next · wⱼᵢ) ⊙ activation'(z_i)
     * 
     * 对于输出层：
     *   δ_i = (a_i - target_i) ⊙ activation'(z_i)
     * 
     * @param upstreamDelta 上游（更靠近输出层）的误差信号
     * @param nextLayerWeights 下一层的权重矩阵（如果存在）
     * @return 本层的误差信号 δ
     */
    fun computeDelta(
        upstreamDelta: DoubleArray?,
        nextLayerWeights: Array<DoubleArray>?
    ): DoubleArray {
        val delta = DoubleArray(neuronCount)
        
        if (upstreamDelta == null) {
            // 这是输出层（由外部提供误差信号的情况）
            // delta 直接由调用方设置
            return delta
        }
        
        // 反向传播误差
        for (i in 0 until neuronCount) {
            var error = 0.0
            
            // 累加来自下一层的误差信号
            if (nextLayerWeights != null) {
                for (j in upstreamDelta.indices) {
                    // w[j][i] 表示下一层第j个神经元对本层第i个神经元的连接权重
                    if (i < nextLayerWeights[j].size) {
                        error += upstreamDelta[j] * nextLayerWeights[j][i]
                    }
                }
            }
            
            // 乘以激活函数导数
            delta[i] = error * neurons[i].activationDerivative()
        }
        
        return delta
    }
    
    /**
     * 根据误差信号更新权重
     * 
     * 公式：
     *   w_ij = w_ij - η · δ_i · x_j
     *   b_i  = b_i - η · δ_i
     * 
     * @param delta 本层误差信号
     * @param input 本层输入
     * @param learningRate 学习率
     */
    fun updateWeights(delta: DoubleArray, input: DoubleArray, learningRate: Double) {
        for (i in neurons.indices) {
            neurons[i].updateWeights(delta[i], input, learningRate)
            // 同步权重矩阵引用
            weightMatrix[i] = neurons[i].weights
            biasVector[i] = neurons[i].bias
        }
    }
    
    /**
     * 序列化层数据
     */
    fun serialize(): ByteArray {
        // 格式：[神经元数量(4)] + 每个神经元的权重数据
        val neuronData = neurons.map { it.serializeWeights() }
        val totalSize = 4 + 4 + neuronData.sumOf { 4 + it.size } // 4 for size prefix per neuron
        
        val buffer = java.nio.ByteBuffer.allocate(totalSize)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(neuronCount)
        buffer.putInt(activationType.ordinal)
        for (data in neuronData) {
            buffer.putInt(data.size)
            buffer.put(data)
        }
        return buffer.array()
    }
}

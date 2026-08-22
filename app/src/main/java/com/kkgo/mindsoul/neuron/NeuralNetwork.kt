/*
 * ============================================================
 * NeuralNetwork - 多层前馈神经网络
 * ============================================================
 *
 * 完整的多层前馈神经网络实现：
 * 
 * 网络结构：
 *   输入层 → [隐藏层₁] → [隐藏层₂] → ... → 输出层
 * 
 * 前向传播：
 *   信号从输入层逐层向前传播，每层执行：
 *   a^(l) = activation(W^(l) · a^(l-1) + b^(l))
 * 
 * 反向传播（BP算法）：
 *   误差从输出层逐层向后传播，计算每层的梯度：
 *   δ^(L) = ∂L/∂z^(L) = (a^(L) - y) ⊙ f'(z^(L))
 *   δ^(l) = ((W^(l+1))^T · δ^(l+1)) ⊙ f'(z^(l))
 *   ∂L/∂W^(l) = δ^(l) · (a^(l-1))^T
 *   ∂L/∂b^(l) = δ^(l)
 * ============================================================
 */
package com.kkgo.mindsoul.neuron

import android.util.Log
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 多层前馈神经网络
 */
class NeuralNetwork(
    /** 网络名称 */
    val name: String,
    /** 网络层配置（包括输入维度） */
    val layerSizes: IntArray
) : Serializable {
    
    companion object {
        private const val TAG = "NeuralNetwork"
        private const val serialVersionUID = 1L
        
        /** 默认学习率 */
        const val DEFAULT_LEARNING_RATE = 0.01
    }
    
    /** 所有层（不包括输入层） */
    val layers: Array<NeuralLayer>
    
    /** 突触连接图 */
    val synapseGraph = SynapseGraph()
    
    /** 当前学习率 */
    var learningRate: Double = DEFAULT_LEARNING_RATE
    
    /** 总训练轮次 */
    var totalEpochs: Long = 0L
    
    /** 总训练样本数 */
    var totalSamples: Long = 0L
    
    /** 累计损失值 */
    var cumulativeLoss: Double = 0.0
    
    init {
        require(layerSizes.size >= 2) { "至少需要输入层和输出层" }
        
        // 创建隐藏层和输出层
        layers = Array(layerSizes.size - 1) { i ->
            val inputDim = layerSizes[i]
            val outputDim = layerSizes[i + 1]
            val activation = if (i == layerSizes.size - 2) {
                // 输出层使用Sigmoid（概率输出）
                ActivationType.SIGMOID
            } else {
                // 隐藏层使用ReLU（缓解梯度消失）
                ActivationType.RELU
            }
            NeuralLayer(
                layerIndex = i,
                neuronCount = outputDim,
                inputDim = inputDim,
                activationType = activation
            )
        }
        
        // 初始化突触连接
        initializeSynapses()
        
        Log.i(TAG, "神经网络[$name]已创建: 结构=${layerSizes.joinToString("→")}")
    }
    
    /**
     * 初始化突触连接图
     */
    private fun initializeSynapses() {
        for (l in 0 until layers.size - 1) {
            val currentLayer = layers[l]
            val nextLayer = layers[l + 1]
            
            for (i in currentLayer.neurons.indices) {
                for (j in nextLayer.neurons.indices) {
                    synapseGraph.addSynapse(
                        preNeuronId = currentLayer.neurons[i].id,
                        postNeuronId = nextLayer.neurons[j].id,
                        initialWeight = NeuralMath.gaussianRandom(0.0, 0.1)
                    )
                }
            }
        }
    }
    
    /**
     * 前向传播
     * 
     * 信号从输入层逐层传播到输出层
     * 
     * 对于每一层 l：
     *   z^(l) = W^(l) · a^(l-1) + b^(l)
     *   a^(l) = activation(z^(l))
     * 
     * @param input 输入向量（维度必须匹配网络输入维度）
     * @return 输出向量
     */
    fun forward(input: DoubleArray): DoubleArray {
        require(input.size == layerSizes[0]) {
            "输入维度不匹配: 期望${layerSizes[0]}, 实际${input.size}"
        }
        
        var current = input
        
        // 逐层前向传播
        for (layer in layers) {
            current = layer.forward(current)
        }
        
        return current
    }
    
    /**
     * 反向传播（BP算法）
     * 
     * 计算误差梯度并更新权重
     * 
     * 步骤：
     * 1. 前向传播得到预测值 ŷ
     * 2. 计算输出层误差 δ^(L) = (ŷ - y) ⊙ f'(z^(L))
     * 3. 逐层反向传播误差 δ^(l) = ((W^(l+1))^T · δ^(l+1)) ⊙ f'(z^(l))
     * 4. 更新权重 W^(l) = W^(l) - η · δ^(l) · (a^(l-1))^T
     * 
     * @param input 输入向量
     * @param target 目标输出
     * @return 本次训练的损失值（MSE）
     */
    fun train(input: DoubleArray, target: DoubleArray): Double {
        require(target.size == layerSizes.last()) {
            "目标维度不匹配: 期望${layerSizes.last()}, 实际${target.size}"
        }
        
        // 步骤1：前向传播，保存每层激活值
        val activations = mutableListOf<DoubleArray>()
        activations.add(input)
        
        var current = input
        for (layer in layers) {
            current = layer.forward(current)
            activations.add(current.copyOf())
        }
        
        val output = current
        
        // 步骤2：计算损失（均方误差 MSE）
        // L = (1/2n) Σᵢ(ŷᵢ - yᵢ)²
        var loss = 0.0
        val outputDelta = DoubleArray(output.size)
        for (i in output.indices) {
            val error = output[i] - target[i]
            loss += error * error
            // 输出层误差 = (ŷ - y) ⊙ f'(z)
            outputDelta[i] = error * layers.last().neurons[i].activationDerivative()
        }
        loss /= (2.0 * output.size)
        
        // 步骤3：反向传播误差
        var delta = outputDelta
        for (l in layers.indices.reversed()) {
            val layer = layers[l]
            val layerInput = activations[l]
            
            // 更新权重
            layer.updateWeights(delta, layerInput, learningRate)
            
            // 计算传递给上一层的误差信号
            if (l > 0) {
                val prevLayer = layers[l - 1]
                delta = layer.computeDelta(delta, null)
                // 简化版：直接计算上一层的delta
                delta = DoubleArray(prevLayer.neuronCount) { i ->
                    var error = 0.0
                    for (j in delta.indices) {
                        if (i < layer.weightMatrix[j].size) {
                            error += delta[j] * layer.weightMatrix[j][i]
                        }
                    }
                    error * prevLayer.neurons[i].activationDerivative()
                }
            }
        }
        
        // 更新统计
        totalSamples++
        cumulativeLoss += loss
        
        return loss
    }
    
    /**
     * 赫布学习（无监督）
     * 
     * 根据赫布规则更新突触权重：
     *   Δw = η · pre_output · post_output
     * 
     * 这是一种无监督学习方式，模拟"一起激活的神经元连接在一起"
     * 
     * @param input 输入信号
     * @param hebbRate 赫布学习率
     */
    fun hebbianLearn(input: DoubleArray, hebbRate: Double = 0.001) {
        // 前向传播
        val activations = mutableListOf(input)
        var current = input
        for (layer in layers) {
            current = layer.forward(current)
            activations.add(current.copyOf())
        }
        
        // 对相邻层应用赫布规则
        for (l in 0 until layers.size) {
            val preOutputs = activations[l]
            val postOutputs = activations[l + 1]
            
            for (i in preOutputs.indices) {
                for (j in postOutputs.indices) {
                    // 找到对应的突触
                    val preNeuronId = layers[l].neurons[i].id
                    val postNeuronId = layers[l + 1].neurons[j].id
                    val synapses = synapseGraph.getOutSynapses(preNeuronId)
                        .filter { it.postNeuronId == postNeuronId }
                    
                    for (synapse in synapses) {
                        synapse.hebbianUpdate(preOutputs[i], postOutputs[j], hebbRate)
                        // 同步权重到神经元
                        if (j < layers[l + 1].neurons[i].weights.size) {
                            // 更新权重矩阵
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 批量训练
     * 
     * @param inputs 输入样本集
     * @param targets 目标输出集
     * @param epochs 训练轮次
     */
    fun trainBatch(inputs: Array<DoubleArray>, targets: Array<DoubleArray>, epochs: Int = 1) {
        require(inputs.size == targets.size) { "输入输出样本数不匹配" }
        
        for (epoch in 0 until epochs) {
            var epochLoss = 0.0
            // 随机打乱顺序
            val indices = (inputs.indices).toMutableList().also { it.shuffle() }
            
            for (idx in indices) {
                epochLoss += train(inputs[idx], targets[idx])
            }
            
            totalEpochs++
            val avgLoss = epochLoss / inputs.size
            if (epoch % 10 == 0 || epoch == epochs - 1) {
                Log.d(TAG, "[$name] Epoch ${epoch + 1}/$epochs, 平均损失=${String.format("%.6f", avgLoss)}")
            }
        }
    }
    
    /**
     * 获取网络统计信息
     */
    fun getStats(): NetworkStats {
        val totalNeurons = layers.sumOf { it.neuronCount }
        val totalWeights = layers.sumOf { layer ->
            layer.neuronCount * layer.inputDim + layer.neuronCount  // 权重 + 偏置
        }
        val avgLoss = if (totalSamples > 0) cumulativeLoss / totalSamples else 0.0
        
        return NetworkStats(
            name = name,
            layerStructure = layerSizes.toList(),
            totalNeurons = totalNeurons,
            totalWeights = totalWeights,
            totalSynapses = synapseGraph.size(),
            totalEpochs = totalEpochs,
            totalSamples = totalSamples,
            averageLoss = avgLoss,
            learningRate = learningRate
        )
    }
    
    /**
     * 序列化整个网络（用于.brain文件存储）
     */
    fun serialize(): ByteArray {
        val layerData = layers.map { it.serialize() }
        val totalSize = 4 + 4 + layerData.sumOf { 4 + it.size }
        
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(layerSizes.size)
        for (size in layerSizes) {
            buffer.putInt(size)
        }
        for (data in layerData) {
            buffer.putInt(data.size)
            buffer.put(data)
        }
        return buffer.array()
    }
}

/**
 * 网络统计信息
 */
data class NetworkStats(
    val name: String,
    val layerStructure: List<Int>,
    val totalNeurons: Int,
    val totalWeights: Int,
    val totalSynapses: Int,
    val totalEpochs: Long,
    val totalSamples: Long,
    val averageLoss: Double,
    val learningRate: Double
)

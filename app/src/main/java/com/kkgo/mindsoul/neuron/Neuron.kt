/*
 * ============================================================
 * Neuron - 单个神经元实现
 * ============================================================
 *
 * 生物神经元的数学抽象：
 * 
 * 1. 接收多个输入信号（突触前神经元的输出）
 * 2. 对每个输入乘以权重（突触强度）
 * 3. 加权求和后加上偏置
 * 4. 通过激活函数产生输出
 * 
 * 数学模型：
 *   output = activation(Σᵢ(wᵢ · inputᵢ) + bias)
 * 
 * 其中：
 *   wᵢ = 第i个突触的权重（突触强度）
 *   inputᵢ = 第i个输入信号
 *   bias = 偏置项（神经元固有兴奋度）
 * ============================================================
 */
package com.kkgo.mindsoul.neuron

import java.io.Serializable

/**
 * 激活函数类型枚举
 */
enum class ActivationType {
    SIGMOID,    // σ(x) = 1/(1+e^(-x))  输出范围(0,1)
    RELU,       // f(x) = max(0,x)       输出范围[0,+∞)
    LEAKY_RELU, // f(x) = x>0?x:0.01x   输出范围(-∞,+∞)
    TANH        // tanh(x)                输出范围(-1,1)
}

/**
 * 单个神经元
 * 
 * 实现人工神经元的基本计算单元
 */
class Neuron(
    /** 神经元唯一ID */
    val id: Int,
    /** 输入连接数（突触数量） */
    val inputCount: Int,
    /** 激活函数类型 */
    val activationType: ActivationType = ActivationType.SIGMOID
) : Serializable {
    
    companion object {
        private const val serialVersionUID = 1L
    }
    
    /**
     * 权重向量 w = [w₀, w₁, ..., wₙ₋₁]
     * 
     * 每个权重对应一个突触连接的强度
     * 正值表示兴奋性突触，负值表示抑制性突触
     */
    var weights: DoubleArray = DoubleArray(inputCount)
    
    /**
     * 偏置值 b
     * 
     * 相当于神经元的固有兴奋度阈值
     * 偏置越大，神经元越容易被激活
     */
    var bias: Double = 0.0
    
    /** 最近一次计算的加权输入值（激活前） z = w·x + b */
    var lastZ: Double = 0.0
    
    /** 最近一次的输出值（激活后） a = activation(z) */
    var lastOutput: Double = 0.0
    
    /** 神经元当前激活水平 [0, 1]（用于可视化） */
    var activationLevel: Double = 0.0
    
    init {
        initializeWeights()
    }
    
    /**
     * 初始化权重
     * 
     * 根据激活函数类型选择合适的初始化策略：
     * - Sigmoid/Tanh → Xavier初始化
     * - ReLU → He初始化
     */
    private fun initializeWeights() {
        when (activationType) {
            ActivationType.RELU, ActivationType.LEAKY_RELU -> {
                // He初始化: W ~ N(0, √(2/n))
                val std = Math.sqrt(2.0 / inputCount)
                for (i in 0 until inputCount) {
                    weights[i] = NeuralMath.gaussianRandom(0.0, std)
                }
                bias = NeuralMath.gaussianRandom(0.0, 0.01)
            }
            else -> {
                // Xavier初始化: W ~ U(-√(6/(n+m)), √(6/(n+m)))
                val limit = Math.sqrt(6.0 / (inputCount + 1))
                for (i in 0 until inputCount) {
                    weights[i] = NeuralMath.random() * 2 * limit - limit
                }
                bias = 0.0
            }
        }
    }
    
    /**
     * 前向传播：计算神经元输出
     * 
     * 计算过程：
     *   1. z = w₀·x₀ + w₁·x₁ + ... + wₙ₋₁·xₙ₋₁ + b  （加权求和）
     *   2. a = activation(z)                             （激活函数）
     * 
     * @param inputs 输入向量 [x₀, x₁, ..., xₙ₋₁]
     * @return 神经元输出值
     */
    fun forward(inputs: DoubleArray): Double {
        require(inputs.size == inputCount) {
            "输入维度不匹配: 期望$inputCount, 实际${inputs.size}"
        }
        
        // 步骤1：加权求和 z = Σ(wᵢ·xᵢ) + b
        lastZ = NeuralMath.dotProduct(weights, inputs) + bias
        
        // 步骤2：激活函数 a = f(z)
        lastOutput = activate(lastZ)
        
        // 更新激活水平（用于可视化）
        activationLevel = when (activationType) {
            ActivationType.SIGMOID -> lastOutput  // 已经是[0,1]
            ActivationType.RELU, ActivationType.LEAKY_RELU -> {
                Math.min(lastOutput / 5.0, 1.0)  // 归一化到[0,1]
            }
            ActivationType.TANH -> (lastOutput + 1.0) / 2.0  // 从(-1,1)映射到(0,1)
        }
        
        return lastOutput
    }
    
    /**
     * 激活函数
     * 
     * @param z 加权输入值
     * @return 激活后的输出值
     */
    private fun activate(z: Double): Double {
        return when (activationType) {
            ActivationType.SIGMOID -> NeuralMath.sigmoid(z)
            ActivationType.RELU -> NeuralMath.relu(z)
            ActivationType.LEAKY_RELU -> NeuralMath.leakyRelu(z)
            ActivationType.TANH -> NeuralMath.tanh(z)
        }
    }
    
    /**
     * 计算激活函数的导数
     * 
     * 用于反向传播中的梯度计算
     * 
     * @return 激活函数在当前z值处的导数
     */
    fun activationDerivative(): Double {
        return when (activationType) {
            ActivationType.SIGMOID -> NeuralMath.sigmoidDerivative(lastOutput)
            ActivationType.RELU -> NeuralMath.reluDerivative(lastZ)
            ActivationType.LEAKY_RELU -> NeuralMath.leakyReluDerivative(lastZ)
            ActivationType.TANH -> NeuralMath.tanhDerivative(lastOutput)
        }
    }
    
    /**
     * 更新权重（梯度下降）
     * 
     * 公式：wᵢ = wᵢ - η · δ · xᵢ
     * 
     * 其中：
     *   η = 学习率
     *   δ = 误差信号（delta）
     *   xᵢ = 第i个输入值
     * 
     * @param delta 误差信号
     * @param inputs 输入向量
     * @param learningRate 学习率
     */
    fun updateWeights(delta: Double, inputs: DoubleArray, learningRate: Double) {
        for (i in weights.indices) {
            // 梯度下降：w = w - η·δ·x
            weights[i] -= learningRate * delta * inputs[i]
        }
        // 更新偏置
        bias -= learningRate * delta
    }
    
    /**
     * 序列化权重数据（用于.brain文件存储）
     */
    fun serializeWeights(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(8 + inputCount * 8 + 8)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(id)
        buffer.putInt(inputCount)
        buffer.putDouble(bias)
        for (w in weights) {
            buffer.putDouble(w)
        }
        return buffer.array()
    }
    
    /**
     * 反序列化权重数据
     */
    fun deserializeWeights(data: ByteArray) {
        val buffer = java.nio.ByteBuffer.wrap(data)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.getInt() // skip id
        buffer.getInt() // skip inputCount
        bias = buffer.getDouble()
        for (i in 0 until inputCount) {
            weights[i] = buffer.getDouble()
        }
    }
}

/*
 * ============================================================
 * NeuralMath - 纯手写原生数学运算基础库
 * ============================================================
 *
 * 这是 MindSoul 所有神经网络运算的数学基础。
 * 严禁使用任何第三方AI/ML库，所有数学运算纯手写实现。
 *
 * 包含：
 * - 向量/矩阵运算
 * - 激活函数（Sigmoid、ReLU、Tanh、Softmax）
 * - 随机数生成器（用于权重初始化）
 * - 数值稳定性工具
 * ============================================================
 */
package com.kkgo.mindsoul.neuron

import kotlin.math.*

/**
 * 神经网络数学基础工具集
 *
 * 所有函数均为纯数学实现，不依赖任何外部库
 */
object NeuralMath {
    
    // ============ 常量定义 ============
    /** 自然对数底 e */
    const val E = 2.718281828459045
    /** 圆周率 π */
    const val PI = 3.141592653589793
    /** 极小值，防止除零 */
    const val EPSILON = 1e-10
    /** 最大安全值，防止溢出 */
    const val MAX_SAFE = 500.0
    
    // ============ 伪随机数生成器 ============
    // 使用线性同余生成器（LCG），避免依赖java.util.Random
    // 参数: a=6364136223846793005, c=1442695040888963407, m=2^64
    private var rngState: Long = System.nanoTime()
    
    /**
     * 生成下一个伪随机数 [0, 1)
     * 使用 XorShift64 算法，周期 2^64-1
     */
    fun random(): Double {
        // XorShift64 算法
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        rngState = x
        // 映射到 [0, 1)
        return ((x ushr 11).toDouble()) / (1L shl 53).toDouble()
    }
    
    /**
     * 设置随机种子（可复现性）
     */
    fun setSeed(seed: Long) {
        rngState = if (seed == 0L) 1L else seed
    }
    
    /**
     * 高斯随机数（Box-Muller变换）
     * 
     * 数学公式：
     *   z₀ = √(-2·ln(u₁)) · cos(2π·u₂)
     *   z₁ = √(-2·ln(u₁)) · sin(2π·u₂)
     * 
     * 其中 u₁, u₂ 是均匀分布随机数
     * 
     * @param mean 均值，默认0
     * @param std 标准差，默认1
     */
    fun gaussianRandom(mean: Double = 0.0, std: Double = 1.0): Double {
        val u1 = max(random(), EPSILON)  // 防止ln(0)
        val u2 = random()
        val z0 = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return z0 * std + mean
    }
    
    // ============ 激活函数 ============
    
    /**
     * Sigmoid 激活函数
     * 
     * 公式：σ(x) = 1 / (1 + e^(-x))
     * 
     * 特性：
     * - 输出范围 (0, 1)
     * - 导数：σ'(x) = σ(x) · (1 - σ(x))
     * - 适用于概率输出和门控机制
     */
    fun sigmoid(x: Double): Double {
        // 数值稳定性：对大正/负值进行截断
        val cx = x.coerceIn(-MAX_SAFE, MAX_SAFE)
        return if (cx >= 0) {
            val expNeg = exp(-cx)
            1.0 / (1.0 + expNeg)
        } else {
            val expPos = exp(cx)
            expPos / (1.0 + expPos)
        }
    }
    
    /**
     * Sigmoid 导数
     * 
     * σ'(x) = σ(x) · (1 - σ(x))
     * 
     * @param sigmoidOutput 已经计算过的σ(x)值（避免重复计算）
     */
    fun sigmoidDerivative(sigmoidOutput: Double): Double {
        return sigmoidOutput * (1.0 - sigmoidOutput)
    }
    
    /**
     * ReLU 激活函数（修正线性单元）
     * 
     * 公式：f(x) = max(0, x)
     * 
     * 特性：
     * - 计算简单高效
     * - 导数：f'(x) = 1 if x > 0, else 0
     * - 存在"死亡ReLU"问题，用LeakyReLU缓解
     */
    fun relu(x: Double): Double {
        return max(0.0, x)
    }
    
    /**
     * ReLU 导数
     */
    fun reluDerivative(x: Double): Double {
        return if (x > 0) 1.0 else 0.0
    }
    
    /**
     * LeakyReLU 激活函数
     * 
     * 公式：f(x) = x if x > 0, else α·x
     * 
     * @param alpha 负半轴斜率，默认0.01
     */
    fun leakyRelu(x: Double, alpha: Double = 0.01): Double {
        return if (x > 0) x else alpha * x
    }
    
    /**
     * LeakyReLU 导数
     */
    fun leakyReluDerivative(x: Double, alpha: Double = 0.01): Double {
        return if (x > 0) 1.0 else alpha
    }
    
    /**
     * Tanh 双曲正切激活函数
     * 
     * 公式：tanh(x) = (e^x - e^(-x)) / (e^x + e^(-x))
     *              = 2·σ(2x) - 1
     * 
     * 特性：
     * - 输出范围 (-1, 1)
     * - 零均值，比Sigmoid更适合隐藏层
     * - 导数：tanh'(x) = 1 - tanh²(x)
     */
    fun tanh(x: Double): Double {
        return kotlin.math.tanh(x)
    }
    
    /**
     * Tanh 导数
     * 
     * tanh'(x) = 1 - tanh²(x)
     * 
     * @param tanhOutput 已经计算过的tanh(x)值
     */
    fun tanhDerivative(tanhOutput: Double): Double {
        return 1.0 - tanhOutput * tanhOutput
    }
    
    /**
     * Softmax 函数（向量化）
     * 
     * 公式：softmax(xᵢ) = e^(xᵢ) / Σⱼ e^(xⱼ)
     * 
     * 数值稳定版：先减去最大值
     * softmax(xᵢ) = e^(xᵢ - max(x)) / Σⱼ e^(xⱼ - max(x))
     * 
     * @param input 输入向量
     * @return 概率分布向量（和为1）
     */
    fun softmax(input: DoubleArray): DoubleArray {
        if (input.isEmpty()) return DoubleArray(0)
        
        // 减去最大值保证数值稳定性
        val maxVal = input.max()
        val exps = DoubleArray(input.size)
        var sumExp = 0.0
        
        for (i in input.indices) {
            exps[i] = exp(input[i] - maxVal)
            sumExp += exps[i]
        }
        
        // 归一化
        for (i in exps.indices) {
            exps[i] /= sumExp
        }
        
        return exps
    }
    
    // ============ 向量运算 ============
    
    /**
     * 向量点积（内积）
     * 
     * 公式：a·b = Σᵢ aᵢ·bᵢ
     * 
     * 这是神经网络中最基本的运算
     */
    fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "向量维度不匹配: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
    
    /**
     * 向量逐元素相乘（Hadamard积）
     * 
     * 公式：(a⊙b)ᵢ = aᵢ·bᵢ
     * 
     * 用于误差反向传播中的梯度计算
     */
    fun hadamardProduct(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "向量维度不匹配" }
        return DoubleArray(a.size) { a[it] * b[it] }
    }
    
    /**
     * 向量加法
     */
    fun vectorAdd(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "向量维度不匹配" }
        return DoubleArray(a.size) { a[it] + b[it] }
    }
    
    /**
     * 向量减法
     */
    fun vectorSubtract(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size) { "向量维度不匹配" }
        return DoubleArray(a.size) { a[it] - b[it] }
    }
    
    /**
     * 标量乘法
     */
    fun scalarMultiply(vector: DoubleArray, scalar: Double): DoubleArray {
        return DoubleArray(vector.size) { vector[it] * scalar }
    }
    
    /**
     * 向量L2范数（欧几里得长度）
     * 
     * ||v|| = √(Σᵢ vᵢ²)
     */
    fun l2Norm(vector: DoubleArray): Double {
        var sumSq = 0.0
        for (v in vector) {
            sumSq += v * v
        }
        return sqrt(sumSq)
    }
    
    /**
     * 向量L2归一化
     */
    fun l2Normalize(vector: DoubleArray): DoubleArray {
        val norm = l2Norm(vector)
        return if (norm > EPSILON) {
            DoubleArray(vector.size) { vector[it] / norm }
        } else {
            vector.copyOf()
        }
    }
    
    /**
     * 余弦相似度
     * 
     * cos(θ) = (a·b) / (||a|| · ||b||)
     * 
     * 用于衡量两个向量的方向相似性
     */
    fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        val dot = dotProduct(a, b)
        val normA = l2Norm(a)
        val normB = l2Norm(b)
        val denom = normA * normB
        return if (denom > EPSILON) dot / denom else 0.0
    }
    
    // ============ 矩阵运算 ============
    
    /**
     * 矩阵乘法
     * 
     * C[i][j] = Σₖ A[i][k] · B[k][j]
     * 
     * 这是全连接层的核心运算
     * 
     * @param a 矩阵A [m×n]
     * @param b 矩阵B [n×p]
     * @return 矩阵C [m×p]
     */
    fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val m = a.size
        val n = a[0].size
        val p = b[0].size
        require(b.size == n) { "矩阵维度不匹配: A[${m}x${n}] * B[${b.size}x${p}]" }
        
        val result = Array(m) { DoubleArray(p) }
        for (i in 0 until m) {
            for (k in 0 until n) {
                val aik = a[i][k]
                if (aik != 0.0) {  // 稀疏优化
                    for (j in 0 until p) {
                        result[i][j] += aik * b[k][j]
                    }
                }
            }
        }
        return result
    }
    
    /**
     * 矩阵转置
     */
    fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> {
        if (matrix.isEmpty()) return arrayOf()
        val rows = matrix.size
        val cols = matrix[0].size
        return Array(cols) { j -> DoubleArray(rows) { i -> matrix[i][j] } }
    }
    
    /**
     * 创建随机权重矩阵（Xavier/Glorot初始化）
     * 
     * Xavier初始化公式：
     *   W ~ U(-√(6/(n_in + n_out)), √(6/(n_in + n_out)))
     * 
     * 这种初始化方式可以保证前向和反向传播时信号方差一致
     * 
     * @param rows 行数（输出神经元数）
     * @param cols 列数（输入神经元数）
     */
    fun xavierInit(rows: Int, cols: Int): Array<DoubleArray> {
        val limit = sqrt(6.0 / (rows + cols))
        return Array(rows) { _ ->
            DoubleArray(cols) { _ ->
                random() * 2 * limit - limit  // 均匀分布 [-limit, limit]
            }
        }
    }
    
    /**
     * He初始化（适用于ReLU激活函数）
     * 
     * He初始化公式：
     *   W ~ N(0, √(2/n_in))
     * 
     * @param rows 行数（输出神经元数）
     * @param cols 列数（输入神经元数）
     */
    fun heInit(rows: Int, cols: Int): Array<DoubleArray> {
        val std = sqrt(2.0 / cols)
        return Array(rows) { _ ->
            DoubleArray(cols) { _ ->
                gaussianRandom(0.0, std)
            }
        }
    }
    
    /**
     * 欧几里得距离
     */
    fun euclideanDistance(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "向量维度不匹配" }
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}

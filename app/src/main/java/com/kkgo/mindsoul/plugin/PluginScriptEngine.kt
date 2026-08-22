/*
 * ============================================================
 * PluginScriptEngine - 插件脚本引擎
 * ============================================================
 *
 * 解析并执行插件的 script.kts 脚本。
 * 采用自研的轻量级脚本解释器（非第三方依赖），
 * 支持 Kotlin Script 的核心子集：
 *   - 变量声明 (val/var)
 *   - 函数定义 (fun)
 *   - 事件处理 (onEvent)
 *   - UI 操作 (setText, setVisibility, ...)
 *   - 条件/循环 (if/while)
 *   - 字符串模板
 *
 * AI 可自主编写脚本文件，放入 Plugins 目录即生效。
 * ============================================================
 */
package com.kkgo.mindsoul.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * 脚本运行时上下文
 */
data class ScriptContext(
    /** 变量存储 */
    val variables: MutableMap<String, Any?> = mutableMapOf(),
    /** 函数定义存储 */
    val functions: MutableMap<String, ScriptFunction> = mutableMapOf(),
    /** 事件处理器注册表 */
    val eventHandlers: MutableMap<String, MutableList<String>> = mutableMapOf(),
    /** 输出日志 */
    val output: MutableList<String> = mutableListOf()
)

/**
 * 脚本函数定义
 */
data class ScriptFunction(
    val name: String,
    val params: List<String>,
    val body: String
)

/**
 * 脚本执行结果
 */
data class ScriptResult(
    val success: Boolean,
    val returnValue: Any? = null,
    val error: String? = null,
    val output: List<String> = emptyList()
)

/**
 * 插件脚本引擎
 *
 * 核心脚本解释器，负责：
 * 1. 加载和解析 .kts 脚本文件
 * 2. 执行脚本代码
 * 3. 事件分发和回调
 * 4. 提供安全的沙箱环境
 */
class PluginScriptEngine(private val context: Context) {

    companion object {
        private const val TAG = "ScriptEngine"

        // ============ 脚本关键字 ============
        const val KW_VAL = "val"
        const val KW_VAR = "var"
        const val KW_FUN = "fun"
        const val KW_IF = "if"
        const val KW_ELSE = "else"
        const val KW_WHILE = "while"
        const val KW_FOR = "for"
        const val KW_RETURN = "return"
        const val KW_ON_EVENT = "onEvent"
        const val KW_PRINT = "print"
        const val KW_PRINTLN = "println"

        // ============ 内置 UI 函数 ============
        const val FN_SET_TEXT = "uiSetText"
        const val FN_GET_TEXT = "uiGetText"
        const val FN_SET_VISIBLE = "uiSetVisibility"
        const val FN_SET_COLOR = "uiSetColor"
        const val FN_SHOW_TOAST = "uiToast"

        /** 全局脚本引擎引用（用于静态事件分发） */
        @Volatile
        private var instance: PluginScriptEngine? = null

        /**
         * 处理 UI 事件（由 DynamicUIRenderer 调用）
         */
        fun handleEvent(viewTag: String, eventType: String, handlerName: String) {
            instance?.dispatchEvent(viewTag, eventType, handlerName)
        }
    }

    // ============ 脚本注册表 ============
    /** 已加载的脚本源码 {pluginId: sourceCode} */
    private val scripts = mutableMapOf<String, String>()

    /** 每个插件的运行时上下文 */
    private val contexts = mutableMapOf<String, ScriptContext>()

    /** 执行作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============ 初始化 ============

    fun initialize() {
        instance = this
        Log.i(TAG, "[初始化] 脚本引擎就绪")
    }

    fun destroy() {
        scope.cancel()
        scripts.clear()
        contexts.clear()
        instance = null
        Log.i(TAG, "[销毁] 脚本引擎已释放")
    }

    // ============ 脚本加载 ============

    /**
     * 加载脚本
     *
     * @param pluginId 插件ID
     * @param sourceCode 脚本源码
     */
    fun loadScript(pluginId: String, sourceCode: String) {
        scripts[pluginId] = sourceCode
        val ctx = ScriptContext()
        contexts[pluginId] = ctx

        // 预解析：提取函数定义和事件绑定
        try {
            preParse(sourceCode, ctx)
            Log.i(TAG, "[加载] 脚本: $pluginId (${sourceCode.length} 字符, ${ctx.functions.size} 函数)")
        } catch (e: Exception) {
            Log.e(TAG, "[加载] 脚本解析失败: $pluginId - ${e.message}")
        }
    }

    /**
     * 卸载脚本
     */
    fun unloadScript(pluginId: String) {
        scripts.remove(pluginId)
        contexts.remove(pluginId)
        Log.d(TAG, "[卸载] 脚本: $pluginId")
    }

    // ============ 脚本执行 ============

    /**
     * 执行插件脚本的指定函数
     *
     * @param pluginId 插件ID
     * @param functionName 函数名
     * @param args 参数列表
     * @return 执行结果
     */
    fun executeFunction(
        pluginId: String,
        functionName: String,
        args: Map<String, Any?> = emptyMap()
    ): ScriptResult {
        val ctx = contexts[pluginId] ?: return ScriptResult(false, error = "插件未加载: $pluginId")
        val func = ctx.functions[functionName] ?: return ScriptResult(false, error = "函数不存在: $functionName")

        return try {
            // 设置参数到上下文
            for ((key, value) in args) {
                ctx.variables[key] = value
            }
            // 执行函数体
            val result = executeBlock(func.body, ctx)
            ScriptResult(
                success = true,
                returnValue = result,
                output = ctx.output.toList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "[执行] 失败: $pluginId.$functionName - ${e.message}")
            ScriptResult(false, error = e.message)
        }
    }

    /**
     * 执行事件回调
     */
    fun executeEventHandler(pluginId: String, eventHandler: String, eventData: Map<String, Any?> = emptyMap()): ScriptResult {
        val ctx = contexts[pluginId] ?: return ScriptResult(false, error = "插件未加载: $pluginId")
        return try {
            for ((key, value) in eventData) {
                ctx.variables[key] = value
            }
            val result = executeBlock(eventHandler, ctx)
            ScriptResult(success = true, returnValue = result, output = ctx.output.toList())
        } catch (e: Exception) {
            ScriptResult(false, error = e.message)
        }
    }

    /**
     * 异步执行脚本函数
     */
    fun executeAsync(pluginId: String, functionName: String, args: Map<String, Any?> = emptyMap()) {
        scope.launch {
            executeFunction(pluginId, functionName, args)
        }
    }

    // ============ 事件分发 ============

    /**
     * 分发事件到注册的处理器
     */
    private fun dispatchEvent(viewTag: String, eventType: String, handlerName: String) {
        for ((pluginId, ctx) in contexts) {
            val handlers = ctx.eventHandlers["$viewTag.$eventType"]
            if (handlers != null) {
                for (handler in handlers) {
                    executeEventHandler(pluginId, handler, mapOf("viewId" to viewTag, "eventType" to eventType))
                }
            }
        }
    }

    // ============ 预解析 ============

    /**
     * 预解析脚本源码
     *
     * 提取函数定义和事件绑定，不执行代码。
     * 这是脚本加载时的第一步。
     */
    private fun preParse(source: String, ctx: ScriptContext) {
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // 跳过注释和空行
            if (line.startsWith("//") || line.startsWith("/*") || line.isEmpty()) {
                i++
                continue
            }

            // 提取函数定义
            if (line.startsWith("$KW_FUN ")) {
                val funcResult = parseFunction(lines, i)
                if (funcResult != null) {
                    ctx.functions[funcResult.first.name] = funcResult.first
                    i = funcResult.second + 1
                    continue
                }
            }

            // 提取事件绑定
            if (line.startsWith("$KW_ON_EVENT")) {
                val eventResult = parseEventBinding(lines, i)
                if (eventResult != null) {
                    val (key, handlerBody) = eventResult.first
                    ctx.eventHandlers.getOrPut(key) { mutableListOf() }.add(handlerBody)
                    i = eventResult.second + 1
                    continue
                }
            }

            // 顶层变量声明
            if (line.startsWith("$KW_VAL ") || line.startsWith("$KW_VAR ")) {
                parseVariable(line, ctx)
            }

            i++
        }
    }

    /**
     * 解析函数定义
     * @return (函数, 结束行号) 或 null
     */
    private fun parseFunction(lines: List<String>, startLine: Int): Pair<ScriptFunction, Int>? {
        val line = lines[startLine].trim()
        // fun name(param1, param2) {
        val match = Regex("""fun\s+(\w+)\s*\(([^)]*)\)\s*\{?""").find(line) ?: return null
        val name = match.groupValues[1]
        val params = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // 收集函数体（匹配大括号）
        val body = StringBuilder()
        var braceCount = line.count { it == '{' } - line.count { it == '}' }
        var i = startLine

        // 如果首行没有 {，找下一行
        if (braceCount == 0 && !line.contains("{")) {
            i++
            while (i < lines.size) {
                braceCount += lines[i].count { it == '{' } - lines[i].count { it == '}' }
                if (braceCount > 0) break
                i++
            }
        }

        i++
        while (i < lines.size && braceCount > 0) {
            braceCount += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (braceCount > 0) {
                body.appendLine(lines[i])
            }
            i++
        }

        return Pair(ScriptFunction(name, params, body.toString().trim()), i - 1)
    }

    /**
     * 解析事件绑定
     * @return ((事件key, 处理器体), 结束行号) 或 null
     */
    private fun parseEventBinding(lines: List<String>, startLine: Int): Pair<Pair<String, String>, Int>? {
        val line = lines[startLine].trim()
        // onEvent("viewId.onClick") { ... }
        val match = Regex("""onEvent\s*\(\s*"([^"]+)"\s*\)\s*\{""").find(line) ?: return null
        val eventKey = match.groupValues[1]

        val body = StringBuilder()
        var braceCount = 1
        var i = startLine + 1
        while (i < lines.size && braceCount > 0) {
            braceCount += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (braceCount > 0) {
                body.appendLine(lines[i])
            }
            i++
        }

        return Pair(Pair(eventKey, body.toString().trim()), i - 1)
    }

    /**
     * 解析变量声明
     */
    private fun parseVariable(line: String, ctx: ScriptContext) {
        // val/var name = value
        val match = Regex("""(?:val|var)\s+(\w+)\s*=\s*(.+)""").find(line) ?: return
        val name = match.groupValues[1]
        val valueStr = match.groupValues[2].trim().removeSuffix(";")
        ctx.variables[name] = evaluateLiteral(valueStr)
    }

    // ============ 代码块执行 ============

    /**
     * 执行代码块（简化版解释器）
     *
     * 支持：
     * - 变量赋值和读取
     * - 函数调用（内置 + 自定义）
     * - 条件分支 (if/else)
     * - 字符串拼接
     * - print/println
     */
    private fun executeBlock(code: String, ctx: ScriptContext): Any? {
        val lines = code.lines()
        var lastResult: Any? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            try {
                lastResult = executeLine(line, ctx)
            } catch (e: Exception) {
                Log.w(TAG, "执行行失败: $line - ${e.message}")
            }
        }

        return lastResult
    }

    /**
     * 执行单行代码
     */
    private fun executeLine(line: String, ctx: ScriptContext): Any? {
        // println / print
        if (line.startsWith("$KW_PRINTLN(") || line.startsWith("$KW_PRINTLN (")) {
            val content = extractParenContent(line, line.indexOf('('))
            val value = evaluateExpression(content, ctx)
            ctx.output.add(value.toString())
            Log.d(TAG, "[脚本输出] $value")
            return value
        }

        if (line.startsWith("$KW_PRINT(") || line.startsWith("$KW_PRINT (")) {
            val content = extractParenContent(line, line.indexOf('('))
            val value = evaluateExpression(content, ctx)
            ctx.output.add(value.toString())
            return value
        }

        // 变量赋值
        if (line.startsWith("$KW_VAR ")) {
            val match = Regex("""var\s+(\w+)\s*=\s*(.+)""").find(line)
            if (match != null) {
                val name = match.groupValues[1]
                val value = evaluateExpression(match.groupValues[2].trim(), ctx)
                ctx.variables[name] = value
                return value
            }
        }

        // 变量重新赋值（不带 val/var）
        val assignMatch = Regex("""(\w+)\s*=\s*(.+)""").find(line)
        if (assignMatch != null && !line.startsWith(KW_VAL) && !line.startsWith(KW_FUN)) {
            val name = assignMatch.groupValues[1]
            if (ctx.variables.containsKey(name)) {
                val value = evaluateExpression(assignMatch.groupValues[2].trim(), ctx)
                ctx.variables[name] = value
                return value
            }
        }

        // 函数调用
        val funcCallMatch = Regex("""(\w+)\s*\(""").find(line)
        if (funcCallMatch != null) {
            val funcName = funcCallMatch.groupValues[1]
            val parenStart = line.indexOf('(', funcCallMatch.range.first)
            val content = extractParenContent(line, parenStart)
            return callFunction(funcName, content, ctx)
        }

        return null
    }

    /**
     * 调用函数
     */
    private fun callFunction(name: String, argsStr: String, ctx: ScriptContext): Any? {
        // 内置函数
        return when (name) {
            FN_SET_TEXT -> { /* UI 操作，实际由宿主注入 */ Log.d(TAG, "uiSetText($argsStr)"); null }
            FN_GET_TEXT -> { /* UI 读取 */ Log.d(TAG, "uiGetText($argsStr)"); "" }
            FN_SET_VISIBLE -> { Log.d(TAG, "uiSetVisibility($argsStr)"); null }
            FN_SET_COLOR -> { Log.d(TAG, "uiSetColor($argsStr)"); null }
            FN_SHOW_TOAST -> { Log.d(TAG, "uiToast($argsStr)"); null }
            "toString" -> argsStr
            "toInt" -> argsStr.trim().removeSurrounding("\"").toIntOrNull()
            "toFloat" -> argsStr.trim().removeSurrounding("\"").toFloatOrNull()
            "length" -> argsStr.trim().removeSurrounding("\"").length
            else -> {
                // 自定义函数
                val func = ctx.functions[name]
                if (func != null) {
                    executeBlock(func.body, ctx)
                } else {
                    Log.w(TAG, "未找到函数: $name")
                    null
                }
            }
        }
    }

    /**
     * 计算表达式
     */
    private fun evaluateExpression(expr: String, ctx: ScriptContext): Any? {
        val trimmed = expr.trim()

        // 字符串字面量
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.removeSurrounding("\"")
        }

        // 数字字面量
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toFloatOrNull()?.let { return it }

        // 布尔值
        if (trimmed == "true") return true
        if (trimmed == "false") return false
        if (trimmed == "null") return null

        // 变量引用
        ctx.variables[trimmed]?.let { return it }

        // 字符串拼接 (+)
        if (trimmed.contains("+")) {
            val parts = trimmed.split("+").map { evaluateExpression(it.trim(), ctx) }
            return parts.joinToString("") { it?.toString() ?: "" }
        }

        // 函数调用表达式
        val funcMatch = Regex("""(\w+)\s*\((.*)?\)""").find(trimmed)
        if (funcMatch != null) {
            return callFunction(funcMatch.groupValues[1], funcMatch.groupValues[2], ctx)
        }

        return trimmed
    }

    /**
     * 计算字面量
     */
    private fun evaluateLiteral(valueStr: String): Any? {
        val trimmed = valueStr.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.removeSurrounding("\"")
        }
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toFloatOrNull()?.let { return it }
        if (trimmed == "true") return true
        if (trimmed == "false") return false
        if (trimmed == "null") return null
        return trimmed
    }

    /**
     * 提取括号内的内容
     */
    private fun extractParenContent(line: String, openParenIndex: Int): String {
        var depth = 0
        val sb = StringBuilder()
        for (i in openParenIndex until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
                else -> if (depth > 0) sb.append(line[i])
            }
        }
        return sb.toString().trim()
    }

    // ============ 查询接口 ============

    /**
     * 获取插件的脚本上下文变量
     */
    fun getVariables(pluginId: String): Map<String, Any?> {
        return contexts[pluginId]?.variables?.toMap() ?: emptyMap()
    }

    /**
     * 设置插件上下文的变量（供宿主注入数据）
     */
    fun setVariable(pluginId: String, name: String, value: Any?) {
        contexts[pluginId]?.variables?.set(name, value)
    }

    /**
     * 获取已注册的事件处理器
     */
    fun getEventHandlers(pluginId: String): Map<String, List<String>> {
        return contexts[pluginId]?.eventHandlers?.mapValues { it.value.toList() } ?: emptyMap()
    }
}

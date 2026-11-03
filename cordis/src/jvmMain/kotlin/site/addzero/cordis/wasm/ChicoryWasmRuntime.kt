package site.addzero.cordis.wasm

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.WasmFunctionHandle
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException
import java.nio.charset.StandardCharsets

/*
 * 基于 Chicory 的 WASM 运行时。
 * 选它的原因：纯 JVM 字节码实现，Android 也能直接跑，
 * 不需要为每个目标平台预编译一份 wasmtime 动态库。
 *
 * 安全边界：插件只能通过导入的 log / config 两个函数与宿主交互，
 * 拿不到文件系统、网络、时钟，也读不到宿主其他内存。
 */
public class ChicoryWasmRuntime : WasmRuntime {
    override suspend fun instantiate(artifact: ByteArray, host: WasmHost): WasmInstance {
        val module = parse(artifact)
        val imports = buildImports(host)
        val instance = instantiateModule(module, imports)
        return ChicoryInstance(instance)
    }

    private fun parse(artifact: ByteArray): WasmModule {
        return try {
            Parser.parse(artifact)
        } catch (reason: ChicoryException) {
            throw CordisException(
                CordisErrorCode.PluginLoadFailed,
                "artifact is not a valid wasm module",
                reason,
            )
        }
    }

    private fun instantiateModule(module: WasmModule, imports: ImportValues): Instance {
        return try {
            Instance.builder(module)
                .withImportValues(imports)
                .build()
        } catch (reason: ChicoryException) {
            throw CordisException(
                CordisErrorCode.PluginLoadFailed,
                "wasm module failed to instantiate: ${reason.message}",
                reason,
            )
        }
    }

    private fun buildImports(host: WasmHost): ImportValues {
        return ImportValues.builder()
            .addFunction(logFunction(host))
            .addFunction(configFunction(host))
            .build()
    }

    /*
     * log(level: i32, ptr: i32, len: i32)
     */
    private fun logFunction(host: WasmHost): HostFunction {
        val signature = FunctionType.of(
            listOf(ValType.I32, ValType.I32, ValType.I32),
            emptyList(),
        )
        val handle = WasmFunctionHandle { instance, args ->
            val level = args[0].toInt()
            val message = readString(instance, args[1].toInt(), args[2].toInt())
            host.log(level, message)
            NO_RESULTS
        }
        return HostFunction(PluginAbi.IMPORT_MODULE, PluginAbi.IMPORT_LOG, signature, handle)
    }

    /*
     * config(keyPtr: i32, keyLen: i32, outPtr: i32, outCap: i32) -> i32
     */
    private fun configFunction(host: WasmHost): HostFunction {
        val signature = FunctionType.of(
            listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
            listOf(ValType.I32),
        )
        val handle = WasmFunctionHandle { instance, args ->
            val key = readString(instance, args[0].toInt(), args[1].toInt())
            val outPtr = args[2].toInt()
            val outCap = args[3].toInt()
            longArrayOf(writeConfig(instance, host, key, outPtr, outCap).toLong())
        }
        return HostFunction(PluginAbi.IMPORT_MODULE, PluginAbi.IMPORT_CONFIG, signature, handle)
    }

    /*
     * 从插件线性内存读字符串。
     * 长度由插件给出，必须先校验：不校验就等于把宿主的堆布局暴露给制品。
     */
    private fun readString(instance: Instance, ptr: Int, len: Int): String {
        if (len < 0 || len > PluginAbi.MAX_STRING_BYTES) {
            throw CordisException(
                CordisErrorCode.PluginLoadFailed,
                "wasm plugin requested a string of $len bytes, limit is ${PluginAbi.MAX_STRING_BYTES}",
            )
        }
        if (ptr < 0) {
            throw CordisException(
                CordisErrorCode.PluginLoadFailed,
                "wasm plugin passed negative string pointer $ptr",
            )
        }
        return instance.memory().readString(ptr, len)
    }

    /*
     * 把配置值写进插件提供的缓冲区。
     * 三个返回码区分「没有」「放不下」「已写入」，插件据此决定是否重试。
     */
    private fun writeConfig(
        instance: Instance,
        host: WasmHost,
        key: String,
        outPtr: Int,
        outCap: Int,
    ): Int {
        val value = host.config(key) ?: return PluginAbi.CONFIG_MISSING
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (outCap < 0 || bytes.size > outCap) {
            return PluginAbi.CONFIG_BUFFER_TOO_SMALL
        }
        instance.memory().write(outPtr, bytes)
        return bytes.size
    }

    private companion object {
        val NO_RESULTS = longArrayOf()
    }
}

/*
 * 绑定到 Chicory 实例的插件视图。
 * 所有导出函数都先查导出表再调用，缺失时按「未实现」处理而非抛异常，
 * 这样可选导出（如 stop）能让插件只实现必需的那部分。
 */
private class ChicoryInstance(private val instance: Instance) : WasmInstance {
    override fun abiVersion(): Int {
        val result = call(PluginAbi.EXPORT_ABI_VERSION) ?: return ABIVersion.MISSING
        return if (result.isEmpty()) ABIVersion.MISSING else result[0].toInt()
    }

    override fun pluginName(): String = callString(PluginAbi.EXPORT_PLUGIN_NAME)

    override fun inject(): List<String> {
        val raw = callString(PluginAbi.EXPORT_INJECT)
        if (raw.isEmpty()) {
            return emptyList()
        }
        return raw.split(PluginAbi.INJECT_SEPARATOR).filter { it.isNotBlank() }
    }

    override fun start(): Int = callLifecycle(PluginAbi.EXPORT_START)

    override fun stop(): Int = callLifecycle(PluginAbi.EXPORT_STOP)

    private fun callString(export: String): String {
        val result = call(export) ?: return ""
        if (result.isEmpty()) {
            return ""
        }
        return readCString(result[0].toInt())
    }

    private fun callLifecycle(export: String): Int {
        val result = call(export) ?: return LIFECYCLE_NOT_IMPLEMENTED
        return if (result.isEmpty()) 0 else result[0].toInt()
    }

    /*
     * 调用一个导出函数。
     * 返回 null 表示制品没有导出这个函数。
     */
    private fun call(export: String): LongArray? {
        if (!exportsFunction(export)) {
            return null
        }
        return instance.exports().function(export).apply()
    }

    private fun exportsFunction(name: String): Boolean {
        val section = instance.module().exportSection()
        return (0 until section.exportCount()).any { index ->
            val export = section.getExport(index)
            export.name() == name && export.exportType() == ExternalType.FUNCTION
        }
    }

    /*
     * 读零结尾字符串。
     * 逐字节读到 0 为止，并用 MAX_STRING_BYTES 兜底，
     * 避免制品返回一个没有终止符的指针让宿主死循环。
     */
    private fun readCString(ptr: Int): String {
        // 0 是合法的线性内存地址，插件把字符串放在段首是常见做法，
        // 只有负数才是无效指针。
        if (ptr < 0) {
            return ""
        }
        val memory = instance.memory()
        val bytes = ByteArray(PluginAbi.MAX_STRING_BYTES)
        var size = 0
        while (size < PluginAbi.MAX_STRING_BYTES) {
            val byte = memory.read(ptr + size)
            if (byte == TERMINATOR) {
                return String(bytes, 0, size, StandardCharsets.UTF_8)
            }
            bytes[size] = byte
            size++
        }
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin returned an unterminated string at $ptr",
        )
    }

    private companion object {
        val TERMINATOR: Byte = 0

        /*
         * start/stop 未导出视为「无事可做」。
         * 与「插件报错」区分开，避免把可选实现当成失败。
         */
        const val LIFECYCLE_NOT_IMPLEMENTED = 0
    }
}

/*
 * ABI 版本的哨兵值。
 */
internal object ABIVersion {
    const val MISSING: Int = -1
}

package site.addzero.cordis.wasm

import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/*
 * JS / 浏览器 / Node 平台的 WASM 运行时，直接用引擎内置的 WebAssembly。
 *
 * 这是“一次编写、全平台装载”的关键一环：
 * 同一份 .wasm 制品，JVM 走 Chicory、浏览器走原生引擎，插件代码无需改动。
 */
public class JsWasmRuntime : WasmRuntime {
    override suspend fun instantiate(artifact: ByteArray, host: WasmHost): WasmInstance {
        // 导入函数必须在实例化之前交给引擎，但那时还没有实例、也就没有内存。
        // 用一个可回填的持有者把两者的生命周期接上。
        val memory = MemoryHolder()
        val imports = buildImports(host, memory)

        val module = await({ WASM.compile(toUint8Array(artifact)) }, INVALID_MODULE)
        val instance = await({ WASM.instantiate(module, imports) }, INSTANTIATE_FAILED)

        val exported = instance.exports.memory
        if (jsTypeOf(exported) != "object" || exported == null) {
            throw CordisException(
                CordisErrorCode.PluginAbiInvalid,
                "wasm plugin does not export memory, which the cordis abi requires",
            )
        }
        memory.value = exported
        return JsWasmInstance(instance, memory)
    }

    /*
     * 构造导入对象。
     * 字符串按“指针 + 长度”从线性内存读取，与 JVM 侧保持完全一致。
     */
    private fun buildImports(host: WasmHost, memory: MemoryHolder): dynamic {
        val cordis = js("({})")
        cordis.log = { level: Int, ptr: Int, len: Int ->
            host.log(level, readString(memory, ptr, len))
            undefined
        }
        cordis.config = { keyPtr: Int, keyLen: Int, outPtr: Int, outCap: Int ->
            val key = readString(memory, keyPtr, keyLen)
            val value = host.config(key)
            if (value == null) {
                PluginAbi.CONFIG_MISSING
            } else {
                val bytes = value.encodeToByteArray()
                if (bytes.size > outCap) {
                    PluginAbi.CONFIG_BUFFER_TOO_SMALL
                } else {
                    writeBytes(memory, outPtr, bytes)
                    bytes.size
                }
            }
        }
        val imports = js("({})")
        imports[PluginAbi.IMPORT_MODULE] = cordis
        return imports
    }

    /*
     * 挂起等待 Promise 完成。
     * 浏览器主线程上不能阻塞等待，因此必须走这个路径。
     */
    private suspend fun await(launch: () -> dynamic, failure: String): dynamic {
        val attempt = try {
            launch()
        } catch (reason: Throwable) {
            throw CordisException(CordisErrorCode.PluginLoadFailed, failure, reason)
        }
        return suspendCoroutine { continuation ->
            attempt.then(
                { value: dynamic -> continuation.resume(value) },
                { reason: dynamic ->
                    continuation.resumeWithException(
                        CordisException(CordisErrorCode.PluginLoadFailed, failure + ": " + reason),
                    )
                },
            )
        }
    }

    private companion object {
        const val INVALID_MODULE = "artifact is not a valid wasm module"
        const val INSTANTIATE_FAILED = "wasm module failed to instantiate"
    }
}

/*
 * 引擎内置的 WebAssembly。
 * Kotlin/JS 标准库不自带这个全局的声明，用 js() 直接取。
 */
private val WASM: dynamic = js("WebAssembly")

/*
 * ByteArray -> Uint8Array。
 * 手工拷贝，不依赖不同 Kotlin 版本里可能改名的标准库扩展。
 */
private val TO_UINT8: (ByteArray) -> dynamic = js("(function(a){ var u = new Uint8Array(a.length); for (var i = 0; i < a.length; i++) { u[i] = a[i]; } return u; })")

private fun toUint8Array(bytes: ByteArray): dynamic = TO_UINT8(bytes)

/*
 * 每次读内存都新建一个 Uint8Array 视图。
 * 不缓存视图的原因：内存增长后旧的 ArrayBuffer 会失效，
 * 缓存下来的视图会读到已分离的缓冲区并抛错。
 */
private val MEMORY_VIEW: (dynamic) -> dynamic = js("(function(memory){ return new Uint8Array(memory.buffer); })")

/*
 * 延迟绑定的线性内存。
 */
internal class MemoryHolder {
    var value: dynamic = null

    fun view(): dynamic {
        val bound = value ?: throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin called into the host before its memory was bound",
        )
        return MEMORY_VIEW(bound)
    }
}

/*
 * 从插件线性内存读字符串，长度由插件给出必须先校验。
 */
internal fun readString(memory: MemoryHolder, ptr: Int, len: Int): String {
    val sane = requireSaneLength(len)
    if (ptr < 0) {
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin passed negative string pointer " + ptr,
        )
    }
    if (sane == 0) {
        return ""
    }
    val view = memory.view()
    val bytes = ByteArray(sane)
    for (index in 0 until sane) {
        bytes[index] = (view[ptr + index] as Number).toByte()
    }
    return bytes.decodeToString()
}

/*
 * 把字节写进插件提供的缓冲区。
 */
internal fun writeBytes(memory: MemoryHolder, ptr: Int, bytes: ByteArray) {
    if (ptr < 0) {
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin passed negative buffer pointer " + ptr,
        )
    }
    memory.view().set(toUint8Array(bytes), ptr)
}

/*
 * 把一个已实例化的 JS WebAssembly.Instance 包成跨平台接口。
 * 导出函数通过 exports 直接查表，缺失时返回“未实现”而不是抛错。
 */
internal class JsWasmInstance(
    private val instance: dynamic,
    private val memory: MemoryHolder,
) : WasmInstance {

    override fun abiVersion(): Int {
        val callable = exported(PluginAbi.EXPORT_ABI_VERSION) ?: return -1
        return (callable() as Number).toInt()
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
        val callable = exported(export) ?: return ""
        val pointer = (callable() as Number).toInt()
        if (pointer < 0) {
            return ""
        }
        return readCString(pointer)
    }

    /*
     * start/stop 未导出视为“无事可做”，
     * 与“插件主动返回失败码”区分开。
     */
    private fun callLifecycle(export: String): Int {
        val callable = exported(export) ?: return 0
        return (callable() as Number).toInt()
    }

    private fun exported(name: String): dynamic {
        val value = instance.exports[name]
        return if (jsTypeOf(value) == "function") value else null
    }

    /*
     * 读零结尾字符串，用 MAX_STRING_BYTES 兜底防止无终止符的指针拖死宿主。
     */
    private fun readCString(pointer: Int): String {
        if (pointer < 0) {
            return ""
        }
        val view = memory.view()
        val bytes = ByteArray(PluginAbi.MAX_STRING_BYTES)
        var size = 0
        while (size < PluginAbi.MAX_STRING_BYTES) {
            val byte = (view[pointer + size] as Number).toByte()
            if (byte == 0.toByte()) {
                return bytes.decodeToString(0, size)
            }
            bytes[size] = byte
            size++
        }
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin returned an unterminated string at " + pointer,
        )
    }
}

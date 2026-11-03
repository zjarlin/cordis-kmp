package site.addzero.cordis.wasm

import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException

/*
 * WASM 插件运行时抽象。
 *
 * 为什么 instantiate 是 suspend：
 * 浏览器与 Node 的原生 WebAssembly 装载返回 Promise，是异步的；
 * JVM 的 Chicory 是同步的。把接口定为 suspend 让同步实现零成本，
 * 同时不逼异步平台去做阻塞等待（浏览器主线程上会直接死锁）。
 */
public interface WasmRuntime {
    /*
     * 装载一个制品。
     * 失败必须抛出 CordisException，不允许返回半可用实例。
     */
    public suspend fun instantiate(artifact: ByteArray, host: WasmHost): WasmInstance
}

/*
 * 宿主能力，插件通过导入函数回调到这里。
 * 只暴露最小面：日志与配置。给插件更多权限就意味着更大的逃逸面。
 */
public interface WasmHost {
    /*
     * 插件写日志。level 沿用 WASI 习惯：0=debug 1=info 2=warn 3=error。
     */
    public fun log(level: Int, message: String)

    /*
     * 插件读配置，缺失返回 null。
     */
    public fun config(key: String): String?
}

/*
 * 一个已实例化的 WASM 插件。
 */
public interface WasmInstance {
    /*
     * ABI 版本。制品没有导出该函数时返回 -1。
     */
    public fun abiVersion(): Int

    /*
     * 插件名。未导出返回空串。
     */
    public fun pluginName(): String

    /*
     * 声明依赖的服务名列表。未导出返回空列表。
     */
    public fun inject(): List<String>

    /*
     * 启动插件。返回 0 表示成功。
     */
    public fun start(): Int

    /*
     * 停止插件。返回 0 表示成功。
     */
    public fun stop(): Int
}

/*
 * 校验 ABI 版本，不匹配直接拒绝。
 * 宁可装载失败，也不要让旧插件在新宿主上跑出未定义行为。
 */
internal fun requireSupportedAbi(version: Int, pluginId: String) {
    if (version == PluginAbi.VERSION) {
        return
    }
    val detail = if (version < 0) {
        "does not export ${PluginAbi.EXPORT_ABI_VERSION}"
    } else {
        "declares abi $version, host expects ${PluginAbi.VERSION}"
    }
    throw CordisException(
        CordisErrorCode.PluginAbiInvalid,
        "wasm plugin \"$pluginId\" $detail",
    )
}

/*
 * 长度越界的统一报错。
 * 插件给出的长度与指针都不可信，越界必须在访问内存之前拦下。
 */
internal fun requireSaneLength(len: Int): Int {
    if (len < 0 || len > PluginAbi.MAX_STRING_BYTES) {
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "wasm plugin requested a string of $len bytes, limit is ${PluginAbi.MAX_STRING_BYTES}",
        )
    }
    return len
}

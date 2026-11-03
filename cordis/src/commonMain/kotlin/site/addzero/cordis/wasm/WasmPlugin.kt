package site.addzero.cordis.wasm

import site.addzero.cordis.Context
import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException
import site.addzero.cordis.Plugin
import site.addzero.cordis.ServiceKeyRegistry
import site.addzero.cordis.plugin

/*
 * 把 WASM 制品适配成 cordis 插件。
 *
 * 关键点：适配之后它就是一个普通 Plugin，参与同一套依赖解析与级联卸载。
 * 宿主不需要为「WASM 插件」再写一套生命周期管理，这是本设计的核心收益。
 */
public class WasmPluginLoader(
    private val runtime: WasmRuntime,
    public val keys: ServiceKeyRegistry = ServiceKeyRegistry(),
    public val config: Map<String, String> = emptyMap(),
    /*
     * 插件日志出口。
     * 由宿主接进自己的日志体系；默认打到标准输出，与 CordisLogger 的默认行为一致。
     */
    private val logSink: (level: Int, message: String) -> Unit = { level, message ->
        println("[wasm/${LogLevelName.of(level)}] $message")
    },
) {
    /*
     * 装载制品。
     * 制品在此刻实例化并校验 ABI，错误尽早暴露；
     * 插件体的实际执行发生在依赖就绪之后。
     */
    public suspend fun load(artifact: ByteArray, displayName: String? = null): Plugin<Unit> {
        val instance = runtime.instantiate(artifact, host())
        val name = displayName ?: instance.pluginName().ifBlank { "wasm" }
        requireSupportedAbi(instance.abiVersion(), name)

        val declared = instance.inject()
            .filter { it.isNotBlank() }
            .map { keys.key<Any>(it) }
            .toSet()

        return plugin(name = name, inject = declared) {
            start(instance, name)
        }
    }

    /*
     * 启动插件并登记停止逻辑。
     * start 非 0 时抛异常，让 Fiber 进入 Failed 并回滚已登记的服务，
     * 而不是留下一个「半启动」的插件。
     */
    private suspend fun Context.start(instance: WasmInstance, name: String) {
        val code = instance.start()
        if (code != 0) {
            throw CordisException(
                CordisErrorCode.PluginLoadFailed,
                "wasm plugin \"$name\" failed to start with code $code",
            )
        }
        onDispose("wasm/stop") {
            instance.stop()
        }
    }

    /*
     * 宿主能力门面。
     * 只转发日志与配置，插件拿不到文件系统、网络与时钟。
     */
    private fun host(): WasmHost {
        return object : WasmHost {
            override fun log(level: Int, message: String) {
                logSink(level, message)
            }

            override fun config(key: String): String? = this@WasmPluginLoader.config[key]
        }
    }
}

/*
 * 数字日志级别到名字。
 * 与 PluginAbi 的约定一致：0=debug 1=info 2=warn 3=error。
 */
internal object LogLevelName {
    fun of(level: Int): String = when (level) {
        0 -> "Debug"
        1 -> "Info"
        2 -> "Warn"
        3 -> "Error"
        else -> "Level$level"
    }
}

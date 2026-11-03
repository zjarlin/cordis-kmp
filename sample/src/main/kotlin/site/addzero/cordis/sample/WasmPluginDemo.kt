package site.addzero.cordis.sample

import com.dylibso.chicory.wabt.Wat2Wasm
import kotlinx.coroutines.runBlocking
import site.addzero.cordis.CordisLogger
import site.addzero.cordis.Engine
import site.addzero.cordis.LogLevel
import site.addzero.cordis.plugin
import site.addzero.cordis.wasm.ChicoryWasmRuntime
import site.addzero.cordis.wasm.WasmPluginLoader
import site.addzero.cordis.wasm.WasmRuntime

/*
 * 运行时插件演示。
 *
 * 与 Main.kt 的区别：这里插件是**外部二进制制品**，不是编译进宿主的类。
 * 宿主代码完全不知道 GreeterWasm 里有什么，只按 ABI 调用。
 * 换成磁盘上的任意 .wasm 文件，这段宿主代码一行都不用改。
 */
fun main() = runBlocking {
    val engine = Engine(CordisLogger(threshold = LogLevel.Debug))
    val runtime: WasmRuntime = ChicoryWasmRuntime()

    // 宿主登记本次运行的服务名，插件通过名字取键，两侧不会拼写漂移
    val loader = WasmPluginLoader(
        runtime = runtime,
        config = mapOf("prefix" to "runtime"),
    )

    println("== 1. 装载外部 wasm 制品（宿主不重新编译） ==")
    val plugin = loader.load(wat(WASM_PLUGIN))
    val fiber = engine.root.plugin(plugin)
    println("  wasm 插件状态 = ${fiber.state}（缺少 counter 依赖，停在 Pending）")

    println("== 2. 装上它依赖的服务 ==")
    val counter = loader.keys.key<Any>("counter")
    engine.root.plugin(plugin("counter") {
        provide(counter, CounterService(7))
    })
    println("  wasm 插件状态 = ${fiber.state}")

    println("== 3. 卸载依赖，wasm 插件跟着退回 Pending ==")
    engine.dispose()
    println("  fiber 数量 = ${engine.fiberCount}")
}

/*
 * 演示用的宿主服务，被 wasm 插件声明为依赖。
 */
private class CounterService(val value: Int)

private fun wat(source: String): ByteArray = Wat2Wasm.parse(source)

/*
 * 一个真实形态的 wasm 插件：导出 ABI 元信息与生命周期函数，
 * 启动时通过 log 导入向宿主汇报。
 */
private val WASM_PLUGIN = """
    (module
      (import "cordis" "log" (func ${'$'}log (param i32 i32 i32)))

      (memory (export "memory") 1)
      (data (i32.const 0) "greeter\00")
      (data (i32.const 64) "counter\00")
      (data (i32.const 128) "greeter started via wasm\00")

      (func (export "cordis_abi_version") (result i32)
        i32.const 1)

      (func (export "cordis_plugin_name") (result i32)
        i32.const 0)

      (func (export "cordis_inject") (result i32)
        i32.const 64)

      (func (export "cordis_start") (result i32)
        i32.const 1
        i32.const 128
        i32.const 24
        call ${'$'}log
        i32.const 0)

      (func (export "cordis_stop") (result i32)
        i32.const 0)
    )
""".trimIndent()

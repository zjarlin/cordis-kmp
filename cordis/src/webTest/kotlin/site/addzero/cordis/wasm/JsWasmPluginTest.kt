package site.addzero.cordis.wasm

import kotlinx.coroutines.test.runTest
import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException
import site.addzero.cordis.CordisLogger
import site.addzero.cordis.Engine
import site.addzero.cordis.FiberState
import site.addzero.cordis.LogLevel
import site.addzero.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * JS 平台的 WASM 装载测试。
 * 制品字节与 JVM 侧完全相同：这是“同一份 .wasm 跑在两个平台”的实证。
 */
class JsWasmPluginTest {

    @Test
    fun readsExportedMetadataFromTheSameArtifact() = runTest {
        val instance = JsWasmRuntime().instantiate(TestArtifacts.greeter, noopHost())

        assertEquals(1, instance.abiVersion())
        assertEquals("greeter", instance.pluginName())
        assertEquals(listOf("counter"), instance.inject())
    }

    @Test
    fun pluginStaysPendingUntilDependencyAppears() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val loader = WasmPluginLoader(JsWasmRuntime())

        val fiber = engine.root.plugin(loader.load(TestArtifacts.greeter))
        assertEquals(FiberState.Pending, fiber.state)

        engine.root.plugin(plugin("counter") {
            provide(loader.keys.key<Any>("counter"), "ready")
        })
        assertEquals(FiberState.Active, fiber.state)
    }

    @Test
    fun logsReachTheHost() = runTest {
        val marks = mutableListOf<String>()
        val instance = JsWasmRuntime().instantiate(TestArtifacts.greeter, markingHost(marks))

        instance.start()

        assertEquals(listOf("greeter"), marks)
    }

    @Test
    fun rejectsArtifactWithoutAbiVersion() = runTest {
        val loader = WasmPluginLoader(JsWasmRuntime())

        val failure = assertFailsWith<CordisException> {
            loader.load(TestArtifacts.bare)
        }
        assertEquals(CordisErrorCode.PluginAbiInvalid, failure.code)
    }

    @Test
    fun rejectsNonWasmBytes() = runTest {
        assertFailsWith<CordisException> {
            JsWasmRuntime().instantiate("not a wasm module".encodeToByteArray(), noopHost())
        }
    }

    @Test
    fun startFailureCodeIsPreserved() = runTest {
        val instance = JsWasmRuntime().instantiate(TestArtifacts.failing, noopHost())

        assertEquals(7, instance.start())
    }

    private fun noopHost(): WasmHost = object : WasmHost {
        override fun log(level: Int, message: String) = Unit

        override fun config(key: String): String? = null
    }

    private fun markingHost(marks: MutableList<String>): WasmHost = object : WasmHost {
        override fun log(level: Int, message: String) {
            marks.add(message)
        }

        override fun config(key: String): String? = null
    }
}

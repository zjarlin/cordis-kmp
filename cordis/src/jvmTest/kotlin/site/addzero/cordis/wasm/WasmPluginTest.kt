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
 * JVM 平台的 WASM 装载测试。
 * 制品字节来自 commonTest 里的同一份常量，与 JS 侧共享，
 * 因此这两个测试类通过就等于证明了“一份制品、两个平台、相同行为”。
 */
class WasmPluginTest {

    @Test
    fun readsExportedMetadata() = runTest {
        val instance = ChicoryWasmRuntime().instantiate(TestArtifacts.greeter, noopHost())

        assertEquals(1, instance.abiVersion())
        assertEquals("greeter", instance.pluginName())
        assertEquals(listOf("counter"), instance.inject())
    }

    @Test
    fun pluginBecomesActiveOnlyWhenInjectedServiceAppears() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val loader = WasmPluginLoader(ChicoryWasmRuntime())

        val fiber = engine.root.plugin(loader.load(TestArtifacts.greeter))
        assertEquals(FiberState.Pending, fiber.state)

        engine.root.plugin(plugin("counter") {
            provide(loader.keys.key<Any>("counter"), "ready")
        })
        assertEquals(FiberState.Active, fiber.state)
    }

    @Test
    fun stopIsCalledWhenPluginIsUnloaded() = runTest {
        val marks = mutableListOf<String>()
        val instance = ChicoryWasmRuntime().instantiate(TestArtifacts.greeter, markingHost(marks))

        assertEquals(0, instance.start())
        assertEquals(0, instance.stop())
        assertTrue(marks.contains("greeter"))
    }

    @Test
    fun rejectsArtifactWithoutAbiVersion() = runTest {
        val loader = WasmPluginLoader(ChicoryWasmRuntime())

        val failure = assertFailsWith<CordisException> {
            loader.load(TestArtifacts.bare)
        }
        assertEquals(CordisErrorCode.PluginAbiInvalid, failure.code)
    }

    @Test
    fun rejectsArtifactThatIsNotWasm() = runTest {
        assertFailsWith<CordisException> {
            ChicoryWasmRuntime().instantiate("not a wasm module".encodeToByteArray(), noopHost())
        }
    }

    @Test
    fun startFailureCodeIsPreserved() = runTest {
        val instance = ChicoryWasmRuntime().instantiate(TestArtifacts.failing, noopHost())

        assertEquals(7, instance.start())
    }

    @Test
    fun logsAreForwardedToHost() = runTest {
        val marks = mutableListOf<String>()
        val instance = ChicoryWasmRuntime().instantiate(TestArtifacts.greeter, markingHost(marks))

        instance.start()

        assertEquals(listOf("greeter"), marks)
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

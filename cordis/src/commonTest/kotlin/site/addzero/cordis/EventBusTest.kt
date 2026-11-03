package site.addzero.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 事件总线测试：四种派发模式 + 生命周期解绑。
 */
class EventBusTest {

    @Test
    fun emitDeliversToEveryListener() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listener-a") {
            on("ping") { seen.add("a") }
            on("ping") { seen.add("b") }
        })

        engine.root.emit("ping")

        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun listenersAreRemovedWhenPluginUnloads() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        val fiber = engine.root.plugin(plugin("listener") {
            on("ping") { seen.add("hit") }
        })

        engine.root.emit("ping")
        fiber.dispose()
        engine.root.emit("ping")

        assertEquals(listOf("hit"), seen)
    }

    @Test
    fun prependPutsListenerFirst() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("ping") { seen.add("normal") }
            on("ping", OnOptions(prepend = true)) { seen.add("prepended") }
        })

        engine.root.emit("ping")

        assertEquals(listOf("prepended", "normal"), seen)
    }

    @Test
    fun onceFiresExactlyOnce() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var hits = 0

        engine.root.plugin(plugin("listener") {
            once("ping") { hits++ }
        })

        engine.root.emit("ping")
        engine.root.emit("ping")

        assertEquals(1, hits)
    }

    @Test
    fun serialStopsAtFirstBailedResult() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("resolve") { seen.add("first"); "first-result" }
            on("resolve") { seen.add("second"); "second-result" }
        })

        val result = engine.events.serial("resolve")

        assertEquals("first-result", result)
        assertEquals(listOf("first"), seen)
    }

    @Test
    fun serialContinuesWhenListenerReturnsNull() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("resolve") { seen.add("first"); null }
            on("resolve") { seen.add("second"); "hit" }
        })

        val result = engine.events.serial("resolve")

        assertEquals("hit", result)
        assertEquals(listOf("first", "second"), seen)
    }

    @Test
    fun waterfallLetsListenerShortCircuitTheChain() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("build") { call ->
                seen.add("outer")
                "short-circuit"
            }
            on("build") { call ->
                seen.add("inner")
                call.next()
            }
        })

        val result = engine.events.waterfall("build") {
            seen.add("terminal")
            "terminal"
        }

        assertEquals("short-circuit", result)
        assertEquals(listOf("outer"), seen)
    }

    @Test
    fun waterfallReachesTerminalWhenEveryListenerCallsNext() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("build") { call ->
                seen.add("outer")
                call.next()
            }
        })

        val result = engine.events.waterfall("build") {
            seen.add("terminal")
            "from-terminal"
        }

        assertEquals("from-terminal", result)
        assertEquals(listOf("outer", "terminal"), seen)
    }

    @Test
    fun parallelRunsEveryListenerAndCollectsFailures() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<String>()

        engine.root.plugin(plugin("listeners") {
            on("task") { seen.add("ok") }
            on("task") { throw IllegalStateException("boom") }
        })

        val failure = runCatching { engine.events.parallel("task") }.exceptionOrNull()

        assertTrue(failure is CordisException)
        assertTrue(seen.contains("ok"))
    }

    @Test
    fun callingNextTwiceIsRejected() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var failure: Throwable? = null

        engine.root.plugin(plugin("listener") {
            on("build") { call ->
                call.next()
                runCatching { call.next() }.onFailure { failure = it }
                null
            }
        })

        engine.events.waterfall("build") { "done" }

        assertTrue(failure is CordisException)
        assertEquals(CordisErrorCode.NextCalledTwice, (failure as CordisException).code)
    }

    @Test
    fun emitOnMissingEventIsNoOp() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))

        engine.root.emit("nobody-listens")
        val result = engine.events.serial("nobody-listens")

        assertNull(result)
        assertEquals(FiberState.Active, engine.root.fiber.state)
    }
}

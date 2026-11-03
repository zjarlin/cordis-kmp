package site.addzero.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 服务生命周期测试：provide / 依赖驱动重载 / 卸载顺序。
 */
class ServiceLifecycleTest {
    private val counter = serviceKey<Counter>("counter")

    private class Counter(var value: Int = 0)

    @Test
    fun pluginBecomesActiveOnlyAfterDependenciesAreReady() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var mounted = false

        val consumer = plugin("consumer", inject = setOf(counter)) {
            mounted = true
            val service = get(counter)
            assertEquals(7, service.value)
        }

        val fiber = engine.root.plugin(consumer)

        assertEquals(FiberState.Pending, fiber.state)
        assertFalse(mounted)

        engine.root.plugin(plugin("provider") {
            provide(counter, Counter(7))
        })

        assertEquals(FiberState.Active, fiber.state)
        assertTrue(mounted)
    }

    @Test
    fun pluginUnloadsWhenProviderDisappears() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var unloaded = false

        val consumer = plugin("consumer", inject = setOf(counter)) {
            get(counter)
            onDispose { unloaded = true }
        }

        engine.root.plugin(consumer)
        val provider = engine.root.plugin(plugin("provider") {
            provide(counter, Counter(1))
        })

        assertEquals(FiberState.Active, provider.state)
        assertFalse(unloaded)

        provider.dispose()

        assertTrue(unloaded)
    }

    @Test
    fun consumerReloadsOnProviderReplacement() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val seen = mutableListOf<Int>()

        engine.root.plugin(plugin("consumer", inject = setOf(counter)) {
            seen.add(get(counter).value)
        })

        val first = engine.root.plugin(plugin("provider-a") {
            provide(counter, Counter(1))
        })

        first.dispose()

        engine.root.plugin(plugin("provider-b") {
            provide(counter, Counter(2))
        })

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun provideTwiceInSameIsolationIsRejected() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val failures = mutableListOf<Throwable>()

        engine.root.plugin(plugin("provider") {
            provide(counter, Counter(1))
            try {
                provide(counter, Counter(2))
            } catch (reason: Throwable) {
                failures.add(reason)
            }
        })

        assertEquals(1, failures.size)
        assertTrue(failures.single() is CordisException)
    }

    @Test
    fun getWithoutProvideThrowsServiceNotFound() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val failures = mutableListOf<Throwable>()

        engine.root.plugin(plugin("consumer") {
            try {
                get(counter)
            } catch (reason: Throwable) {
                failures.add(reason)
            }
        })

        val error = failures.single()
        assertTrue(error is CordisException)
        assertEquals(CordisErrorCode.ServiceNotFound, error.code)
    }

    @Test
    fun getOrNullReturnsNullWhenServiceIsMissing() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var value: Counter? = Counter(99)

        engine.root.plugin(plugin("consumer") {
            value = getOrNull(counter)
        })

        assertNull(value)
    }

    @Test
    fun isolateKeepsTwoImplementationsOfTheSameKeyApart() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val observed = mutableListOf<Int>()

        engine.root.plugin(plugin("provider") {
            provide(counter, Counter(1))

            // 隔离域必须复用同一个 Context，重复 isolate(name) 会派生出互相独立的域
            val shadow = isolate(counter.name)
            shadow.plugin(plugin("isolated-provider") {
                provide(counter, Counter(2))
            })
            shadow.plugin(plugin("isolated-consumer", inject = setOf(counter)) {
                observed.add(get(counter).value)
            })
        })

        assertEquals(listOf(2), observed)
    }

    @Test
    fun nestedPluginUnloadsWithParent() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var childDisposed = false

        val parent = engine.root.plugin(plugin("parent") {
            plugin(plugin("child") {
                onDispose { childDisposed = true }
            })
        })

        assertEquals(FiberState.Active, parent.state)
        assertFalse(childDisposed)

        parent.dispose()

        assertTrue(childDisposed)
    }

    @Test
    fun effectReleasesRegisteredResourcesExactlyOnce() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val log = mutableListOf<String>()

        val fiber = engine.root.plugin(plugin("resource-owner") {
            onDispose { log.add("body") }
        })

        fiber.dispose()
        fiber.dispose()

        assertEquals(listOf("body"), log)
        assertEquals(FiberState.Disposed, fiber.state)
    }

    @Test
    fun providerCanReadItsOwnServiceWhileLoading() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var observed = -1

        engine.root.plugin(plugin("self-referencing") {
            provide(counter, Counter(5))
            observed = get(counter).value
        })

        assertEquals(5, observed)
    }
}

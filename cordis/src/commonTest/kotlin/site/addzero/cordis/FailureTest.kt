package site.addzero.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/*
 * 失败语义测试：插件抛异常后不能反复重试，也不能残留半成品服务。
 */
class FailureTest {
    private val counterKey = serviceKey<Counter>("counter")

    private class Counter(val value: Int)

    @Test
    fun failingPluginIsMarkedFailedAndRecordsError() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val boom = IllegalStateException("boom")

        val fiber = engine.root.plugin(plugin("broken") {
            throw boom
        })

        assertEquals(FiberState.Failed, fiber.state)
        assertEquals(boom, fiber.error)
    }

    @Test
    fun failingPluginIsNotRetriedOnEveryReconcile() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var attempts = 0

        engine.root.plugin(plugin("flaky") {
            attempts++
            throw IllegalStateException("boom")
        })

        engine.reconcile()
        engine.reconcile()

        assertEquals(1, attempts)
    }

    @Test
    fun failingPluginRetriesWhenDependenciesChange() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var attempts = 0

        engine.root.plugin(plugin("flaky", inject = setOf(counterKey)) {
            attempts++
            throw IllegalStateException("boom")
        })

        engine.root.plugin(plugin("provider") {
            provide(counterKey, Counter(1))
        })

        // 依赖从「不可用」变成「可用」，允许重试一次
        assertEquals(1, attempts)
    }

    @Test
    fun servicesProvidedBeforeFailureAreRevoked() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var consumer: Fiber? = null

        engine.root.plugin(plugin("half-broken") {
            provide(counterKey, Counter(1))
            throw IllegalStateException("boom after provide")
        })

        consumer = engine.root.plugin(plugin("consumer", inject = setOf(counterKey)) {
            get(counterKey)
        })

        // 提供方失败后其服务必须被撤销，消费方不能激活
        assertEquals(FiberState.Pending, consumer.state)
        assertNull(engine.root.getOrNull(counterKey))
    }

    @Test
    fun failedPluginCanRecoverAfterDependencyReplacement() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var shouldFail = true

        val fiber = engine.root.plugin(plugin("consumer", inject = setOf(counterKey)) {
            get(counterKey)
            if (shouldFail) {
                throw IllegalStateException("first attempt fails")
            }
        })

        val first = engine.root.plugin(plugin("provider-a") {
            provide(counterKey, Counter(1))
        })

        assertEquals(FiberState.Failed, fiber.state)
        assertNotNull(fiber.error)

        shouldFail = false
        first.dispose()
        engine.root.plugin(plugin("provider-b") {
            provide(counterKey, Counter(2))
        })

        assertEquals(FiberState.Active, fiber.state)
        assertNull(fiber.error)
    }
}

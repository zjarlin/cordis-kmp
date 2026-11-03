package site.addzero.cordis

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 隔离域测试：逐项 shadowing，未 shadow 的服务仍然回落外层。
 */
class IsolationTest {
    private val counterKey = serviceKey<Counter>("counter")
    private val greeterKey = serviceKey<Greeter>("greeter")

    private class Counter(val value: Int)

    private class Greeter(val prefix: String)

    @Test
    fun nonShadowedServiceStillResolvesFromOuterScope() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val observed = mutableListOf<String>()

        engine.root.plugin(plugin("outer") {
            provide(counterKey, Counter(1))
            provide(greeterKey, Greeter("outer"))

            // 只隔离 counter；greeter 未被 shadow，内层应继续解析到外层实现
            isolate(counterKey.name).plugin(plugin("inner", inject = setOf(greeterKey)) {
                observed.add(get(greeterKey).prefix)
            })
        })

        assertEquals(listOf("outer"), observed)
    }

    @Test
    fun shadowedServiceIsUnavailableWithoutInnerProvider() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var inner: Fiber? = null

        engine.root.plugin(plugin("outer") {
            provide(counterKey, Counter(1))

            // counter 被 shadow 后，内层不会再回落到外层实现，因此保持 Pending
            inner = isolate(counterKey.name).plugin(plugin("inner", inject = setOf(counterKey)) {
                error("must not load without an inner provider")
            })
        })

        assertEquals(FiberState.Pending, inner?.state)
    }

    @Test
    fun shadowedServiceHidesOuterImplementation() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        var observed = -1

        engine.root.plugin(plugin("outer") {
            provide(counterKey, Counter(1))

            val shadow = isolate(counterKey.name)
            shadow.plugin(plugin("inner-provider") {
                provide(counterKey, Counter(2))
            })
            shadow.plugin(plugin("inner-consumer", inject = setOf(counterKey)) {
                observed = get(counterKey).value
            })
        })

        assertEquals(2, observed)
    }

    @Test
    fun separateIsolationsOfTheSameNameAreIndependent() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val failures = mutableListOf<Throwable>()

        engine.root.plugin(plugin("multi") {
            provide(counterKey, Counter(0))

            // 两次独立的 isolate 派生出互相独立的域，各自可以 provide 同名服务
            isolate(counterKey.name).plugin(plugin("a") {
                runCatching { provide(counterKey, Counter(1)) }.onFailure { failures.add(it) }
            })
            isolate(counterKey.name).plugin(plugin("b") {
                runCatching { provide(counterKey, Counter(2)) }.onFailure { failures.add(it) }
            })
        })

        assertTrue(failures.isEmpty(), "unexpected failures: $failures")
    }

    @Test
    fun reusingTheSameIsolatedContextSharesTheIsolation() = runTest {
        val engine = Engine(CordisLogger(threshold = LogLevel.Error))
        val failures = mutableListOf<Throwable>()

        engine.root.plugin(plugin("multi") {
            provide(counterKey, Counter(0))

            val shadow = isolate(counterKey.name)
            shadow.plugin(plugin("a") {
                runCatching { provide(counterKey, Counter(1)) }.onFailure { failures.add(it) }
            })
            shadow.plugin(plugin("b") {
                runCatching { provide(counterKey, Counter(2)) }.onFailure { failures.add(it) }
            })
        })

        // 同一隔离域内重复 provide 必须被拒绝
        assertEquals(1, failures.size)
        assertTrue(failures.single() is CordisException)
    }
}

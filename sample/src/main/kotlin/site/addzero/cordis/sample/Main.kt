package site.addzero.cordis.sample

import kotlinx.coroutines.runBlocking
import site.addzero.cordis.CordisLogger
import site.addzero.cordis.Engine
import site.addzero.cordis.FiberState
import site.addzero.cordis.LogLevel
import site.addzero.cordis.ServiceKey
import site.addzero.cordis.plugin

/*
 * 演示服务：一个计数器，模拟“数据库连接”这类下游依赖。
 */
interface Counter {
    val value: Int
}

/*
 * 演示服务：对外暴露业务能力。
 */
interface Greeter {
    fun greet(name: String): String
}

/*
 * 示例入口：演示插件按依赖自动激活、依赖消失自动卸载。
 */
fun main() = runBlocking {
    val logger = CordisLogger(threshold = LogLevel.Debug)
    val engine = Engine(logger)

    val counterKey = ServiceKey<Counter>("counter")
    val greeterKey = ServiceKey<Greeter>("greeter")

    println("== 1. 先装消费者，观察它保持 Pending ==")
    val consumer = engine.root.plugin(
        plugin("greeter", inject = setOf(counterKey)) {
            val live = get(counterKey)
            provide(greeterKey, object : Greeter {
                override fun greet(name: String): String = "hello $name (#${live.value})"
            })
            onDispose { println("  greeter 卸载") }
        }
    )
    println("  greeter 状态 = ${consumer.state}")

    engine.root.on("greet") { call ->
        val name = call.args.firstOrNull() as? String ?: "world"
        val greeter = engine.root.get(greeterKey)
        println("  -> ${greeter.greet(name)}")
        null
    }

    println("== 2. 装上依赖，观察级联激活 ==")
    var counterValue = 1
    val provider = engine.root.plugin(
        plugin("counter") {
            provide(counterKey, object : Counter {
                override val value: Int get() = counterValue
            })
            onDispose { println("  counter 卸载") }
        }
    )
    println("  greeter 状态 = ${consumer.state}")
    engine.root.emit("greet", "cordis")

    println("== 3. 热更新依赖实现，观察消费者重载 ==")
    provider.dispose()
    counterValue = 42
    engine.root.plugin(
        plugin("counter-v2") {
            provide(counterKey, object : Counter {
                override val value: Int get() = counterValue
            })
        }
    )
    println("  greeter 状态 = ${consumer.state}")
    engine.root.emit("greet", "kmp")

    println("== 4. 卸载依赖，观察消费者退回 Pending ==")
    engine.dispose()
    println("  greeter 状态 = ${consumer.state}")
    println("  fiber 数量 = ${engine.fiberCount}")
    check(consumer.state == FiberState.Disposed || consumer.state == FiberState.Pending)
}

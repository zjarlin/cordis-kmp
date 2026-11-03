package site.addzero.cordis

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/*
 * 一次事件派发。
 * waterfall 模式下监听器通过 next() 把控制权交给内层。
 */
public class EventInvocation internal constructor(
    public val args: List<Any?>,
    private val nextProvider: (suspend () -> Any?)?,
) {
    public val hasNext: Boolean
        get() = nextProvider != null

    public suspend fun next(): Any? {
        val provider = nextProvider
            ?: throw CordisException(CordisErrorCode.NextUnavailable, "next() is not available for this dispatch mode")
        return provider()
    }
}

/*
 * 事件监听器。
 * 返回值只在 serial / waterfall 模式下有语义。
 */
public fun interface EventListener {
    public suspend fun handle(call: EventInvocation): Any?
}

/*
 * 监听选项。
 * prepend 让监听器排到队首。
 */
public data class OnOptions(
    public val prepend: Boolean = false,
)

/*
 * 事件总线。
 * 与上游 cordis 的差异：不做动态 `thisArg` 过滤，改为监听时显式声明。
 */
public class EventBus internal constructor(private val engine: Engine) {
    private val hooks = mutableMapOf<String, MutableList<Hook>>()

    private class Hook(
        val owner: Context,
        val listener: EventListener,
        val options: OnOptions,
    ) {
        var removed: Boolean = false
    }

    /*
     * 注册监听器。
     * 生命周期绑定到注册时所在 Fiber，插件卸载时自动移除。
     */
    public suspend fun on(
        owner: Context,
        name: String,
        options: OnOptions = OnOptions(),
        listener: EventListener,
    ): Disposable {
        return owner.fiber.effect("on($name)") {
            val list = hooks.getOrPut(name) { mutableListOf() }
            val hook = Hook(owner, listener, options)
            if (options.prepend) {
                list.add(0, hook)
            } else {
                list.add(hook)
            }
            onDispose {
                hook.removed = true
                list.remove(hook)
                if (list.isEmpty()) {
                    hooks.remove(name)
                }
            }
        }
    }

    /*
     * 只触发一次，触发后自动解除。
     */
    public suspend fun once(
        owner: Context,
        name: String,
        options: OnOptions = OnOptions(),
        listener: EventListener,
    ): Disposable {
        lateinit var handle: Disposable
        handle = on(owner, name, options) { call ->
            handle.dispose()
            listener.handle(call)
        }
        return handle
    }

    /*
     * 广播，忽略返回值。
     * 单个监听器失败只记录日志，不影响其余监听器。
     */
    public suspend fun emit(name: String, vararg args: Any?) {
        activeHooks(name).forEach { hook ->
            try {
                hook.listener.handle(EventInvocation(args.toList(), null))
            } catch (reason: Throwable) {
                engine.logger.error(hook.owner.fiber.name, reason)
            }
        }
    }

    /*
     * 并行执行全部监听器。
     * 先让全部监听器跑完，再把失败汇总成一个异常抛出，避免半途中断。
     */
    public suspend fun parallel(name: String, vararg args: Any?) {
        val payload = args.toList()
        val results = coroutineScope {
            activeHooks(name).map { hook ->
                async {
                    try {
                        hook.listener.handle(EventInvocation(payload, null))
                        null
                    } catch (reason: Throwable) {
                        reason
                    }
                }
            }.awaitAll()
        }

        val failures = results.filterNotNull()
        if (failures.isEmpty()) {
            return
        }
        throw CordisException(
            CordisErrorCode.ListenerFailed,
            "one or more listeners of event \"$name\" failed",
            failures.first(),
        )
    }

    /*
     * 串行执行，遇到第一个非空返回值立即中止并返回。
     */
    public suspend fun serial(name: String, vararg args: Any?): Any? {
        val payload = args.toList()
        for (hook in activeHooks(name)) {
            val result = hook.listener.handle(EventInvocation(payload, null))
            if (isBailed(result)) {
                return result
            }
        }
        return null
    }

    /*
     * 洋葱模型：监听器决定是否调用 next() 继续向内层推进。
     */
    public suspend fun waterfall(name: String, vararg args: Any?, terminal: suspend () -> Any?): Any? {
        val list = activeHooks(name)
        val payload = args.toList()
        var index = 0

        suspend fun dispatch(): Any? {
            while (index < list.size) {
                val candidate = list[index]
                index++
                if (candidate.removed) {
                    continue
                }

                var called = false
                val next: suspend () -> Any? = {
                    if (called) {
                        throw CordisException(CordisErrorCode.NextCalledTwice)
                    }
                    called = true
                    dispatch()
                }
                return candidate.listener.handle(EventInvocation(payload, next))
            }
            return terminal()
        }

        return dispatch()
    }

    private fun activeHooks(name: String): List<Hook> {
        return hooks[name]?.filter { !it.removed } ?: emptyList()
    }
}

/*
 * 与上游一致的“是否已短路”判定：null / false 视为未短路。
 */
internal fun isBailed(value: Any?): Boolean {
    return value != null && value != false
}

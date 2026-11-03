package site.addzero.cordis

/*
 * 插件运行上下文。
 * 一个 Context 绑定一个 Fiber，服务解析、事件注册、effect 创建都以它为边界。
 */
public class Context internal constructor(
    internal val engine: Engine,
    public val fiber: Fiber,
    internal val isolation: Isolation,
    public val parent: Context?,
) {
    public val name: String
        get() = fiber.name

    /*
     * 事件总线入口。
     */
    public val events: EventBus
        get() = engine.events

    /*
     * 日志器。
     */
    public val logger: CordisLogger
        get() = engine.logger

    /*
     * 派生一个隔离上下文。
     * 同一服务名在隔离域内各用各的实现，互不干扰。
     */
    public fun isolate(name: String): Context {
        return Context(engine, fiber, isolation.isolate(name), parent)
    }

    /*
     * 按服务键解析实现。
     * 未在 inject 中声明的服务不可访问，从机制上避免“偷偷依赖全局单例”。
     */
    public fun <T : Any> get(key: ServiceKey<T>): T {
        assertVisible(key)
        val impl = engine.availableImpl(this, key)
            ?: throw CordisException(
                CordisErrorCode.ServiceNotFound,
                "cannot get required service \"${key.name}\" in <${fiber.name}>",
            )
        // 提供方自己装载期间允许读到刚 provide 的服务（自引用）
        if (impl.ctx.fiber !== fiber && impl.ctx.fiber.state != FiberState.Active) {
            throw CordisException(
                CordisErrorCode.ServiceInactive,
                "service \"${key.name}\" is provided by <${impl.ctx.fiber.name}> which is ${impl.ctx.fiber.state.name.lowercase()}",
            )
        }
        return cast(impl)
    }

    /*
     * 宽松解析，拿不到返回 null。
     */
    public fun <T : Any> getOrNull(key: ServiceKey<T>): T? {
        if (!isVisible(key)) {
            return null
        }
        val impl = engine.availableImpl(this, key) ?: return null
        return cast(impl)
    }

    /*
     * 登记服务实现，返回的句柄负责撤销登记。
     * 必须放在插件体内调用，生命周期自动跟随插件。
     */
    public suspend fun <T : Any> provide(key: ServiceKey<T>, value: T): Effect {
        val effect = fiber.effect("provide(${key.name})") {
            val impl = engine.register(this@Context, key, value)
            onDispose {
                engine.unregister(impl)
            }
        }
        engine.notifyServiceChanged(key)
        return effect
    }

    /*
     * 登记一段随插件卸载执行的清理逻辑。
     * 语义等价于 `effect { onDispose { ... } }`，常用场景的短写法。
     */
    public suspend fun onDispose(label: String = "onDispose", block: suspend () -> Unit) {
        fiber.effect(label) {
            onDispose(block)
        }
    }

    /*
     * 注册事件监听器。
     */
    public suspend fun on(
        name: String,
        options: OnOptions = OnOptions(),
        listener: EventListener,
    ): Disposable {
        return events.on(this, name, options, listener)
    }

    /*
     * 注册一次性事件监听器。
     */
    public suspend fun once(
        name: String,
        options: OnOptions = OnOptions(),
        listener: EventListener,
    ): Disposable {
        return events.once(this, name, options, listener)
    }

    public suspend fun emit(name: String, vararg args: Any?) {
        events.emit(name, *args)
    }

    /*
     * 在上下文上装载子插件。
     * 子插件生命周期跟随当前插件：当前插件卸载时一并卸载。
     */
    public suspend fun <T> plugin(target: Plugin<T>, config: T): Fiber {
        val child = engine.mount(this, target, config)
        fiber.effect("plugin(${target.name})") {
            onDispose { child.dispose() }
        }
        return child
    }

    /*
     * 无配置插件装载。
     */
    public suspend fun plugin(target: Plugin<Unit>): Fiber = plugin(target, Unit)

    /*
     * 在当前上下文上创建 effect。
     */
    public suspend fun effect(label: String = "anonymous", block: suspend EffectScope.() -> Unit): Effect {
        return fiber.effect(label, block)
    }

    /*
     * 隔离令牌。
     * 派生隔离上下文会累积成一张表，因此单个 Context 的令牌已编码全部 shadowing 语义。
     */
    internal fun tokenOf(key: ServiceKey<*>): Any = isolation.tokenOf(key.name, engine.depot)

    /*
     * 可见性判定。
     * 根上下文不受限；插件上下文只能访问“自己声明的依赖”和“自己提供的服务”。
     */
    internal fun isVisible(key: ServiceKey<*>): Boolean {
        if (fiber.plugin == null) {
            return true
        }
        if (key in fiber.declaredInject) {
            return true
        }
        val token = tokenOf(key)
        return fiber.provisions.any { it.key === key && it.isolateToken === token }
    }

    private fun assertVisible(key: ServiceKey<*>) {
        if (isVisible(key)) {
            return
        }
        throw CordisException(
            CordisErrorCode.ServiceNotFound,
            "service \"${key.name}\" is not declared in the inject list of <${fiber.name}>",
        )
    }

    private fun <T : Any> cast(impl: Impl): T {
        @Suppress("UNCHECKED_CAST")
        return impl.value as T
    }

    override fun toString(): String = "Context<${fiber.name}>"
}

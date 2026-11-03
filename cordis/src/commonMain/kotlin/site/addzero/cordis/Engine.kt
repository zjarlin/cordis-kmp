package site.addzero.cordis

/*
 * 运行时引擎。
 * 负责 Fiber 一览、服务仓库、事件总线和依赖变更的级联重算。
 */
public class Engine(
    public val logger: CordisLogger = CordisLogger(),
) {
    internal val depot = Depot()
    internal val events = EventBus(this)

    private val fibers = mutableListOf<Fiber>()
    private var fiberCounter = 0
    private var reconcileGuard = false
    private var reconcilePending = false

    public val root: Context = run {
        val fiber = Fiber(engine = this, parent = null, plugin = null, config = null)
        fiber.state = FiberState.Active
        fibers.add(fiber)
        fiber.ctx
    }

    /*
     * 当前登记的 Fiber 数量，用于调试与断言。
     */
    public val fiberCount: Int
        get() = fibers.size

    internal fun nextFiberUid(): Int = ++fiberCounter

    /*
     * 登记服务实现。
     * 同一隔离令牌下不允许重复登记，否则依赖方会拿到不确定的实现。
     */
    internal fun register(owner: Context, key: ServiceKey<*>, value: Any): Impl {
        val token = owner.isolation.tokenOf(key.name, depot)
        depot.get(token)?.let { existing ->
            throw CordisException(
                CordisErrorCode.ServiceAlreadyRegistered,
                "service \"${key.name}\" has been registered at <${existing.ctx.fiber.name}>",
            )
        }

        val impl = Impl(depot.nextUid(), key, owner, value, isolateToken = token)
        depot.put(impl)
        owner.fiber.provisions.add(impl)
        return impl
    }

    /*
     * 撤销服务实现登记。
     */
    internal suspend fun unregister(impl: Impl) {
        depot.remove(impl)
        impl.ctx.fiber.provisions.remove(impl)
        notifyServiceChanged(impl.key)
    }

    /*
     * 查“当前是否有可用实现”，不考虑调用方是否声明过依赖。
     * 用于判断插件依赖是否满足。
     */
    internal fun availableImpl(origin: Context, key: ServiceKey<*>): Impl? {
        val impl = depot.get(origin.tokenOf(key)) ?: return null
        // 提供方自己装载期间也要能拿到自己的服务（自引用）
        if (impl.ctx.fiber !== origin.fiber && impl.ctx.fiber.state != FiberState.Active) {
            return null
        }
        return impl
    }

    /*
     * 挂载插件。
     * 挂载是同步登记的，真正的加载由 reconcile 完成，因此可以安全地级联调用。
     */
    internal suspend fun mount(parent: Context, plugin: Plugin<*>, config: Any?): Fiber {
        val fiber = Fiber(
            engine = this,
            parent = parent,
            plugin = plugin,
            config = config,
            isolation = parent.isolation,
        )
        fiber.declaredInject = plugin.inject
        fibers.add(fiber)
        reconcile()
        return fiber
    }

    /*
     * 服务可用性变化后重算全部 Fiber。
     */
    internal suspend fun notifyServiceChanged(key: ServiceKey<*>) {
        events.emit("internal/service", key.name)
        reconcile()
    }

    /*
     * 收敛全部 Fiber。
     * 用 guard 防止级联过程中的重入；重入期间的请求合并为一次补算。
     */
    internal suspend fun reconcile() {
        if (reconcileGuard) {
            reconcilePending = true
            return
        }

        reconcileGuard = true
        try {
            var rounds = 0
            do {
                reconcilePending = false
                var changed = false
                fibers.toList().forEach { fiber ->
                    if (fiber.isInTransition) {
                        return@forEach
                    }
                    val before = fiber.state
                    fiber.reconcile()
                    if (fiber.state != before) {
                        changed = true
                    }
                }
                // 重入期间有新请求（如装载子插件）也要再扫一轮
                if (!changed && !reconcilePending) {
                    break
                }
                rounds++
            } while (rounds < MAX_RECONCILE_ROUNDS)

            if (rounds >= MAX_RECONCILE_ROUNDS) {
                logger.warn(null, "reconcile did not converge after $MAX_RECONCILE_ROUNDS rounds")
            }
        } finally {
            reconcileGuard = false
        }
    }

    internal fun removeFiber(fiber: Fiber) {
        fibers.remove(fiber)
    }

    /*
     * 卸载全部插件并清空运行时。
     */
    public suspend fun dispose() {
        fibers.toList().asReversed().forEach { fiber ->
            if (fiber !== root.fiber) {
                fiber.dispose()
            }
        }
        fibers.clear()
    }

    private companion object {
        const val MAX_RECONCILE_ROUNDS = 16
    }
}

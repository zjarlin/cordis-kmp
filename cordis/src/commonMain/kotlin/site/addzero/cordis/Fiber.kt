package site.addzero.cordis

/*
 * Fiber 生命周期。
 * 与上游 cordis 的差异：显式区分 Pending / Loading / Active / Unloading，
 * 便于宿主观测“插件为何没起来”。
 */
public enum class FiberState {
    Pending,
    Loading,
    Active,
    Unloading,
    Failed,
    Disposed,
}

/*
 * 一次插件装载的运行时载体。
 * 关键语义：只有声明依赖全部可用时才进入 Active；依赖消失时自动卸载并回到 Pending。
 */
public class Fiber internal constructor(
    internal val engine: Engine,
    public val parent: Context?,
    public val plugin: Plugin<*>?,
    public var config: Any?,
    internal val isolation: Isolation = Isolation(),
) {
    public val uid: Int = engine.nextFiberUid()

    public val name: String
        get() = plugin?.name ?: "root"

    public var state: FiberState = FiberState.Pending
        internal set

    public var error: Throwable? = null
        internal set

    // 本插件对外提供的服务实现
    internal val provisions = mutableListOf<Impl>()

    // 本插件声明的依赖；用于约束 get() 的可见范围
    internal var declaredInject: Set<ServiceKey<*>> = emptySet()

    // 上一次成功装载时的依赖快照，用于判定依赖是否变化
    internal var committed: Map<ServiceKey<*>, Impl> = emptyMap()

    // 装载失败时的依赖快照：依赖未变化就不反复重试，避免下一轮扫描重复执行插件体
    private var failedEpoch: String? = null

    private val effects = DisposableBag()
    private var busy = false
    private var dirty = false
    private var disposed = false

    public val ctx: Context = Context(
        engine = engine,
        fiber = this,
        isolation = isolation,
        parent = parent,
    )

    public val isActive: Boolean
        get() = state == FiberState.Active

    /*
     * 是否正处在装载或卸载过程中。
     * 重算时跳过这类 Fiber，避免对正在执行的插件体重入。
     */
    internal val isInTransition: Boolean
        get() = busy || state == FiberState.Loading || state == FiberState.Unloading

    /*
     * 登记一个副作用。
     * 只有装载过程中才能登记，避免在已卸载插件上留下悬挂资源。
     */
    internal suspend fun effect(label: String, block: suspend EffectScope.() -> Unit): Effect {
        if (state != FiberState.Active && state != FiberState.Loading) {
            throw CordisException(
                CordisErrorCode.InactiveEffect,
                "cannot create effect \"$label\" on ${state.name.lowercase()} context",
            )
        }

        val scope = EffectScope()
        scope.block()

        lateinit var effect: Effect
        effect = Effect(label, scope) {
            effects.remove(effect)
        }
        effects.add(effect)
        return effect
    }

    /*
     * 依据当前服务可用性重算状态。
     * busy/dirty 组合让重入调用合并成一次补算，避免递归抖动。
     */
    internal suspend fun reconcile() {
        if (disposed) {
            return
        }
        if (busy) {
            dirty = true
            return
        }

        busy = true
        try {
            do {
                dirty = false
                step()
            } while (dirty)
        } finally {
            busy = false
        }
    }

    private suspend fun step() {
        val resolved = resolveRequirements()
        val ready = plugin == null || resolved.keys.containsAll(plugin.inject)
        if (!ready) {
            if (state == FiberState.Active || state == FiberState.Failed) {
                unload(reason = "dependency unavailable")
            }
            state = FiberState.Pending
            error = null
            return
        }

        val epoch = epochOf(resolved)

        if (state == FiberState.Active) {
            if (epoch != epochOf(committed)) {
                unload(reason = "dependency changed")
                load(resolved, epoch)
            }
            return
        }

        // 失败后只有依赖组合变化才重试，否则每次重算都会重复执行插件体
        if (state == FiberState.Failed && epoch == failedEpoch) {
            return
        }

        load(resolved, epoch)
    }

    private fun epochOf(impls: Map<ServiceKey<*>, Impl>): String {
        return impls.entries
            .sortedBy { it.key.name }
            .joinToString(separator = ",") { "${it.key.name}#${it.value.uid}" }
    }

    /*
     * 解析插件声明的依赖。
     * 这里只看“仓库里有没有可用实现”，不看本插件是否声明过，
     * 与 get() 的可见性检查是两件事：前者决定能否装载，后者决定能否访问。
     */
    private fun resolveRequirements(): Map<ServiceKey<*>, Impl> {
        val target = plugin ?: return emptyMap()
        val result = mutableMapOf<ServiceKey<*>, Impl>()
        for (key in target.inject) {
            val impl = engine.availableImpl(ctx, key) ?: continue
            result[key] = impl
        }
        return result
    }

    private suspend fun load(resolved: Map<ServiceKey<*>, Impl>, epoch: String) {
        val target = plugin ?: return
        state = FiberState.Loading
        engine.logger.debug(name, "loading")

        try {
            target.execute(ctx, config)
            // 只有装载成功才提交依赖快照，避免失败时留下假的“已就绪”视图
            committed = resolved
            failedEpoch = null
            error = null
            state = FiberState.Active
            engine.logger.debug(name, "active")
        } catch (reason: Throwable) {
            committed = emptyMap()
            failedEpoch = epoch
            error = reason
            state = FiberState.Failed
            engine.logger.error(name, reason)
            awaitEffects()
        }
    }

    private suspend fun unload(reason: String) {
        if (state != FiberState.Active && state != FiberState.Failed) {
            return
        }

        state = FiberState.Unloading
        engine.logger.debug(name, "unloading ($reason)")

        // 先回收资源：provide 的清理逻辑会撤销服务并触发依赖方重算
        awaitEffects()
        committed = emptyMap()
        failedEpoch = null
        state = FiberState.Pending
    }

    /*
     * 回收全部副作用，并把失败写进日志而不是吞掉。
     */
    private suspend fun awaitEffects() {
        val failures = effects.disposeAll()
        failures.forEach { reason ->
            engine.logger.error(name, reason)
        }
    }

    /*
     * 卸载该插件并释放全部资源。
     * 幂等：重复调用不会重复执行清理。
     */
    public suspend fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        unload(reason = "fiber disposed")
        state = FiberState.Disposed
        awaitEffects()
        engine.removeFiber(this)
    }

    override fun toString(): String = "Fiber<$name>"
}

package site.addzero.cordis

/*
 * effect 作用域。
 * 在 effect 内部登记的所有句柄，随该 effect 一起回收。
 */
public class EffectScope internal constructor() {
    private val bag = DisposableBag()

    /*
     * 登记回收逻辑。
     */
    public fun onDispose(block: suspend () -> Unit) {
        bag.add(Disposable { block() })
    }

    /*
     * 登记一个可回收句柄。
     */
    public fun onDispose(handle: Disposable) {
        bag.add(handle)
    }

    internal suspend fun disposeAll(): List<Throwable> = bag.disposeAll()
}

/*
 * 一个可回收的副作用单元。
 * 插件卸载时由所属 Fiber 统一回收，保证“谁注册、谁清理”。
 */
public class Effect internal constructor(
    public val label: String,
    private val scope: EffectScope,
    private val detach: () -> Unit,
) : Disposable {
    private var disposed = false

    public val isDisposed: Boolean
        get() = disposed

    override suspend fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        detach()
        scope.disposeAll()
    }
}

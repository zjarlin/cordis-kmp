package site.addzero.cordis

/*
 * 可回收句柄。
 * 契约：重复调用必须幂等，重复 dispose 不能再报错。
 */
public fun interface Disposable {
    public suspend fun dispose()
}

/*
 * 把同步回收逻辑包装成 Disposable。
 */
public fun disposable(block: () -> Unit): Disposable {
    var disposed = false
    return Disposable {
        if (disposed) {
            return@Disposable
        }
        disposed = true
        block()
    }
}

/*
 * 组合多个句柄，按加入顺序的逆序回收。
 * 回收失败不吞掉：全部执行完，再把失败列表交回调用方统一记录。
 */
internal class DisposableBag {
    private val items = mutableListOf<Disposable>()
    private var disposed = false

    public fun add(handle: Disposable) {
        if (disposed) {
            return
        }
        items.add(handle)
    }

    public fun remove(handle: Disposable): Boolean = items.remove(handle)

    public val size: Int
        get() = items.size

    public suspend fun disposeAll(): List<Throwable> {
        if (disposed) {
            return emptyList()
        }
        disposed = true

        val snapshot = items.toList()
        items.clear()

        val failures = mutableListOf<Throwable>()
        snapshot.asReversed().forEach { handle ->
            try {
                handle.dispose()
            } catch (reason: Throwable) {
                failures.add(reason)
            }
        }
        return failures
    }
}

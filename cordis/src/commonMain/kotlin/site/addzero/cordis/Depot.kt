package site.addzero.cordis

/*
 * 服务实现登记项。
 * 一个 Impl 对应“某次 provide”，生命周期由提供它的 Fiber 管理。
 */
internal class Impl(
    public val uid: Int,
    public val key: ServiceKey<*>,
    public val ctx: Context,
    public var value: Any?,
    public val check: (() -> Boolean)? = null,
    public val isolateToken: Any,
)

/*
 * 服务仓库。
 * 令牌寻址，天然支持同一服务名在多隔离域里并存。
 */
internal class Depot {
    private val defaults = mutableMapOf<String, Any>()
    private val store = mutableMapOf<Any, Impl>()
    private var counter = 0

    public fun defaultToken(name: String): Any {
        return defaults.getOrPut(name) { Any() }
    }

    public fun nextUid(): Int = ++counter

    public fun get(token: Any): Impl? = store[token]

    public fun put(impl: Impl) {
        store[impl.isolateToken] = impl
    }

    public fun remove(impl: Impl) {
        if (store[impl.isolateToken] === impl) {
            store.remove(impl.isolateToken)
        }
    }
}

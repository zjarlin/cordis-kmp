package site.addzero.cordis

/*
 * 隔离域。
 * 每一项表示“某个服务名在当前域里使用哪个令牌”，未声明的服务回落到根域默认令牌。
 */
internal class Isolation(
    private val tokens: Map<String, Any> = emptyMap(),
) {
    public fun tokenOf(name: String, depot: Depot): Any {
        return tokens[name] ?: depot.defaultToken(name)
    }

    /*
     * 派生一个子域，只覆盖给定服务名。
     */
    public fun isolate(name: String): Isolation {
        val next = tokens.toMutableMap()
        next[name] = Any()
        return Isolation(next)
    }
}

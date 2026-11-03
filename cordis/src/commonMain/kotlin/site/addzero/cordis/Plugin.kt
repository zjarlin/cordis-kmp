package site.addzero.cordis

/*
 * 插件单元。
 * 与上游 cordis 的差异：Kotlin 里无法在构造函数中登记服务，
 * 所以插件体是 suspend 函数，需要什么服务用 inject 声明，运行时再 get。
 */
public class Plugin<T>(
    public val name: String,
    public val inject: Set<ServiceKey<*>>,
    public val body: suspend Context.(T) -> Unit,
) {
    /*
     * 擦除配置类型后执行插件体。
     * Fiber 只持有 Plugin<*>，这里集中做一次受控转型。
     */
    internal suspend fun execute(ctx: Context, raw: Any?) {
        @Suppress("UNCHECKED_CAST")
        val config = raw as T
        ctx.body(config)
    }

    override fun toString(): String = "Plugin($name)"
}

/*
 * 无配置插件的语义化构造入口。
 */
public fun plugin(
    name: String = "anonymous",
    inject: Set<ServiceKey<*>> = emptySet(),
    body: suspend Context.() -> Unit,
): Plugin<Unit> = Plugin(name, inject) { body() }

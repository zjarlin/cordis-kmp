package site.addzero.cordis

/*
 * 服务键。
 * 用类型参数保证编译期安全，用 name 作为运行时身份。
 *
 * 为什么相等性按 name 而非对象身份：
 * 运行时加载的插件来自另一个编译单元或另一个 WASM 实例，它自己构造的 ServiceKey
 * 与宿主手里的不是同一个对象。若按身份判定，跨边界永远解析不到对方。
 * 按 name 判等后，跨模块、跨制品、宿主与插件之间的键都能对上。
 */
public class ServiceKey<T : Any>(public val name: String) {
    override fun equals(other: Any?): Boolean {
        return other is ServiceKey<*> && other.name == name
    }

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name
}

/*
 * 创建服务键的语义化入口，类型由接收方推断。
 */
public fun <T : Any> serviceKey(name: String): ServiceKey<T> = ServiceKey(name)

/*
 * 服务键登记表。
 * 宿主登记「本次运行有哪些服务名」，插件通过它取键，
 * 避免两侧各写一遍字符串导致拼写漂移。
 */
public class ServiceKeyRegistry {
    private val keys = mutableMapOf<String, ServiceKey<*>>()

    /*
     * 按名字取键，不存在则登记。
     * 同一登记表内同名永远返回同一个实例。
     */
    public fun <T : Any> key(name: String): ServiceKey<T> {
        @Suppress("UNCHECKED_CAST")
        return keys.getOrPut(name) { ServiceKey<Any>(name) } as ServiceKey<T>
    }

    /*
     * 批量登记，通常由宿主在启动时调用。
     */
    public fun declareAll(vararg names: String) {
        names.forEach { key<Any>(it) }
    }

    /*
     * 是否已登记过该服务名。
     */
    public fun contains(name: String): Boolean = keys.containsKey(name)

    /*
     * 当前全部已登记的服务名，供插件自检使用。
     */
    public val names: Set<String>
        get() = keys.keys.toSet()
}

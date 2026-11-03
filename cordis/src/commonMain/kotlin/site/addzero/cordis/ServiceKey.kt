package site.addzero.cordis

/*
 * 服务键。
 * 用对象身份区分服务，避免字符串拼写错误；name 只用于日志与隔离分组。
 */
public class ServiceKey<T : Any>(public val name: String) {
    override fun toString(): String = name
}

/*
 * 创建服务键的语义化入口，类型由接收方推断。
 */
public fun <T : Any> serviceKey(name: String): ServiceKey<T> = ServiceKey(name)

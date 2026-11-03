package site.addzero.cordis

/*
 * Cordis 的错误码。
 * 语义靠 code 区分，不靠 message 字符串匹配。
 */
public enum class CordisErrorCode(public val defaultMessage: String) {
    InactiveEffect("cannot create effect on an inactive context"),
    NextUnavailable("next() is not available for this dispatch mode"),
    NextCalledTwice("next() called multiple times"),
    ServiceNotFound("service is not available in the current context"),
    ServiceInactive("required service is provided by an inactive fiber"),
    ServiceAlreadyRegistered("service has been registered in the same isolation"),
    ListenerFailed("one or more event listeners failed"),
    PluginAbiInvalid("wasm artifact does not satisfy the cordis plugin abi"),
    PluginLoadFailed("plugin artifact could not be loaded"),
}

/*
 * Cordis 运行时异常。
 */
public class CordisException(
    public val code: CordisErrorCode,
    message: String = code.defaultMessage,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

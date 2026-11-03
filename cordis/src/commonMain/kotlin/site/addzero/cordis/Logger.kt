package site.addzero.cordis

/*
 * 日志级别。
 */
public enum class LogLevel { Debug, Info, Warn, Error }

/*
 * 日志出口协议。
 * 库本身不绑定具体日志框架，由宿主注入。
 */
public fun interface LogSink {
    public fun log(level: LogLevel, message: String, error: Throwable?)
}

/*
 * 默认实现：输出到标准错误，避免与业务 stdout 混淆。
 */
public object ConsoleLogSink : LogSink {
    override fun log(level: LogLevel, message: String, error: Throwable?) {
        val line = "[cordis/${level.name}] $message"
        println(line)
        error?.let { println(it.stackTraceToString()) }
    }
}

/*
 * 轻量日志器。
 * scope 用于标记日志来源（插件名 / 服务名），便于定位问题。
 */
public class CordisLogger(
    public var sink: LogSink = ConsoleLogSink,
    public var threshold: LogLevel = LogLevel.Info,
) {
    public fun log(level: LogLevel, scope: String?, message: String, error: Throwable? = null) {
        if (level.ordinal < threshold.ordinal) {
            return
        }
        val prefix = if (scope.isNullOrBlank()) {
            message
        } else {
            "<$scope> $message"
        }
        sink.log(level, prefix, error)
    }

    public fun debug(scope: String?, message: String) {
        log(LogLevel.Debug, scope, message)
    }

    public fun info(scope: String?, message: String) {
        log(LogLevel.Info, scope, message)
    }

    public fun warn(scope: String?, message: String, error: Throwable? = null) {
        log(LogLevel.Warn, scope, message, error)
    }

    public fun error(scope: String?, error: Throwable) {
        log(LogLevel.Error, scope, error.message ?: error::class.simpleName ?: "unknown error", error)
    }
}

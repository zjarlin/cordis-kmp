package site.addzero.cordis.wasm

/*
 * 插件制品 ABI。
 *
 * 为什么是 WebAssembly：
 * JAR 只有 JVM 认，iOS / Android Native / 浏览器都不认，
 * 与「一次编写、全平台装载」直接冲突。WASM 是唯一被所有目标平台接受的
 * 通用可执行制品格式（浏览器原生、JVM 靠 Chicory、Native 靠 Wasmtime），
 * 且天然带沙箱与确定性内存模型。
 *
 * 本 ABI 只定义「插件如何被调用」，不定义「插件用什么语言写」。
 * Rust / Kotlin/Wasm / AssemblyScript / C 都能产出同样形态的制品。
 *
 * 内存约定：字符串一律 UTF-8；导出函数返回指针时，指向零结尾字符串。
 * 这样插件不需要导出分配器，宿主也不需要管理跨边界的堆。
 */
public object PluginAbi {
    /*
     * 制品导入的模块名。
     */
    public const val IMPORT_MODULE: String = "cordis"

    /*
     * 制品必须导出的函数。
     * abi_version / plugin_name / inject / start / stop。
     */
    public const val EXPORT_ABI_VERSION: String = "cordis_abi_version"
    public const val EXPORT_PLUGIN_NAME: String = "cordis_plugin_name"
    public const val EXPORT_INJECT: String = "cordis_inject"
    public const val EXPORT_START: String = "cordis_start"
    public const val EXPORT_STOP: String = "cordis_stop"

    /*
     * 宿主提供给插件的导入函数，落在 IMPORT_MODULE 下。
     * log(level, ptr, len)
     * config(keyPtr, keyLen, outPtr, outCap) -> 写入字节数 / -1 缺失 / -2 缓冲不足
     */
    public const val IMPORT_LOG: String = "log"
    public const val IMPORT_CONFIG: String = "config"

    /*
     * inject 字符串的分隔符。
     * 用换行而不是逗号，避免服务名里出现标点时产生歧义。
     */
    public const val INJECT_SEPARATOR: Char = '\n'

    /*
     * 当前 ABI 版本。
     * 制品启动前与这个值比对，不匹配直接拒绝装载。
     */
    public const val VERSION: Int = 1

    /*
     * 单个字符串的字节上限。
     * WASM 线性内存是共享的，不设上限的话一个写错的插件可以用一个巨大长度值
     * 让宿主读出界内存。64 KiB 足够覆盖插件名与依赖清单。
     */
    public const val MAX_STRING_BYTES: Int = 64 * 1024

    /*
     * 配置缓冲不足的错误码。
     */
    public const val CONFIG_MISSING: Int = -1
    public const val CONFIG_BUFFER_TOO_SMALL: Int = -2
}

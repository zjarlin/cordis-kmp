package site.addzero.cordis.wasm

import site.addzero.cordis.CordisErrorCode
import site.addzero.cordis.CordisException

/*
 * Native 平台的 WASM 运行时。
 *
 * 现状说明：Kotlin/Native 没有内置 WASM 引擎，接 Wasmtime 需要为
 * 每个目标（iosArm64 / iosSimulatorArm64 / macosArm64 / linuxX64）
 * 单独交叉编译 C 库并写 cinterop 绑定。
 *
 * 这里选择显式失败而不是静默降级：
 * 宿主能立刻知道「这个平台还没接运行时」，而不是得到一个永远装载不成功、
 * 排查半天的诡异状态。接入 Wasmtime 时只需换掉这个类的实现，上层不用动。
 */
public class WasmtimeWasmRuntime : WasmRuntime {
    override suspend fun instantiate(artifact: ByteArray, host: WasmHost): WasmInstance {
        throw CordisException(
            CordisErrorCode.PluginLoadFailed,
            "no wasm runtime is wired up for this native target yet; " +
                "implement WasmRuntime with a wasmtime cinterop binding to enable runtime plugins here",
        )
    }
}

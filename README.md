# cordis-kmp

**Kotlin Multiplatform 版 [cordis](https://github.com/cordiverse/cordis)** —— 一个面向「时空可组合性」的插件元框架。

上游 cordis 是 Koishi 生态的插件运行时核心：插件不是「被注册进某个列表」，而是**声明自己依赖什么、提供什么**，运行时据此自动装载、级联卸载、按隔离域分流实现。本项目把这套语义完整移植到 Kotlin，跑在 JVM / Android / iOS / macOS / Linux / JS 上。

```kotlin
val counterKey = serviceKey<Counter>("counter")

// 消费者：声明依赖，依赖没就绪就一直 Pending
engine.root.plugin(plugin("greeter", inject = setOf(counterKey)) {
    val live = get(counterKey)
    provide(greeterKey, Greeter(live))
})

// 提供方：注册服务，消费者被自动激活
engine.root.plugin(plugin("counter") {
    provide(counterKey, Counter(1))
})
```

---

## 为什么不是「目录分源集」

KMP 的源集（`commonMain` / `jvmMain` / `iosMain`）回答的是**平台维度**问题：同一份代码在不同平台用哪个实现。它天然是编译期闭集。

cordis 的插件化回答的是**功能维度**问题：运行时有哪些插件、谁依赖谁、谁提供了谁。这两个维度正交，硬凑在一起就会得到「按目录分插件」这种既不能热插拔、又不能跨平台统一的四不像。

所以本库的职责边界是：

- **源集** → 决定哪些代码参与哪个平台的编译（`expect/actual`）。
- **cordis-kmp** → 决定运行时哪些插件可用、谁依赖谁、卸载顺序如何（本库）。

而「插件从哪来」这件事（classpath 扫描 / JS `import()` / iOS 静态注册）属于宿主，不属于框架 —— 见 [插件发现](#插件发现按平台放手) 一节。

---

## 核心模型

| 概念 | 职责 |
|---|---|
| `Engine` | 运行时根，持有服务仓库、事件总线、Fiber 一览 |
| `Context` | 插件的执行环境，`get` / `provide` / `on` / `plugin` 都挂在它上面 |
| `Fiber` | 一次插件装载的载体，有明确生命周期状态 |
| `Plugin` | 插件定义：`name` + `inject` + `body` |
| `ServiceKey<T>` | 带类型的服务键，替代字符串键 |
| `Effect` | 副作用单元，插件卸载时统一回收 |
| `EventBus` | `emit` / `parallel` / `serial` / `waterfall` 四种派发 |

### Fiber 状态机

```
Pending ──依赖就绪──▶ Loading ──成功──▶ Active
   ▲                    │                 │
   │                    └──异常──▶ Failed │
   └────────依赖消失──────────────────────┤
   ▲                                      │
   └────────── Unloading ◀────────────────┘
```

关键保证：

- **依赖不满足就不装载**：`Pending` 是合法稳态，不是错误。
- **依赖变化自动重载**：提供方换实现，消费方先卸载再装载，不会读到半状态。
- **卸载顺序正确**：先撤销 `provide` 的服务，让依赖方先感知并退出，再回收自身资源。
- **声明即边界**：没写进 `inject` 的服务，`get()` 直接抛异常，堵住「偷偷依赖全局单例」。

---

## 快速开始

### 依赖

```kotlin
commonMain.dependencies {
    implementation("site.addzero:cordis-kmp:0.1.0")
}
```

### 定义一个服务

```kotlin
interface Counter {
    val value: Int
}

val counterKey = serviceKey<Counter>("counter")
```

### 写一个提供方插件

```kotlin
val counterPlugin = plugin("counter") {
    provide(counterKey, object : Counter {
        override val value = 1
    })
    onDispose { println("counter 卸载") }
}
```

### 写一个消费方插件

```kotlin
val greeterPlugin = plugin("greeter", inject = setOf(counterKey)) {
    val live = get(counterKey)
    provide(greeterKey, object : Greeter {
        override fun greet(name: String) = "hello $name (#${live.value})"
    })
}
```

### 装载

```kotlin
val engine = Engine()
engine.root.plugin(greeterPlugin)   // 此刻是 Pending
engine.root.plugin(counterPlugin)   // 级联激活 greeter
```

完整可运行示例见 [`sample/`](sample/src/main/kotlin/site/addzero/cordis/sample/Main.kt)：

```bash
./gradlew :sample:run
```

输出会走完 Pending → Active → 热替换重载 → 卸载全流程。

---

## 隔离域

同一服务名需要多份实现时，用 `isolate` 派生一个域。域内解析走独立令牌，互不可见：

```kotlin
engine.root.plugin(plugin("multi-tenant") {
    provide(counterKey, TenantCounter("default"))

    // 复用同一个隔离 Context，否则每次 isolate(name) 都会得到互相独立的新域
    val tenantA = isolate("tenant-a")
    tenantA.plugin(plugin("tenant-a-provider") {
        provide(counterKey, TenantCounter("a"))
    })
    tenantA.plugin(plugin("tenant-a-consumer", inject = setOf(counterKey)) {
        assertEquals("a", get(counterKey).tenant)
    })
})
```

这条语义和上游 cordis 的 `ctx.isolate(name)` 一致：隔离是按服务名逐项 shadowing，未 shadow 的服务仍然回落到外层。

---

## 事件总线

```kotlin
// 广播
on("ping") { /* 返回值被忽略 */ }

// 串行，遇到第一个非空返回值立即中止
val winner = events.serial("resolve")

// 洋葱模型，监听器自己决定是否 next()
events.waterfall("build", terminal = { "default" })

// 并行，任一失败合并抛 CordisException
events.parallel("task")
```

监听器生命周期绑在注册它的插件上：插件卸载，监听器自动摘除，不需要手动 `off`。

---

## 运行时插件：WebAssembly

「运行时插件」有两种含义，本库只做前一种，因为第二种不值得：

| 层次 | 含义 | 本库 |
|---|---|---|
| 运行时装载 | 制品从外部来，宿主不重新编译 | ✅ 已实现 |
| 运行时改代码 | Kotlin Script / eval | ❌ 不做 |

### 为什么是 WASM，不是 JAR

JAR 只有 JVM 认。iOS、Android Native、浏览器都不认，与「一次编写、全平台装载」直接冲突。
WASM 是唯一被所有目标平台接受的通用可执行制品格式：

| 平台 | WASM 引擎 |
|---|---|
| JVM / Android | [Chicory](https://github.com/dylibso/chicory)（纯字节码实现，无需 JNI） |
| JS / 浏览器 / Node | 引擎内置 `WebAssembly` |
| iOS / macOS / Linux Native | Wasmtime cinterop（**尚未接入**，见下） |

这不是自造标准：Extism、WASI Component Model、Krustlet 都用同一套思路，
制品可以由 Rust / Kotlin/Wasm / AssemblyScript / C 任意语言产出。

### 制品 ABI

制品导出五个函数，导入一个模块：

```
导出：
  cordis_abi_version  () -> i32         ABI 版本，必须等于 1
  cordis_plugin_name  () -> i32         插件名指针（零结尾 UTF-8）
  cordis_inject       () -> i32         依赖清单指针（'\n' 分隔的服务名）
  cordis_start        () -> i32         0 成功，非 0 为插件自定义错误码
  cordis_stop         () -> i32         可选，未导出视为无事可做

导入（模块 "cordis"）：
  log    (level: i32, ptr: i32, len: i32)
  config (keyPtr: i32, keyLen: i32, outPtr: i32, outCap: i32) -> i32
         >=0 写入字节数 / -1 缺失 / -2 缓冲不足
```

装载与使用：

```kotlin
val loader = WasmPluginLoader(
    runtime = ChicoryWasmRuntime(),          // JS 平台换成 JsWasmRuntime()
    config = mapOf("prefix" to "runtime"),
)

// 制品可以从磁盘、网络、数据库任何地方来
val plugin = loader.load(File("greeter.wasm").readBytes())
engine.root.plugin(plugin)
```

装载后它就是一个**普通 `Plugin`**：参与同一套依赖解析、级联卸载、隔离域。
宿主不需要为 WASM 插件写第二套生命周期管理 —— 这是本设计的核心收益。

跑一遍看效果：

```bash
./gradlew :sample:wasmDemo
```

### 安全边界

插件只能通过 `log` / `config` 两个导入函数与宿主交互，拿不到文件系统、网络、时钟，
也读不到宿主进程内存以外的任何东西。所有跨边界字符串都在访问前校验长度与指针，
避免一个写错的制品把宿主拖死。

### Native 现状

Kotlin/Native 没有内置 WASM 引擎。接 Wasmtime 需要为 `iosArm64` / `iosSimulatorArm64` /
`macosArm64` / `linuxX64` 分别交叉编译 C 库并写 cinterop 绑定。

当前 `WasmtimeWasmRuntime` 是一个**显式失败**的实现：调用即抛
`CordisException(PluginLoadFailed)`，并在消息里说明原因。刻意不提供静默降级的空实现 ——
让宿主立刻知道「这个平台还没接运行时」，比得到一个永远装载不成功、排查半天的诡异状态要好。

### 插件怎么来，仍然按平台放手

框架只管「已装载的插件如何协作」。制品发现属于宿主：

| 平台 | 从哪拿制品 |
|---|---|
| JVM / Android | 文件系统、classpath 资源、下载 |
| iOS | App Bundle 内置（审核限制，无法运行时下载可执行码） |
| JS / 浏览器 | `fetch()` |
| 服务端 | 数据库、对象存储、配置中心 |

---

## 目标平台

`jvm` / `js(IR, node & browser)` / `linuxX64` / `macosArm64` / `iosArm64` / `iosSimulatorArm64`。

CI 在 JVM 与 JS 上跑完整测试；Native 只做编译校验（交叉编译测试需要更多设施，暂时不做）。

---

## 与上游 cordis 的差异

| 方面 | 上游（TypeScript） | 本项目（Kotlin） |
|---|---|---|
| 服务键 | 字符串属性名 + `Proxy` 拦截 | `ServiceKey<T>` 显式键，保留类型 |
| 上下文派生 | `Object.create` 原型链 | 不可变 `Isolation` 表 |
| 依赖声明 | `inject` 数组 / 对象 | `Set<ServiceKey<*>>` |
| 生命周期 | effect runner + epoch 复用 | 显式状态机 + 轮次收敛 |
| 事件过滤 | `thisArg` + `Context.filter` | 显式 `OnOptions` |
| 动态性 | 运行时 Proxy 魔法 | 不用反射，全平台一致 |
| 插件制品 | npm 包 + 运行时 `import()` | WebAssembly（跨平台通用） |
| 插件发现 | 内置包管理器 | 交给宿主，按平台各自实现 |

**没有移植**的部分：`@Inject` 装饰器、`Context` 属性级 Proxy 拦截、HMR 插件、`replace()` 插件热替换、`mixin`/`accessor` 属性表。这些依赖 JS 的对象模型，在 KMP 里要么做不到（iOS），要么只能用反射做半吊子（JVM 独有），因此不做。

`Fiber.reconcile()` 是本项目自己的收敛算法：多轮扫描直到状态稳定，替代上游的 epoch 复用。代价是每次服务变更 O(n) 扫描，收益是行为可预测、无隐藏状态。

---

## 构建

```bash
./gradlew :cordis-kmp:jvmTest           # JVM 测试（38 个）
./gradlew :cordis-kmp:jsNodeTest        # JS 测试（37 个，共用同一份 wasm 制品）
./gradlew :cordis-kmp:compileKotlinLinuxX64
./gradlew :cordis-kmp:compileKotlinMacosArm64
./gradlew :sample:run               # 依赖驱动生命周期示例
./gradlew :sample:wasmDemo          # 运行时装载外部 wasm 插件
./gradlew publishToMavenLocal       # 发布到本地 Maven
```

## License

MIT

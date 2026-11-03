package site.addzero.cordis.wasm

/*
 * 测试用 wasm 制品，由 WAT 编译后以 base64 固化。
 * 固化而不是运行时编译：JVM 侧有 wabt，JS 侧没有，
 * 固化成同一份字节才能证明“同一个制品在两个平台行为一致”。
 */
internal object TestArtifacts {
    val greeter: ByteArray get() = Base64.decode(GREETER)
    val bare: ByteArray get() = Base64.decode(BARE)
    val failing: ByteArray get() = Base64.decode(FAILING)

    const val GREETER: String = "AGFzbQEAAAABCwJgA39/fwBgAAF/Ag4BBmNvcmRpcwNsb2cAAAMGBQEBAQEBBQMBAAEHYQYGbWVtb3J5AgASY29yZGlzX2FiaV92ZXJzaW9uAAESY29yZGlzX3BsdWdpbl9uYW1lAAINY29yZGlzX2luamVjdAADDGNvcmRpc19zdGFydAAEC2NvcmRpc19zdG9wAAUKIgUEAEEBCwQAQQALBABBIAsMAEEBQQBBBxAAQQALBABBAAsLGwIAQQALCGdyZWV0ZXIAAEEgCwhjb3VudGVyAA=="

    const val BARE: String = "AGFzbQEAAAABBQFgAAF/AwIBAAUDAQABBxkCBm1lbW9yeQIADGNvcmRpc19zdGFydAAACgYBBABBAAs="

    const val FAILING: String = "AGFzbQEAAAABBQFgAAF/AwMCAAAFAwEAAQcuAwZtZW1vcnkCABJjb3JkaXNfYWJpX3ZlcnNpb24AAAxjb3JkaXNfc3RhcnQAAQoLAgQAQQELBABBBws="

}

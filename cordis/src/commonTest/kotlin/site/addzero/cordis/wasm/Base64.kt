package site.addzero.cordis.wasm

/*
 * base64 解码。
 * Kotlin 标准库没有跨平台的解码 API（kotlin.io.encoding 在实验阶段且各版本行为有差异），
 * 测试里只需要解固定常量，自己实现比引入实验 API 更稳。
 */
internal object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(text: String): ByteArray {
        val trimmed = text.trim().trimEnd('=')
        if (trimmed.isEmpty()) {
            return ByteArray(0)
        }
        val output = ByteArray(trimmed.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in trimmed) {
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "invalid base64 character: $char" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[index++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return output.copyOf(index)
    }
}

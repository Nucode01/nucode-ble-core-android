package kr.co.nucode.ble.contract

import java.util.UUID

// 형식 파일 읽기 도우미 — ContractLoader 와 ContractDescriptors 가 같은 검사·같은 오류 문구를 쓴다.

internal fun Map<String, Any?>.requireFormat() {
    if (this["format"] != ContractLoader.FORMAT) {
        throw ContractFormatException("format 은 ${ContractLoader.FORMAT} 이어야 한다(실제 ${this["format"]})")
    }
}

internal fun Map<String, Any?>.onlyKeys(where: String, vararg allowed: String) {
    val unknown = keys - allowed.toSet()
    if (unknown.isNotEmpty()) throw ContractFormatException("$where: 모르는 키 $unknown")
}

internal fun Map<String, Any?>.req(key: String): Any =
    this[key] ?: throw ContractFormatException("'$key' 가 없다")

internal fun Map<String, Any?>.string(key: String): String =
    req(key) as? String ?: throw ContractFormatException("'$key' 는 문자열이어야 한다")

internal fun Map<String, Any?>.long(key: String, default: Long? = null): Long {
    val raw = this[key] ?: return default ?: throw ContractFormatException("'$key' 가 없다")
    return raw as? Long ?: throw ContractFormatException("'$key' 는 정수여야 한다")
}

internal fun Map<String, Any?>.bool(key: String, default: Boolean? = null): Boolean {
    val raw = this[key] ?: return default ?: throw ContractFormatException("'$key' 가 없다")
    return raw as? Boolean ?: throw ContractFormatException("'$key' 는 true/false 여야 한다")
}

internal fun Map<String, Any?>.stringList(key: String): List<String> =
    req(key).asList(key).map { it as? String ?: throw ContractFormatException("'$key' 는 문자열 목록이다") }

internal fun Map<String, Any?>.uuid(key: String): UUID = string(key).let {
    runCatching { UUID.fromString(it) }.getOrNull() ?: throw ContractFormatException("'$key' 가 UUID 가 아니다: $it")
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObject(where: String): Map<String, Any?> =
    this as? Map<String, Any?> ?: throw ContractFormatException("$where 는 객체여야 한다")

internal fun Any?.asList(where: String): List<Any?> =
    this as? List<Any?> ?: throw ContractFormatException("$where 는 배열이어야 한다")

internal fun String.oneOf(key: String, vararg allowed: String) {
    if (this !in allowed) throw ContractFormatException("$key 의 모르는 값 '$this'")
}

internal fun Int.positive(where: String) {
    if (this <= 0) throw ContractFormatException("$where: 1 이상이어야 한다")
}

internal fun String.hexBytes(where: String): ByteArray {
    val clean = replace(" ", "")
    if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        throw ContractFormatException("$where: hex 가 아니다: $this")
    }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

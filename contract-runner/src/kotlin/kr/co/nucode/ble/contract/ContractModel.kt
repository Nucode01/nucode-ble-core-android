package kr.co.nucode.ble.contract

import java.util.UUID
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ServiceRef

/** 시나리오·기기 정의 파일이 형식(contract/README.md)에 맞지 않을 때. 러너는 건너뛰지 않고 실패시킨다. */
internal class ContractFormatException(message: String) : Exception(message)

internal object Lanes {
    const val STATE = "state"
    const val RESULT = "result"
    const val NOTIFY = "notify"
    const val ACTIVE = "active"
    val ALL = listOf(STATE, RESULT, NOTIFY, ACTIVE)
}

internal data class CharacteristicSpec(
    val symbol: String,
    val service: UUID,
    val uuid: UUID,
    val properties: Set<String>,
    val runnerOnly: Boolean,
) {
    val ref: CharacteristicRef get() = CharacteristicRef(ServiceRef(BleUuid(service)), BleUuid(uuid))
}

internal data class PeripheralProfile(
    val name: String,
    val advertisedName: String,
    val characteristics: Map<String, CharacteristicSpec>,
) {
    fun spec(symbol: String): CharacteristicSpec =
        characteristics[symbol] ?: throw ContractFormatException("기기 '$name' 에 '$symbol' 이 없다")

    /** 활성 구독 기록용. 정의에 없는 ref 는 UUID 로 남겨 판정이 어긋나게 한다. */
    fun symbolOf(ref: CharacteristicRef): String =
        characteristics.values.firstOrNull { it.ref == ref }?.symbol ?: "?${ref.uuid.value}"
}

internal enum class CallOp(val key: String) {
    Connect("connect"), Disconnect("disconnect"), Read("read"), Write("write"),
    Subscribe("subscribe"), Unsubscribe("unsubscribe"),
}

internal sealed interface DeviceEvent {
    /** probe.control 에 쓸 펌웨어 명령. */
    val command: String

    data object LinkDrop : DeviceEvent {
        override val command = "LINK_DROP"
    }

    data class LinkDropHide(val ms: Long) : DeviceEvent {
        override val command get() = "LINK_DROP_HIDE $ms"
    }

    data class Notify(val count: Int) : DeviceEvent {
        override val command get() = "NOTIFY $count"
    }

    data class ReadNotify(val on: Boolean) : DeviceEvent {
        override val command get() = if (on) "READ_NOTIFY ON" else "READ_NOTIFY OFF"
    }
}

internal sealed interface Step {
    data class Call(
        val op: CallOp,
        val char: String?,
        val autoReconnect: Boolean,
        val bytes: ByteArray?,
        val withResponse: Boolean,
        val async: Boolean,
        val name: String?,
        val timeoutMs: Long,
    ) : Step {
        /** result 레인 한 줄의 앞부분: "read probe.value". */
        val label: String get() = if (char == null) op.key else "${op.key} $char"
    }

    data class AwaitState(val pattern: String, val timeoutMs: Long) : Step
    data class AwaitNotify(val count: Int, val timeoutMs: Long) : Step
    data class AwaitResult(val name: String, val timeoutMs: Long) : Step
    data class Device(val event: DeviceEvent) : Step
    data class Sleep(val ms: Long) : Step
}

internal data class Scenario(
    val id: String,
    val summary: String,
    val status: String,
    val runsOn: Set<String>,
    val platforms: Set<String>,
    val peripheral: String,
    val steps: List<Step>,
    val expect: Map<String, List<String>>,
    val diffIgnore: List<String>,
)

/** contract/README.md 형식 1 을 엄격하게 읽는다. 모르는 키·단계·이름은 모두 오류다. */
internal object ContractLoader {
    const val FORMAT = 1L
    const val DEFAULT_TIMEOUT_MS = 15_000L
    private val ID = Regex("[a-z0-9-]+")

    fun loadPeripheral(fileName: String, text: String): PeripheralProfile {
        val root = ContractJson.parse(text).asObject("기기 정의")
        root.onlyKeys("기기 정의", "format", "name", "summary", "advertisedName", "characteristics")
        root.requireFormat()
        val name = root.string("name")
        if (name != fileName) throw ContractFormatException("기기 name '$name' 이 파일 이름 '$fileName' 과 다르다")
        val chars = root.req("characteristics").asObject("characteristics").mapValues { (symbol, raw) ->
            val c = raw.asObject(symbol)
            c.onlyKeys(symbol, "service", "uuid", "properties", "runnerOnly")
            CharacteristicSpec(
                symbol = symbol,
                service = c.uuid("service"),
                uuid = c.uuid("uuid"),
                properties = c.stringList("properties")
                    .onEach { it.oneOf("properties", "read", "write", "writeWithoutResponse", "notify", "indicate") }
                    .toSet(),
                runnerOnly = c.bool("runnerOnly", false),
            )
        }
        return PeripheralProfile(name, root.string("advertisedName"), chars)
    }

    fun loadScenario(fileName: String, text: String, peripherals: Map<String, PeripheralProfile>): Scenario {
        val root = ContractJson.parse(text).asObject("시나리오")
        root.onlyKeys(
            "시나리오", "format", "id", "summary", "status", "runsOn", "platforms", "peripheral",
            "steps", "expect", "diffIgnore",
        )
        root.requireFormat()
        val id = root.string("id")
        if (!ID.matches(id)) throw ContractFormatException("id '$id' 는 소문자·숫자·- 만 쓴다")
        if (id != fileName) throw ContractFormatException("id '$id' 가 파일 이름 '$fileName' 과 다르다")
        val status = root.string("status").also { it.oneOf("status", "draft", "frozen") }
        val runsOn = root.stringList("runsOn").onEach { it.oneOf("runsOn", "sim", "device") }.toSet()
        if (runsOn.isEmpty()) throw ContractFormatException("runsOn 이 비었다")
        val platforms = (root["platforms"]?.let { root.stringList("platforms") } ?: listOf("android", "ios"))
            .onEach { it.oneOf("platforms", "android", "ios") }.toSet()
        if (platforms.isEmpty()) throw ContractFormatException("platforms 가 비었다")
        val peripheralName = root.string("peripheral")
        val profile = peripherals[peripheralName]
            ?: throw ContractFormatException("peripheral '$peripheralName' 정의 파일이 없다")

        val names = mutableSetOf<String>()
        val steps = root.req("steps").asList("steps").mapIndexed { index, raw ->
            parseStep("steps[$index]", raw.asObject("steps[$index]"), profile, names)
        }
        val expect = root.req("expect").asObject("expect").mapValues { (lane, raw) ->
            if (lane !in Lanes.ALL) throw ContractFormatException("expect 의 모르는 레인 '$lane'")
            raw.asList("expect.$lane").map { it as? String ?: throw ContractFormatException("expect.$lane 은 문자열 목록이다") }
        }
        val diffIgnore = root["diffIgnore"]?.let { root.stringList("diffIgnore") }.orEmpty().onEach {
            val lane = it.substringBefore('|', "")
            if (lane !in Lanes.ALL) throw ContractFormatException("diffIgnore '$it' 는 '<레인>|<패턴>' 이어야 한다")
        }
        return Scenario(id, root.string("summary"), status, runsOn, platforms, peripheralName, steps, expect, diffIgnore)
    }

    private fun parseStep(
        where: String,
        s: Map<String, Any?>,
        profile: PeripheralProfile,
        names: MutableSet<String>,
    ): Step {
        val kinds = listOf("call", "await", "device", "sleep").filter { it in s }
        if (kinds.size != 1) throw ContractFormatException("$where: call·await·device·sleep 중 정확히 하나가 있어야 한다")
        return when (kinds.single()) {
            "call" -> parseCall(where, s, profile, names)
            "await" -> {
                val timeout = s.long("timeoutMs", DEFAULT_TIMEOUT_MS)
                when (val what = s.string("await")) {
                    "state" -> {
                        s.onlyKeys(where, "await", "is", "timeoutMs")
                        Step.AwaitState(s.string("is"), timeout)
                    }
                    "notify" -> {
                        s.onlyKeys(where, "await", "count", "timeoutMs")
                        Step.AwaitNotify(s.long("count").toInt().also { it.positive(where) }, timeout)
                    }
                    "result" -> {
                        s.onlyKeys(where, "await", "name", "timeoutMs")
                        val name = s.string("name")
                        if (name !in names) throw ContractFormatException("$where: 앞선 async 호출 '$name' 이 없다")
                        Step.AwaitResult(name, timeout)
                    }
                    else -> throw ContractFormatException("$where: 모르는 await '$what'")
                }
            }
            "device" -> {
                val event = when (val what = s.string("device")) {
                    "linkDrop" -> { s.onlyKeys(where, "device"); DeviceEvent.LinkDrop }
                    "linkDropHide" -> { s.onlyKeys(where, "device", "ms"); DeviceEvent.LinkDropHide(s.long("ms").also { it.toInt().positive(where) }) }
                    "notify" -> { s.onlyKeys(where, "device", "count"); DeviceEvent.Notify(s.long("count").toInt().also { it.positive(where) }) }
                    "readNotify" -> { s.onlyKeys(where, "device", "on"); DeviceEvent.ReadNotify(s.bool("on")) }
                    else -> throw ContractFormatException("$where: 모르는 기기 사건 '$what'")
                }
                Step.Device(event)
            }
            else -> {
                s.onlyKeys(where, "sleep")
                Step.Sleep(s.long("sleep").also { if (it < 0) throw ContractFormatException("$where: sleep 은 0 이상") })
            }
        }
    }

    private fun parseCall(where: String, s: Map<String, Any?>, profile: PeripheralProfile, names: MutableSet<String>): Step.Call {
        val op = CallOp.values().firstOrNull { it.key == s["call"] }
            ?: throw ContractFormatException("$where: 모르는 call '${s["call"]}'")
        val common = arrayOf("call", "async", "name", "timeoutMs")
        val needsChar = op !in setOf(CallOp.Connect, CallOp.Disconnect)
        when (op) {
            CallOp.Connect -> s.onlyKeys(where, *common, "autoReconnect")
            CallOp.Disconnect -> s.onlyKeys(where, *common)
            CallOp.Write -> s.onlyKeys(where, *common, "char", "text", "hex", "mode")
            else -> s.onlyKeys(where, *common, "char")
        }
        val char = if (needsChar) s.string("char") else null
        if (char != null) {
            val spec = profile.spec(char)
            if (spec.runnerOnly) throw ContractFormatException("$where: '$char' 는 러너 전용이다")
            val needed = when (op) {
                CallOp.Read -> "read"
                CallOp.Subscribe, CallOp.Unsubscribe -> "notify"
                else -> null
            }
            if (needed != null && needed !in spec.properties) {
                throw ContractFormatException("$where: '$char' 에 $needed 속성이 없다")
            }
        }
        var bytes: ByteArray? = null
        var withResponse = true
        if (op == CallOp.Write) {
            val text = s["text"] as String?
            val hex = s["hex"] as String?
            if ((text == null) == (hex == null)) throw ContractFormatException("$where: write 는 text 나 hex 중 하나만 준다")
            bytes = text?.toByteArray(Charsets.UTF_8) ?: hex!!.hexBytes(where)
            withResponse = when (val mode = s["mode"] ?: "withResponse") {
                "withResponse" -> true
                "withoutResponse" -> false
                else -> throw ContractFormatException("$where: 모르는 mode '$mode'")
            }
        }
        val async = s.bool("async", false)
        val name = s["name"] as String?
        if (async && name == null) throw ContractFormatException("$where: async 호출에는 name 이 있어야 한다")
        if (!async && name != null) throw ContractFormatException("$where: name 은 async 호출에만 쓴다")
        if (name != null && !names.add(name)) throw ContractFormatException("$where: name '$name' 이 겹친다")
        return Step.Call(
            op = op,
            char = char,
            autoReconnect = s.bool("autoReconnect", false),
            bytes = bytes,
            withResponse = withResponse,
            async = async,
            name = name,
            timeoutMs = s.long("timeoutMs", DEFAULT_TIMEOUT_MS),
        )
    }

    // ── 읽기 도우미 ─────────────────────────────────────────────────────────

    private fun Map<String, Any?>.requireFormat() {
        if (this["format"] != FORMAT) throw ContractFormatException("format 은 $FORMAT 이어야 한다(실제 ${this["format"]})")
    }

    private fun Map<String, Any?>.onlyKeys(where: String, vararg allowed: String) {
        val unknown = keys - allowed.toSet()
        if (unknown.isNotEmpty()) throw ContractFormatException("$where: 모르는 키 $unknown")
    }

    private fun Map<String, Any?>.req(key: String): Any =
        this[key] ?: throw ContractFormatException("'$key' 가 없다")

    private fun Map<String, Any?>.string(key: String): String =
        req(key) as? String ?: throw ContractFormatException("'$key' 는 문자열이어야 한다")

    private fun Map<String, Any?>.long(key: String, default: Long? = null): Long {
        val raw = this[key] ?: return default ?: throw ContractFormatException("'$key' 가 없다")
        return raw as? Long ?: throw ContractFormatException("'$key' 는 정수여야 한다")
    }

    private fun Map<String, Any?>.bool(key: String, default: Boolean? = null): Boolean {
        val raw = this[key] ?: return default ?: throw ContractFormatException("'$key' 가 없다")
        return raw as? Boolean ?: throw ContractFormatException("'$key' 는 true/false 여야 한다")
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        req(key).asList(key).map { it as? String ?: throw ContractFormatException("'$key' 는 문자열 목록이다") }

    private fun Map<String, Any?>.uuid(key: String): UUID = string(key).let {
        runCatching { UUID.fromString(it) }.getOrNull() ?: throw ContractFormatException("'$key' 가 UUID 가 아니다: $it")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(where: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw ContractFormatException("$where 는 객체여야 한다")

    private fun Any?.asList(where: String): List<Any?> =
        this as? List<Any?> ?: throw ContractFormatException("$where 는 배열이어야 한다")

    private fun String.oneOf(key: String, vararg allowed: String) {
        if (this !in allowed) throw ContractFormatException("$key 의 모르는 값 '$this'")
    }

    private fun Int.positive(where: String) {
        if (this <= 0) throw ContractFormatException("$where: 1 이상이어야 한다")
    }

    private fun String.hexBytes(where: String): ByteArray {
        val clean = replace(" ", "")
        if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw ContractFormatException("$where: hex 가 아니다: $this")
        }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

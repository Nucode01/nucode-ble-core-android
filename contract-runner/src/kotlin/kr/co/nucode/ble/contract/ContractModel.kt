package kr.co.nucode.ble.contract

import java.util.UUID
import kr.co.nucode.ble.BleChunking
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

    // 확장 레인: 시나리오의 expect 에 있을 때만 기록·출력한다(README §기록).
    const val ADAPTER = "adapter"
    const val SCAN = "scan"
    const val FOUND = "found"
    const val SERVICES = "services"
    const val PROPERTIES = "properties"
    const val AD_TYPES = "adTypes"
    const val PEER = "peer"

    val BASE = listOf(STATE, RESULT, NOTIFY, ACTIVE)
    val EXTENSION = listOf(ADAPTER, SCAN, FOUND, SERVICES, PROPERTIES, AD_TYPES, PEER)

    /** CONTRACT 줄의 출력 순서. */
    val ALL = BASE + EXTENSION

    /** `await <레인> is` 를 쓸 수 있는 레인. */
    val AWAIT_IS = listOf(STATE, ACTIVE, ADAPTER, SCAN, SERVICES, AD_TYPES)

    /** `await <레인> count` 를 쓸 수 있는 레인. */
    val AWAIT_COUNT = listOf(NOTIFY, FOUND)

    /** 연속으로 같은 값은 하나로 합치는 레인(현재 값 레인). */
    val COALESCED = listOf(ACTIVE, ADAPTER, SCAN, SERVICES, PROPERTIES, AD_TYPES)
}

internal data class ServiceSpec(val symbol: String, val uuid: UUID, val advertised: Boolean)

/** startScan 의 filter. 둘 다 없으면 필터 없음. */
internal data class ScanFilterSpec(val name: String?, val services: List<ServiceSpec>)

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
    val services: Map<String, ServiceSpec>,
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
    StartScan("startScan"), StopScan("stopScan"), Snapshot("snapshot"), Close("close"),
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
        val chunking: BleChunking,
        val filter: ScanFilterSpec?,
        val async: Boolean,
        val name: String?,
        val timeoutMs: Long,
    ) : Step {
        /** result 레인 한 줄의 앞부분: "read probe.value". */
        val label: String get() = if (char == null) op.key else "${op.key} $char"
    }

    /** state·active·adapter·scan·services 레인의 마지막 기록이 패턴과 맞을 때까지. */
    data class AwaitLane(val lane: String, val pattern: String, val timeoutMs: Long) : Step

    /** notify·found 레인의 기록이 [count] 개 이상일 때까지. */
    data class AwaitCount(val lane: String, val count: Int, val timeoutMs: Long) : Step
    data class AwaitResult(val name: String, val timeoutMs: Long) : Step

    /**
     * 앞선 async 호출 [name] 의 호출자를 취소한다(전달 계획 §5-3). 아직 끝나지 않았으면 result 레인에
     * `<호출> cancelled` 를 남기고, 이미 끝났으면 아무것도 하지 않는다.
     */
    data class Cancel(val name: String) : Step
    data class Device(val event: DeviceEvent) : Step

    /** 기기 사건 rxCheck: 기기가 nus.rx 로 받은 것을 peer 레인에 남긴다. 기기에 아무 일도 일으키지 않는다. */
    data object RxCheck : Step

    /** sim 전용: 가짜 플랫폼의 어댑터를 [value](adapter 레인의 모양)로 바꾼다. */
    data class SetAdapter(val value: String) : Step
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
) {
    /** startScan 호출이 있는 시나리오. device 러너가 스캔 시작 한도를 피하려고 본다. */
    val usesScan: Boolean get() = steps.any { it is Step.Call && it.op == CallOp.StartScan }
}

/** contract/README.md 형식 1 을 엄격하게 읽는다. 모르는 키·단계·이름은 모두 오류다. */
internal object ContractLoader {
    const val FORMAT = 1L
    const val DEFAULT_TIMEOUT_MS = 15_000L
    private val ID = Regex("[a-z0-9-]+")
    private val ADAPTER_VALUES = setOf(
        "Unknown", "Ready", "PoweredOff", "Resetting", "Unsupported", "Unauthorized(true)", "Unauthorized(false)",
    )
    private const val PATTERN_MIN = 256L
    private const val PATTERN_MAX = 65_536L

    fun loadPeripheral(fileName: String, text: String): PeripheralProfile =
        loadPeripheralValue(fileName, ContractJson.parse(text))

    /** 파일 읽기와 format-tests/load-cases.json 의 사례가 같은 검증 경로를 지나게 값을 받는 진입점. */
    fun loadPeripheralValue(fileName: String, value: Any?): PeripheralProfile {
        val root = value.asObject("기기 정의")
        root.onlyKeys("기기 정의", "format", "name", "summary", "advertisedName", "services", "characteristics")
        root.requireFormat()
        val name = root.string("name")
        if (name != fileName) throw ContractFormatException("기기 name '$name' 이 파일 이름 '$fileName' 과 다르다")
        val services = (root["services"]?.asObject("services") ?: emptyMap()).mapValues { (symbol, raw) ->
            val c = raw.asObject(symbol)
            c.onlyKeys(symbol, "uuid", "advertised")
            ServiceSpec(symbol, c.uuid("uuid"), c.bool("advertised"))
        }
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
        return PeripheralProfile(name, root.string("advertisedName"), services, chars)
    }

    fun loadScenario(fileName: String, text: String, peripherals: Map<String, PeripheralProfile>): Scenario =
        loadScenarioValue(fileName, ContractJson.parse(text), peripherals)

    fun loadScenarioValue(fileName: String, value: Any?, peripherals: Map<String, PeripheralProfile>): Scenario {
        val root = value.asObject("시나리오")
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

        val expect = root.req("expect").asObject("expect").mapValues { (lane, raw) ->
            if (lane !in Lanes.ALL) throw ContractFormatException("expect 의 모르는 레인 '$lane'")
            raw.asList("expect.$lane").map { it as? String ?: throw ContractFormatException("expect.$lane 은 문자열 목록이다") }
        }
        val names = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()
        val steps = root.req("steps").asList("steps").mapIndexed { index, raw ->
            parseStep("steps[$index]", raw.asObject("steps[$index]"), profile, names, cancelled, expect.keys, runsOn)
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
        cancelled: MutableSet<String>,
        expectLanes: Set<String>,
        runsOn: Set<String>,
    ): Step {
        val kinds = listOf("call", "await", "device", "sleep", "adapter", "cancel").filter { it in s }
        if (kinds.size != 1) throw ContractFormatException("$where: call·await·device·sleep·adapter·cancel 중 정확히 하나가 있어야 한다")
        return when (kinds.single()) {
            "call" -> parseCall(where, s, profile, names)
            "cancel" -> {
                s.onlyKeys(where, "cancel")
                val name = s.string("cancel")
                if (name !in names) throw ContractFormatException("$where: 앞선 async 호출 '$name' 이 없다")
                if (!cancelled.add(name)) throw ContractFormatException("$where: '$name' 을 이미 취소했다")
                Step.Cancel(name)
            }
            "await" -> {
                val timeout = s.long("timeoutMs", DEFAULT_TIMEOUT_MS)
                val what = s.string("await")
                when {
                    what == "result" -> {
                        s.onlyKeys(where, "await", "name", "timeoutMs")
                        val name = s.string("name")
                        if (name !in names) throw ContractFormatException("$where: 앞선 async 호출 '$name' 이 없다")
                        Step.AwaitResult(name, timeout)
                    }
                    what in Lanes.AWAIT_IS -> {
                        requireLaneRecorded(where, what, expectLanes)
                        s.onlyKeys(where, "await", "is", "timeoutMs")
                        Step.AwaitLane(what, s.string("is"), timeout)
                    }
                    what in Lanes.AWAIT_COUNT -> {
                        requireLaneRecorded(where, what, expectLanes)
                        s.onlyKeys(where, "await", "count", "timeoutMs")
                        Step.AwaitCount(what, s.long("count").toInt().also { it.positive(where) }, timeout)
                    }
                    else -> throw ContractFormatException("$where: 모르는 await '$what'")
                }
            }
            "device" -> {
                val what = s.string("device")
                if (what == "rxCheck") {
                    s.onlyKeys(where, "device")
                    if (Lanes.PEER !in expectLanes) throw ContractFormatException("$where: rxCheck 는 expect 에 peer 레인이 있어야 한다")
                    return Step.RxCheck
                }
                val event = when (what) {
                    "linkDrop" -> { s.onlyKeys(where, "device"); DeviceEvent.LinkDrop }
                    "linkDropHide" -> { s.onlyKeys(where, "device", "ms"); DeviceEvent.LinkDropHide(s.long("ms").also { it.toInt().positive(where) }) }
                    "notify" -> { s.onlyKeys(where, "device", "count"); DeviceEvent.Notify(s.long("count").toInt().also { it.positive(where) }) }
                    "readNotify" -> { s.onlyKeys(where, "device", "on"); DeviceEvent.ReadNotify(s.bool("on")) }
                    else -> throw ContractFormatException("$where: 모르는 기기 사건 '$what'")
                }
                Step.Device(event)
            }
            "adapter" -> {
                s.onlyKeys(where, "adapter")
                if ("device" in runsOn) throw ContractFormatException("$where: adapter 단계는 sim 전용이라 runsOn 에 device 가 있으면 쓸 수 없다")
                val value = s.string("adapter")
                if (value !in ADAPTER_VALUES) throw ContractFormatException("$where: adapter 의 모르는 값 '$value'")
                Step.SetAdapter(value)
            }
            else -> {
                s.onlyKeys(where, "sleep")
                Step.Sleep(s.long("sleep").also { if (it < 0) throw ContractFormatException("$where: sleep 은 0 이상") })
            }
        }
    }

    private fun requireLaneRecorded(where: String, lane: String, expectLanes: Set<String>) {
        if (lane in Lanes.EXTENSION && lane !in expectLanes) {
            throw ContractFormatException("$where: '$lane' 레인을 기다리려면 expect 에 그 레인이 있어야 한다")
        }
    }

    private fun parseCall(where: String, s: Map<String, Any?>, profile: PeripheralProfile, names: MutableSet<String>): Step.Call {
        val op = CallOp.values().firstOrNull { it.key == s["call"] }
            ?: throw ContractFormatException("$where: 모르는 call '${s["call"]}'")
        val common = arrayOf("call", "async", "name", "timeoutMs")
        val needsChar = op in setOf(CallOp.Read, CallOp.Write, CallOp.Subscribe, CallOp.Unsubscribe)
        when (op) {
            CallOp.Connect -> s.onlyKeys(where, *common, "autoReconnect")
            CallOp.Write -> s.onlyKeys(where, *common, "char", "text", "hex", "pattern", "mode", "chunking", "chunkBytes")
            CallOp.StartScan -> s.onlyKeys(where, *common, "filter")
            CallOp.Read, CallOp.Subscribe, CallOp.Unsubscribe -> s.onlyKeys(where, *common, "char")
            CallOp.Disconnect, CallOp.StopScan, CallOp.Snapshot, CallOp.Close -> s.onlyKeys(where, *common)
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
        var chunking: BleChunking = BleChunking.Automatic
        if (op == CallOp.Write) {
            val given = listOf("text", "hex", "pattern").filter { it in s }
            if (given.size != 1) throw ContractFormatException("$where: write 는 text·hex·pattern 중 정확히 하나만 준다")
            bytes = when (given.single()) {
                "text" -> s.string("text").toByteArray(Charsets.UTF_8)
                "hex" -> s.string("hex").hexBytes(where)
                else -> patternBytes(where, s.long("pattern"))
            }
            withResponse = when (val mode = s["mode"] ?: "withResponse") {
                "withResponse" -> true
                "withoutResponse" -> false
                else -> throw ContractFormatException("$where: 모르는 mode '$mode'")
            }
            chunking = parseChunking(where, s)
        }
        val filter = if (op == CallOp.StartScan) s["filter"]?.let { parseFilter(where, it, profile) } else null
        val async = s.bool("async", false)
        val name = s["name"]?.let { it as? String ?: throw ContractFormatException("$where: name 은 문자열이다") }
        if (async && name == null) throw ContractFormatException("$where: async 호출에는 name 이 있어야 한다")
        if (!async && name != null) throw ContractFormatException("$where: name 은 async 호출에만 쓴다")
        if (name != null && !names.add(name)) throw ContractFormatException("$where: name '$name' 이 겹친다")
        return Step.Call(
            op = op,
            char = char,
            autoReconnect = s.bool("autoReconnect", false),
            bytes = bytes,
            withResponse = withResponse,
            chunking = chunking,
            filter = filter,
            async = async,
            name = name,
            timeoutMs = s.long("timeoutMs", DEFAULT_TIMEOUT_MS),
        )
    }

    /** 바이트 i 가 i mod 256. 256 의 배수 길이라 여러 번에 나눠 써도 누적 바이트가 같은 규칙을 따른다. */
    private fun patternBytes(where: String, n: Long): ByteArray {
        if (n !in PATTERN_MIN..PATTERN_MAX || n % 256 != 0L) {
            throw ContractFormatException("$where: pattern 은 256 의 배수로 $PATTERN_MIN~$PATTERN_MAX 이어야 한다(실제 $n)")
        }
        return ByteArray(n.toInt()) { (it % 256).toByte() }
    }

    private fun parseChunking(where: String, s: Map<String, Any?>): BleChunking {
        val kind = (s["chunking"] ?: "automatic").let { it as? String ?: throw ContractFormatException("$where: chunking 은 문자열이다") }
        val hasBytes = "chunkBytes" in s
        return when (kind) {
            "automatic", "disabled" -> {
                if (hasBytes) throw ContractFormatException("$where: chunkBytes 는 chunking 이 fixed 일 때만 쓴다")
                if (kind == "automatic") BleChunking.Automatic else BleChunking.Disabled
            }
            "fixed" -> {
                if (!hasBytes) throw ContractFormatException("$where: chunking fixed 에는 chunkBytes 가 있어야 한다")
                val n = s.long("chunkBytes")
                if (n < 1 || n > Int.MAX_VALUE) throw ContractFormatException("$where: chunkBytes 는 1 이상이어야 한다")
                BleChunking.Fixed(n.toInt())
            }
            else -> throw ContractFormatException("$where: 모르는 chunking '$kind'")
        }
    }

    private fun parseFilter(where: String, raw: Any, profile: PeripheralProfile): ScanFilterSpec {
        val f = raw.asObject("$where.filter")
        f.onlyKeys("$where.filter", "name", "services")
        val name = f["name"]?.let { it as? String ?: throw ContractFormatException("$where.filter: name 은 문자열이다") }
        val services = if ("services" in f) {
            val symbols = f.stringList("services")
            if (symbols.isEmpty()) throw ContractFormatException("$where.filter: services 가 비었다")
            symbols.map { profile.services[it] ?: throw ContractFormatException("$where.filter: 기기 '${profile.name}' 에 서비스 '$it' 이 없다") }
        } else {
            emptyList()
        }
        return ScanFilterSpec(name, services)
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

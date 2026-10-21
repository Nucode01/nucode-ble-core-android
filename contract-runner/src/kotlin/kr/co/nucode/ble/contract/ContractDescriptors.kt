package kr.co.nucode.ble.contract

import java.util.UUID
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.DescriptorRef

/** 기기 정의의 descriptor 하나(README §기기 정의 파일, CANDIDATE-32). [value] 는 연결을 시작할 때의 값이다. */
internal class DescriptorSpec(
    val name: String,
    val uuid: UUID,
    val properties: Set<String>,
    val value: ByteArray?,
)

/** descriptor 형식 — 기기 정의 `descriptors` 읽기, 시나리오 `desc` 해석과 호출 검사. */
internal object ContractDescriptors {
    private val NAME = Regex("[a-z0-9]+")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** characteristic [symbol] 의 `descriptors`(없으면 빈 표). 객체의 순서를 지킨다(탐색 순서). */
    fun parse(symbol: String, raw: Any?): Map<String, DescriptorSpec> {
        if (raw == null) return emptyMap()
        val seen = mutableSetOf<UUID>()
        return raw.asObject("$symbol.descriptors").mapValues { (name, value) ->
            val where = "$symbol.descriptors.$name"
            if (!NAME.matches(name)) throw ContractFormatException("$where: 이름은 소문자·숫자만 쓴다")
            val d = value.asObject(where)
            d.onlyKeys(where, "uuid", "properties", "value")
            val uuid = d.uuid("uuid")
            if (!seen.add(uuid)) throw ContractFormatException("$where: 같은 characteristic 안에서 uuid 가 겹친다")
            val properties = d.stringList("properties").onEach { it.oneOf("$where.properties", "read", "write") }
            if (properties.isEmpty() || properties.toSet().size != properties.size) {
                throw ContractFormatException("$where: properties 는 read·write 중 하나 이상, 중복 없이")
            }
            val initial = d["value"]?.let { (it as? String ?: throw ContractFormatException("$where: value 는 문자열이다")).hexBytes(where) }
            if (initial != null && uuid == CCCD) throw ContractFormatException("$where: CCCD 에는 value 를 쓰지 않는다(값은 구독 상태다)")
            DescriptorSpec(name, uuid, properties.toSet(), initial)
        }
    }

    /** `<characteristic 기호>/<descriptor 이름>` → 두 정의. runnerOnly characteristic 도 받는다(값 호출만 막는다). */
    fun resolve(where: String, desc: String, profile: PeripheralProfile): Pair<CharacteristicSpec, DescriptorSpec> {
        val parts = desc.split('/')
        if (parts.size != 2) throw ContractFormatException("$where: desc 는 '<characteristic>/<descriptor>' 다: $desc")
        val spec = profile.spec(parts[0])
        val descriptor = spec.descriptors[parts[1]]
            ?: throw ContractFormatException("$where: '${parts[0]}' 에 descriptor '${parts[1]}' 가 없다")
        return spec to descriptor
    }

    /** readDescriptor 는 read, writeDescriptor 는 write 속성이 있어야 한다. */
    fun check(where: String, desc: String, op: CallOp, profile: PeripheralProfile) {
        val needed = if (op == CallOp.ReadDescriptor) "read" else "write"
        if (needed !in resolve(where, desc, profile).second.properties) {
            throw ContractFormatException("$where: '$desc' 에 $needed 속성이 없다")
        }
    }

    /** writeDescriptor 의 값: `text`·`hex` 중 정확히 하나. */
    fun value(where: String, s: Map<String, Any?>): ByteArray {
        val given = listOf("text", "hex").filter { it in s }
        if (given.size != 1) throw ContractFormatException("$where: writeDescriptor 는 text·hex 중 정확히 하나만 준다")
        return if (given.single() == "text") s.string("text").toByteArray(Charsets.UTF_8) else s.string("hex").hexBytes(where)
    }

    /** 공개 API 참조: characteristic ref, 정의의 uuid, instanceIndex 0. */
    fun ref(desc: String, profile: PeripheralProfile): DescriptorRef {
        val (spec, descriptor) = resolve("desc", desc, profile)
        return DescriptorRef(spec.ref, BleUuid(descriptor.uuid))
    }
}

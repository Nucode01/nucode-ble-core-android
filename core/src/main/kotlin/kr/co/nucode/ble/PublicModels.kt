package kr.co.nucode.ble

import java.util.UUID
import java.util.Collections
import kotlin.coroutines.CoroutineContext

/** Opaque SDK-local identifier for a discovered peripheral. */
@JvmInline
public value class PeripheralId(public val value: String)

/** Platform-neutral UUID wrapper used by the common public API. */
@JvmInline
public value class BleUuid(public val value: UUID)

/** Exact service instance reference within a discovered topology. */
public data class ServiceRef(
    val uuid: BleUuid,
    val instanceIndex: Int = 0,
) {
    init {
        require(instanceIndex >= 0) { "instanceIndex must be non-negative" }
    }
}

/** Exact characteristic instance reference within a discovered service instance. */
public data class CharacteristicRef(
    val service: ServiceRef,
    val uuid: BleUuid,
    val instanceIndex: Int = 0,
) {
    init {
        require(instanceIndex >= 0) { "instanceIndex must be non-negative" }
    }
}

/**
 * Structured advertisement fields shared by Android and iOS.
 *
 * Byte values cross the boundary by copy in both directions so callers cannot mutate a stored
 * advertisement after construction or through a property getter.
 */
public class AdvertisementData(
    public val localName: String?,
    serviceUuids: Set<BleUuid>,
    manufacturerData: Map<Int, ByteArray>,
    serviceData: Map<BleUuid, ByteArray>,
    public val txPower: Int?,
    public val isConnectable: Boolean?,
) {
    public val serviceUuids: Set<BleUuid> = serviceUuids.toSet()
    private val manufacturerDataCopy: Map<Int, ByteArray> = manufacturerData.copyByteMap()
    private val serviceDataCopy: Map<BleUuid, ByteArray> = serviceData.copyByteMap()

    public val manufacturerData: Map<Int, ByteArray>
        get() = manufacturerDataCopy.copyByteMap()

    public val serviceData: Map<BleUuid, ByteArray>
        get() = serviceDataCopy.copyByteMap()

    override fun equals(other: Any?): Boolean =
        other is AdvertisementData &&
            localName == other.localName &&
            serviceUuids == other.serviceUuids &&
            manufacturerDataCopy.byteMapEquals(other.manufacturerDataCopy) &&
            serviceDataCopy.byteMapEquals(other.serviceDataCopy) &&
            txPower == other.txPower &&
            isConnectable == other.isConnectable

    override fun hashCode(): Int {
        var result = localName?.hashCode() ?: 0
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + manufacturerDataCopy.byteMapHashCode()
        result = 31 * result + serviceDataCopy.byteMapHashCode()
        result = 31 * result + (txPower ?: 0)
        result = 31 * result + (isConnectable?.hashCode() ?: 0)
        return result
    }
}

public data class ScanResult(
    val peripheralId: PeripheralId,
    val rssi: Int,
    val advertisement: AdvertisementData,
)

public data class ScanRequest(
    val filter: BleScanFilter = BleScanFilter(),
    val options: BleScanOptions = BleScanOptions(),
)

public class BleManufacturerDataFilter(
    public val companyId: Int,
    data: ByteArray? = null,
    mask: ByteArray? = null,
) {
    private val dataCopy: ByteArray? = data?.copyOf()
    private val maskCopy: ByteArray? = mask?.copyOf()

    public val data: ByteArray?
        get() = dataCopy?.copyOf()
    public val mask: ByteArray?
        get() = maskCopy?.copyOf()

    init {
        require(companyId in 0..0xFFFF) { "companyId must be a 16-bit unsigned value" }
        require(mask == null || data != null) { "mask requires data" }
        require(mask == null || mask.size == data!!.size) { "mask and data must have equal lengths" }
    }

    override fun equals(other: Any?): Boolean =
        other is BleManufacturerDataFilter && companyId == other.companyId &&
            dataCopy.contentEqualsNullable(other.dataCopy) && maskCopy.contentEqualsNullable(other.maskCopy)

    override fun hashCode(): Int =
        31 * (31 * companyId + (dataCopy?.contentHashCode() ?: 0)) + (maskCopy?.contentHashCode() ?: 0)
}

public class BleServiceDataFilter(
    public val serviceUuid: BleUuid,
    data: ByteArray? = null,
    mask: ByteArray? = null,
) {
    private val dataCopy: ByteArray? = data?.copyOf()
    private val maskCopy: ByteArray? = mask?.copyOf()

    public val data: ByteArray?
        get() = dataCopy?.copyOf()
    public val mask: ByteArray?
        get() = maskCopy?.copyOf()

    init {
        require(mask == null || data != null) { "mask requires data" }
        require(mask == null || mask.size == data!!.size) { "mask and data must have equal lengths" }
    }

    override fun equals(other: Any?): Boolean =
        other is BleServiceDataFilter && serviceUuid == other.serviceUuid &&
            dataCopy.contentEqualsNullable(other.dataCopy) && maskCopy.contentEqualsNullable(other.maskCopy)

    override fun hashCode(): Int =
        31 * (31 * serviceUuid.hashCode() + (dataCopy?.contentHashCode() ?: 0)) +
            (maskCopy?.contentHashCode() ?: 0)
}

/**
 * Scan matching policy captured by value.
 *
 * The lists and byte-bearing filter entries are copied at this API boundary so a scan can retain
 * one stable policy even when its caller reuses mutable request-building collections.
 */
public class BleScanFilter(
    serviceUuids: List<BleUuid> = emptyList(),
    public val namePrefix: String? = null,
    manufacturerData: List<BleManufacturerDataFilter> = emptyList(),
    serviceData: List<BleServiceDataFilter> = emptyList(),
) {
    public val serviceUuids: List<BleUuid> = serviceUuids.immutableCopy()
    public val manufacturerData: List<BleManufacturerDataFilter> =
        manufacturerData.map { BleManufacturerDataFilter(it.companyId, it.data, it.mask) }.immutableCopy()
    public val serviceData: List<BleServiceDataFilter> =
        serviceData.map { BleServiceDataFilter(it.serviceUuid, it.data, it.mask) }.immutableCopy()

    override fun equals(other: Any?): Boolean =
        other is BleScanFilter &&
            serviceUuids == other.serviceUuids &&
            namePrefix == other.namePrefix &&
            manufacturerData == other.manufacturerData &&
            serviceData == other.serviceData

    override fun hashCode(): Int {
        var result = serviceUuids.hashCode()
        result = 31 * result + (namePrefix?.hashCode() ?: 0)
        result = 31 * result + manufacturerData.hashCode()
        result = 31 * result + serviceData.hashCode()
        return result
    }
}

public data class BleScanOptions(val mode: BleScanMode = BleScanMode.Balanced)

public enum class BleScanMode { LowPower, Balanced, LowLatency }

public sealed interface ConnectionState {
    public data class Disconnected(val reason: BleDisconnectReason) : ConnectionState
    public data object Connecting : ConnectionState
    public data object DiscoveringServices : ConnectionState
    public data object Configuring : ConnectionState
    public data object Ready : ConnectionState
    public data object Disconnecting : ConnectionState
    public data class Recovering(
        val attempt: Int,
        val retryAfterMillis: Long,
        val lastFailure: BleFailure,
    ) : ConnectionState {
        init {
            require(attempt >= 0) { "attempt must be non-negative" }
            require(retryAfterMillis >= 0) { "retryAfterMillis must be non-negative" }
        }
    }
}

public sealed interface BleDisconnectReason {
    public data object Initial : BleDisconnectReason
    public data object Requested : BleDisconnectReason
    public data object ClientClosed : BleDisconnectReason
    public data class Failed(val failure: BleFailure) : BleDisconnectReason
}

public data class BleConnectOptions(
    val reconnectPolicy: BleReconnectPolicy = BleReconnectPolicy.Disabled,
)

public sealed interface BleReconnectPolicy {
    public data object Disabled : BleReconnectPolicy
    public data class ExponentialBackoff(
        val maxAttempts: Int,
        val initialDelayMillis: Long,
        val maxDelayMillis: Long,
    ) : BleReconnectPolicy {
        init {
            require(maxAttempts >= 0) { "maxAttempts must be non-negative" }
            require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
            require(maxDelayMillis >= initialDelayMillis) {
                "maxDelayMillis must be at least initialDelayMillis"
            }
        }
    }
}

public data class GattService(
    val ref: ServiceRef,
    val characteristics: List<GattCharacteristic>,
)

public data class GattCharacteristic(
    val ref: CharacteristicRef,
    val properties: Set<CharacteristicProperty>,
)

public enum class CharacteristicProperty {
    Read, WriteWithResponse, WriteWithoutResponse, Notify, Indicate,
}

public data class BleWriteOptions(
    val mode: BleWriteMode = BleWriteMode.WithResponse,
    val chunking: BleChunking = BleChunking.Automatic,
)

public enum class BleWriteMode { WithResponse, WithoutResponse }

public sealed interface BleChunking {
    public data object Automatic : BleChunking
    public data object Disabled : BleChunking
    public data class Fixed(val maxBytes: Int) : BleChunking {
        init {
            require(maxBytes > 0) { "maxBytes must be positive" }
        }
    }
}

public data class WriteReceipt(
    val bytesCompleted: Int,
    val chunksCompleted: Int,
    val delivery: DeliverySemantics,
) {
    init {
        require(bytesCompleted >= 0) { "bytesCompleted must be non-negative" }
        require(chunksCompleted >= 0) { "chunksCompleted must be non-negative" }
    }
}

public data class ClientSnapshot(
    val adapterState: AdapterState,
    val isScanning: Boolean,
    val sessions: List<SessionSnapshot>,
)

public data class SessionSnapshot(
    val peripheralId: PeripheralId,
    val connectionState: ConnectionState,
    val services: List<GattService>,
    /**
     * The caller's desired subscription intent, not the CCCD activation state of the current
     * connection generation. Intent survives recovery and is restored before Ready.
     */
    val subscriptions: Set<CharacteristicRef>,
)

/** Official common event stream; legacy [BleEvent] remains a separate compatibility projection. */
public sealed interface BleClientEvent

/**
 * 어댑터 상태. Unauthorized(canRequest) 로 요청 가능/영구 거부를 구분한다 —
 * 안 하면 제품이 "권한 허용" 버튼을 띄우고 눌러도 아무 일이 없다(설계 §5).
 */
public sealed interface AdapterState {
    public data object Unsupported : AdapterState

    public data class Unauthorized(
        /**
         * D7(M-8+#16) — 의미 확정. `false` = 필요 권한이 매니페스트에 선언되지 않음
         * (요청해도 소용없음 — 앱 빌드 결함). `true` = 선언은 됐고 런타임 허용만 안 됨
         * (요청할 가치 있음). 영구 거부(다시 묻지 않음) 구분은 `Activity` 가 있어야 판단
         * 가능해 코어 범위 밖이다 — 앱이 `shouldShowRequestPermissionRationale` 로 판단한다.
         */
        val canRequest: Boolean,
    ) : AdapterState

    public data object PoweredOff : AdapterState
    public data object Resetting : AdapterState
    public data object Ready : AdapterState
}

/**
 * 코어 배선 설정. dispatcher / eventBufferCapacity / operationTimeoutMillis와 lifecycle
 * 단계별 timeout의 주입 지점이다. 연결별 재연결 정책은 [BleConnectOptions]가 소유한다.
 *
 * dispatcher 가 null 이면 BleRuntime 가 자기 기본 디스패처(단일 스레드 확정)를 만든다.
 * 주입은 테스트에서 runTest 디스패처를 넣기 위한 것이다.
 *
 * ★ 주입 시 주의(D10/review L-2):
 * - **Job 을 포함한 컨텍스트를 넣지 마라** — 명령의 withContext 가 호출자 Job 을 그
 *   Job 으로 덮어 소비자의 withTimeout·취소가 전파되지 않게 된다(review #7 의 부활 경로).
 *   디스패처(+필요시 이름)만 넣어라.
 * - **단일 스레드로 확정되는 디스패처만 넣어라.** 근거는 FIFO 순서가 아니라 `Session` 상태
 *   단일 스레드 확정이다(plan `runtime-no-runblocking/v2` review 정정) — `Session`(state/mtu/…)
 *   변경은 전부 이 디스패처 위에서만 일어난다는 게 설계 §3 규칙 2 의 불변이고, 그 불변은
 *   디스패처가 실제로 단일 스레드일 때만 성립한다(병렬 디스패처를 넣으면 `Session` 필드에
 *   `Mutex` 없이 동시 쓰기가 생긴다). `close()` 는 더 이상 호출자를 블록하지 않는다(plan
 *   `runtime-no-runblocking` task 2(B)) — 정리는 이 디스패처 위에 예약되고 `close()` 는 즉시
 *   반환한다. 이 계약 덕에 `Dispatchers.Main` 이나 `StandardTestDispatcher` 를 넣고
 *   `session()`/`connect()`/`close()` 를 그 디스패처 위에서 직접 불러도 더는 데드락하지 않는다 —
 *   문서화된 테스트 seam(`StandardTestDispatcher`)이 이제 모든 공개 호출에 대해 실제로 동작한다.
 *   `close()` 가 반환하는 시점에 플랫폼 연결 핸들 정리가 아직 안 끝났을 수 있다 — 그 직후
 *   프로세스가 바로 종료되면(예: 테스트 프로세스, 강제 종료) 남은 핸들 회수는 플랫폼이
 *   프로세스 죽음 시점에 담당한다(코어가 보장하는 범위 밖).
 *
 * @param operationTimeoutMillis write ack 와 CCCD ack 공용 대기 상한(plan 결정③). 느린 기기·
 * 혼잡 환경이면 늘려라.
 * @param linkTimeoutMillis connect 시작부터 link callback까지의 상한.
 * @param discoveryTimeoutMillis service discovery callback 대기 상한.
 * @param configurationTimeoutMillis MTU 등 필수 연결 설정 callback 대기 상한.
 * @param disconnectCleanupTimeoutMillis disconnect callback이 누락됐을 때 local cleanup을
 * 강제하는 상한.
 * @param rediscoveryTimeoutMillis Bluetooth 재시작 등으로 플랫폼 LE 캐시가 비어 있을 때 connect
 * 앞에 도는 주소 필터 스캔의 상한. link timeout 안에서 소비된다.
 */
public data class BleConfig public constructor(
    public val dispatcher: CoroutineContext? = null,
    public val eventBufferCapacity: Int = 64,
    public val operationTimeoutMillis: Long = 5_000,
    public val linkTimeoutMillis: Long = 15_000,
    public val discoveryTimeoutMillis: Long = 10_000,
    public val configurationTimeoutMillis: Long = 10_000,
    public val disconnectCleanupTimeoutMillis: Long = 5_000,
    public val rediscoveryTimeoutMillis: Long = 3_000,
    /** Optional non-authoritative diagnostics sink. Null keeps diagnostics delivery disabled. */
    public val logger: BleLogger? = null,
    /** Privacy-first diagnostic capture policy; raw payload capture is disabled by default. */
    public val diagnosticOptions: DiagnosticOptions = DiagnosticOptions(),
) {
    init {
        require(operationTimeoutMillis > 0) {
            "operationTimeoutMillis 는 0보다 커야 한다: $operationTimeoutMillis"
        }
        require(linkTimeoutMillis > 0) {
            "linkTimeoutMillis 는 0보다 커야 한다: $linkTimeoutMillis"
        }
        require(discoveryTimeoutMillis > 0) {
            "discoveryTimeoutMillis 는 0보다 커야 한다: $discoveryTimeoutMillis"
        }
        require(configurationTimeoutMillis > 0) {
            "configurationTimeoutMillis 는 0보다 커야 한다: $configurationTimeoutMillis"
        }
        require(disconnectCleanupTimeoutMillis > 0) {
            "disconnectCleanupTimeoutMillis 는 0보다 커야 한다: $disconnectCleanupTimeoutMillis"
        }
        require(rediscoveryTimeoutMillis > 0) { "rediscoveryTimeoutMillis 는 0보다 커야 한다: $rediscoveryTimeoutMillis" }
        require(rediscoveryTimeoutMillis <= linkTimeoutMillis) {
            "rediscoveryTimeoutMillis($rediscoveryTimeoutMillis) 는 linkTimeoutMillis($linkTimeoutMillis) 이하여야 한다"
        }
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else this.contentEquals(other)

private fun <K> Map<K, ByteArray>.copyByteMap(): Map<K, ByteArray> =
    entries.associate { (key, value) -> key to value.copyOf() }

private fun <K> Map<K, ByteArray>.byteMapEquals(other: Map<K, ByteArray>): Boolean =
    size == other.size && entries.all { (key, value) -> other[key]?.contentEquals(value) == true }

private fun <K> Map<K, ByteArray>.byteMapHashCode(): Int =
    entries.fold(0) { result, (key, value) -> result + (key.hashCode() xor value.contentHashCode()) }

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(toList())

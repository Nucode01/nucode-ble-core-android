package kr.co.nucode.ble

import java.util.UUID
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * Opaque SDK-local identifier for a discovered peripheral.
 *
 * Android normalizes the id to an upper-case MAC address at the library entry points, so ids that differ
 * only in letter case name the same session; sessions, events and snapshots report the upper-case form.
 */
@JvmInline
public value class PeripheralId(public val value: String)

internal fun PeripheralId.normalized(): PeripheralId {
    val upper = value.uppercase(Locale.ROOT)
    return if (upper == value) this else PeripheralId(upper)
}

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

public data class ScanResult(
    val peripheralId: PeripheralId,
    val rssi: Int,
    val advertisement: AdvertisementData,
)

public data class ScanRequest(
    val filter: BleScanFilter = BleScanFilter(),
    val options: BleScanOptions = BleScanOptions(),
)

/** 스캔 자원 상태. 멈춘 이유를 상태에 싣는다 — 이벤트는 버퍼가 차면 버려질 수 있다. */
public sealed interface ScanState {
    /** 스캔하지 않는 중. [lastFailure] 는 마지막 스캔이 실패로 멈췄으면 그 실패, 정상 정지·초기면 null. */
    public data class Idle(val lastFailure: BleFailure?) : ScanState
    public data class Scanning(val request: ScanRequest) : ScanState
}

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
    public data class Recovering(val lastFailure: BleFailure) : ConnectionState
}

public sealed interface BleDisconnectReason {
    public data object Initial : BleDisconnectReason
    public data object Requested : BleDisconnectReason
    public data object ClientClosed : BleDisconnectReason
    public data class Failed(val failure: BleFailure) : BleDisconnectReason
}

/**
 * 연결 옵션.
 *
 * [autoReconnect] 가 true 면 Android `connectGatt(autoConnect = true)` 로 연결한다. 링크가 끊겨도
 * 플랫폼 handle 을 열어 두고 OS 재연결을 기다리며, 그동안 상태는 `Recovering` 이다. OS 가 다시
 * 붙이면 첫 연결과 같은 경로(서비스 탐색 → MTU → 구독 복원 → Ready)를 탄다. 첫 연결에도 link
 * deadline 이 없다(OS 가 무기한 기다린다) — 포기는 `disconnect()` 로 한다.
 *
 * 이 클라이언트가 최근에 본 적 없는(cold) 주소는 연결 전에 짧은 스캔으로 먼저 찾는다. `autoReconnect` 면
 * 스캔에서 못 찾아도(`Diagnostic(PERIPHERAL_NOT_FOUND)`, 스캔을 못 쓰면 그 실패) 연결은 OS 대기로 이어진다.
 * `autoReconnect = false` 면 못 찾을 때 `PERIPHERAL_NOT_FOUND` 로 던진다.
 *
 * - `Recovering` 중 다른 옵션으로 `connect()` 하면 `CONNECTION_OPTIONS_CONFLICT` 로 실패한다(같은 옵션은
 *   대기를 유지하는 no-op). 옵션을 바꾸려면 먼저 `disconnect()` 한 뒤 다시 연결한다.
 * - 어댑터가 꺼져 `Disconnected(Failed(ADAPTER_UNAVAILABLE))` 로 끝난 `autoReconnect` 세션은 어댑터가 다시
 *   켜지면 한 번 다시 연결을 건다(예약·재시도 없음). 그 사이 `disconnect()` 하면 걸지 않는다.
 */
public data class BleConnectOptions(val autoReconnect: Boolean = false)

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

/** Official common event stream; legacy [kr.co.nucode.ble.internal.model.BleEvent] remains a separate compatibility projection. */
public sealed interface BleClientEvent

/**
 * 어댑터 상태. Unauthorized(canRequest) 로 요청 가능/영구 거부를 구분한다 —
 * 안 하면 제품이 "권한 허용" 버튼을 띄우고 눌러도 아무 일이 없다(설계 §5).
 *
 * [Unknown] 은 아직 판정되지 않은 상태다(iOS 가 첫 판정을 내기 전). Android 는 이 값을 내지 않는다.
 * connect·startScan 같은 명령은 Unknown 이면 판정될 때까지 최대 2000ms 기다린 뒤, 그래도 Unknown 이면
 * `ADAPTER_UNAVAILABLE` 로 실패한다. 앱은 Unknown 을 "판정 대기"로 다룬다.
 */
public sealed interface AdapterState {
    public data object Unknown : AdapterState

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
 * @param linkTimeoutMillis connect 시작부터 link callback까지의 상한. autoReconnect 연결은 link
 * deadline 이 없다(OS 가 기기를 기다린다).
 * @param discoveryTimeoutMillis service discovery callback 대기 상한.
 * @param configurationTimeoutMillis MTU 등 필수 연결 설정 callback 대기 상한.
 * @param disconnectCleanupTimeoutMillis disconnect callback이 누락됐을 때 local cleanup을
 * 강제하는 상한.
 * @param rediscoveryTimeoutMillis Bluetooth 재시작 등으로 플랫폼 LE 캐시가 비어 있을 때 connect
 * 앞에 도는 주소 필터 스캔의 상한. autoReconnect=false 연결에선 link timeout 안에서 소비되고,
 * autoReconnect 연결에선 link deadline 이 없어 이 상한만 적용된다.
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
        require(eventBufferCapacity >= 1) {
            "eventBufferCapacity 는 1 이상이어야 한다: $eventBufferCapacity"
        }
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

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(toList())

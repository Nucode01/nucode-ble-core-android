package kr.co.nucode.ble

import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** nullable ByteArray 내용 비교. 둘 다 null 이면 같고, 하나만 null 이면 다르다. */
private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    if (this == null || other == null) return this == null && other == null
    return this.contentEquals(other)
}

/**
 * 기기 식별자. 코어 공개 API 전체가 객체 대신 이 값을 주고받는다(설계 §5 규칙 5).
 *
 * Android = MAC 주소, iOS = CBPeripheral.identifier.uuidString.
 * 두 값은 같은 물리 기기라도 다르다 — **플랫폼 간 호환되지 않는다.**
 * 문자열로 직렬화 가능해야 한다 — 이 포맷이 나중 Flutter 브리지의 직렬화 계약이 된다(plan §영향범위).
 */
@JvmInline
public value class DeviceId(public val value: String) {
    override fun toString(): String = value
}

/**
 * characteristic 참조. instanceIndex 가 없으면 같은 UUID 를 중복 노출하는 기기에서
 * 엉뚱한 characteristic 에 write 할 수 있다(설계 §5).
 */
public data class CharRef(
    val serviceUuid: UUID,
    val charUuid: UUID,
    val instanceIndex: Int = 0,
    /** Same-UUID service ordinal from the most recent discovery snapshot. */
    val serviceInstanceIndex: Int = 0,
)

/** write 방식. Auto 는 characteristic 속성을 보고 코어가 고른다. */
public enum class WriteType {
    WithResponse,
    WithoutResponse,
    Auto,
}

/** 청킹 정책. None 인데 페이로드가 청크 한도를 넘으면 조용히 쪼개지 않고 TooLarge 로 실패한다. */
public sealed interface Chunking {
    public data object Auto : Chunking
    public data object None : Chunking
    /** Split at this size, provided it does not exceed the negotiated link capability. */
    public data class Fixed(val maxBytes: Int) : Chunking {
        init {
            require(maxBytes > 0) { "maxBytes must be positive" }
        }
    }
}

/**
 * write 결과. partiallyDelivered 를 타입으로 강제한다 — 로그 문자열로 흘리면
 * 소비 제품이 무시하고, 기기는 불완전한 명령을 받은 상태가 된다(설계 §5).
 */
public sealed interface WriteResult {
    /** Every chunk completed successfully; the count includes an empty payload's one chunk. */
    public data class Completed(val chunksCompleted: Int) : WriteResult {
        init {
            require(chunksCompleted > 0) { "chunksCompleted must be positive" }
        }
    }

    /**
     * Source-compatibility sentinel for callers which only classified legacy successful writes.
     * New producers return [Completed] so consumers cannot fabricate a chunk count.
     */
    @Deprecated("Use Completed(chunksCompleted)")
    public data object Ok : WriteResult

    /**
     * A write that did not complete. [failure.writeProgress] carries the transport progress and
     * delivery uncertainty; consumers must not infer that the remaining bytes are safe to replay.
     */
    public data class Failed(
        val failure: BleFailure,
    ) : WriteResult

    public data class TooLarge(val size: Int, val max: Int) : WriteResult
}

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
 * 연결 상태. Reconnecting 이 없다 — 재연결은 정책이고 코어는 메커니즘만 갖는다(설계 §5).
 * 추가하지 않는다.
 */
public sealed interface ConnState {
    /** A terminal session state with a stable, cross-platform reason. */
    public data class Disconnected(
        val reason: DisconnectReason = DisconnectReason.Initial,
    ) : ConnState
    public data object Connecting : ConnState

    /** 링크는 연결됐지만 service/characteristic snapshot이 아직 확정되지 않은 단계. */
    public data object DiscoveringServices : ConnState

    /** service snapshot 확정 뒤 MTU 등 필수 연결 설정을 수행하는 단계. */
    public data object Configuring : ConnState

    /** service discovery와 필수 configuration이 끝나 public GATT operation이 가능한 단계. */
    public data object Ready : ConnState

    // Slice 2의 수직 경로 이관 전까지 기존 public command 동작을 보존한다. Task 4가 새
    // 상태로 소비부를 옮긴 뒤 제거 여부를 결정한다.
    public data object Discovering : ConnState
    public data object Connected : ConnState
    public data object Disconnecting : ConnState
}

/**
 * 제조사 데이터 필터. 비콘류 기기는 이것 없이 특정할 수 없다 — 기존 제품군(BeaconRanger)의
 * 실사용 근거로 v0.1 에 포함(plan §원인·배경).
 * [mask] 는 [data] 와 같은 길이여야 한다(바이트별 AND 매칭) — 다르면 즉시 실패(require).
 * data 없이 companyId 만으로도 필터가 성립한다.
 *
 * ★ iOS 비대칭: CoreBluetooth 는 service UUID 만 API 레벨 필터를 지원한다 — iOS 코어는
 * 이 필터를 광고 콜백에서 소프트웨어로 적용한다(Android 의 namePrefix 후처리와 같은 선례).
 * Android 는 플랫폼 ScanFilter 로 내려 하드웨어 오프로드된다.
 *
 * ★ [data]/[mask] 배열은 생성 후 변형하지 마라 — 방어 복사를 하지 않는다(성능·단순성).
 * 변형해도 크래시는 없지만 의도와 다른 필터로 조용히 스캔되고 equals/hashCode 가 흔들린다.
 */
public data class ManufacturerDataFilter(
    val companyId: Int,
    val data: ByteArray? = null,
    val mask: ByteArray? = null,
) {
    init {
        // BLE company identifier 는 16-bit 다(Bluetooth SIG 할당). 범위 밖 값은 광고의
        // 2바이트 ID 와 절대 매칭되지 않아 "영원히 안 잡히는 필터"가 조용히 성립한다 —
        // mask 불일치는 즉시 실패시키면서 이것만 무음이면 비대칭이다(review L).
        require(companyId in 0..0xFFFF) { "companyId 는 16-bit(0..65535)여야 한다: $companyId" }
        if (mask != null) {
            requireNotNull(data) { "mask 만 있고 data 가 없다 — mask 는 data 없이 성립하지 않는다" }
            require(mask.size == data.size) {
                "mask 길이(${mask.size})와 data 길이(${data.size})가 다르다 — 바이트별 AND 매칭이 불가능하다"
            }
        }
    }

    // ByteArray 를 data class 필드로 두면 equals/hashCode 가 참조 비교가 되므로 내용 비교로 직접 구현한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManufacturerDataFilter) return false
        return companyId == other.companyId &&
            data.contentEqualsNullable(other.data) &&
            mask.contentEqualsNullable(other.mask)
    }

    override fun hashCode(): Int {
        var result = companyId
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (mask?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 서비스 데이터 필터. [mask] 는 [data] 와 같은 길이여야 한다(바이트별 AND 매칭) —
 * 다르면 즉시 실패(require). data 없이 serviceUuid 만으로도 필터가 성립한다.
 *
 * ★ iOS 비대칭: CoreBluetooth 는 service **data** 필터도 API 미지원 — iOS 코어가
 * 광고 콜백에서 소프트웨어로 적용한다([ManufacturerDataFilter] 와 동일 규약).
 * ★ [data]/[mask] 배열은 생성 후 변형하지 마라 — [ManufacturerDataFilter] 와 동일.
 */
public data class ServiceDataFilter(
    val serviceUuid: UUID,
    val data: ByteArray? = null,
    val mask: ByteArray? = null,
) {
    init {
        if (mask != null) {
            requireNotNull(data) { "mask 만 있고 data 가 없다 — mask 는 data 없이 성립하지 않는다" }
            require(mask.size == data.size) {
                "mask 길이(${mask.size})와 data 길이(${data.size})가 다르다 — 바이트별 AND 매칭이 불가능하다"
            }
        }
    }

    // ByteArray 를 data class 필드로 두면 equals/hashCode 가 참조 비교가 되므로 내용 비교로 직접 구현한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServiceDataFilter) return false
        return serviceUuid == other.serviceUuid &&
            data.contentEqualsNullable(other.data) &&
            mask.contentEqualsNullable(other.mask)
    }

    override fun hashCode(): Int {
        var result = serviceUuid.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (mask?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 스캔 필터. 최소한만 — service UUID · 이름 접두어 · 제조사 데이터 · 서비스 데이터.
 *
 * OR 매칭 규약: [serviceUuids] · [manufacturerData] · [serviceData] 의 각 항목은 OR —
 * 어느 하나에 걸리면 결과에 포함된다. [namePrefix] 만 AND(후처리)다.
 * iOS 에서는 serviceUuids 만 API 필터이고 나머지는 코어가 소프트웨어 후처리한다 —
 * 결과는 두 플랫폼에서 같고, 하드웨어 오프로드 여부만 다르다.
 * Android 실동작 확인(task 2, Scanner.toPlatformFilters KDoc): `BluetoothLeScanner.startScan`
 * 은 필터 목록을 OR 로 매칭하고 `Builder` 하나 안의 조건은 AND 라 각 항목을 독립된 플랫폼
 * 필터 하나씩으로 매핑한다 — 이 규약과 일치한다.
 */
public data class ScanFilter(
    val serviceUuids: List<UUID> = emptyList(),
    val namePrefix: String? = null,
    val manufacturerData: List<ManufacturerDataFilter> = emptyList(),
    val serviceData: List<ServiceDataFilter> = emptyList(),
)

/** 스캔 전력/지연 모드. 플랫폼 상수를 그대로 노출하지 않고 최소 3단계로만 추상화한다. */
public enum class ScanMode {
    LowPower,
    Balanced,
    LowLatency,
}

/** 스캔 옵션. 최소한만 — 과설계하지 않는다. */
public data class ScanOptions(
    val scanMode: ScanMode = ScanMode.Balanced,
)

/**
 * 연결 옵션. 최소한만.
 *
 * @param timeoutMillis 타임아웃 시 코어는 포기한다(D2) — 자동 재연결을 시작하지 않는다.
 * 재시도는 소비자가 connect() 를 다시 불러야 한다(정책은 밖). null 이면 플랫폼 기본 동작.
 * @param requestedMtu MTU 협상 요청값(plan 결정①). 기본 247 은 Android/iOS 에서 흔한 협상
 * 상한이다(청크 244 바이트). 기기 펌웨어가 큰 MTU 요청에 이상 동작하면 낮춰라. 협상이 실패하면
 * 코어는 기본 23/20 으로 폴백한다(설계 §12 결정).
 */
public data class ConnectOptions(
    /**
     * true 면 플랫폼 백그라운드 연결 — 기기가 전파 범위에 나타날 때까지 시스템이 대기·재연결한다.
     * **재연결 소유권이 플랫폼으로 넘어간다**: 코어 Reconnector(BleConfig.reconnectPolicy)는
     * 이 연결에 대해 예약·실행 모두 하지 않는다(배타 — plan app-tunables v3).
     *
     * 주의: ① [timeoutMillis] 와의 조합 — autoConnect 의 존재 이유가 무기한 대기인데
     * 타임아웃은 그 대기를 포기시킨다. 의도가 아니면 함께 쓰지 마라.
     * ② 연결이 의도적으로 느리다(low duty cycle 스캔). ③ 시스템 캐시에 없는 비본딩
     * 주소로는 조용히 붙지 않을 수 있다 — 실기기 확인 항목.
     */
    val autoConnect: Boolean = false,
    val timeoutMillis: Long? = null,
    val requestedMtu: Int = 247,
) {
    init {
        require(requestedMtu in 23..517) {
            "requestedMtu 는 ATT 스펙 범위(23..517) 안이어야 한다: $requestedMtu"
        }
    }
}

/**
 * 연결 시도에 적용할 link deadline. `null`은 "deadline 없음"이 아니라 [BleConfig]의
 * 기본값을 사용한다는 뜻이므로, 진행 중인 connect의 중복 여부는 원본 nullable 값이 아닌
 * 이 정규화된 값으로 판단해야 한다.
 *
 * link가 이미 성공한 뒤에는 이 값이 현재 연결 동작에 영향을 주지 않는다. 그 시점의
 * duplicate connect 비교는 autoConnect와 requestedMtu만으로 충분하며, `BleCore`가 그
 * lifecycle 경계를 적용한다.
 */
internal fun ConnectOptions.effectiveLinkTimeoutMillis(defaultLinkTimeoutMillis: Long): Long =
    timeoutMillis ?: defaultLinkTimeoutMillis

/**
 * snapshot() 이 기기 하나에 대해 돌려주는 값. 내부 상태 객체를 직접 노출하지 않는다(설계 §5 규칙 5).
 *
 * mtu/chunkSize 는 설계 §6 런타임 flow 다이어그램의 snapshot() 반환값과 대칭이다 —
 * BleEvent.MtuChanged 가 실어 나르는 두 값을 스냅샷도 그대로 갖는다.
 * 연결 전에는 MTU 협상이 일어나지 않으므로 null 로 둔다(23 바이트 기본값을 임의로 채우지 않는다).
 */
public data class DeviceSnapshot(
    val deviceId: DeviceId,
    val connState: ConnState,
    val mtu: Int? = null,
    val chunkSize: Int? = null,
)

/**
 * snapshot() 반환형 — 콜드 스타트(화면 회전 · 새 화면 · hot reload) 대응 장치(설계 §5).
 * 기기별 상태는 DeviceId 기반 값 타입 목록으로만 노출한다.
 */
public data class Snapshot(
    val adapterState: AdapterState,
    val devices: List<DeviceSnapshot>,
)

/**
 * 재연결 정책. 최대 시도 횟수 · 지수 백오프의 밑변 · 상한만 둔다(과설계 금지).
 * 기본값은 스켈레톤 단계의 추정치이고, 설계 §12-5 "재시도 기본값"이 실기기로 확정한다.
 *
 * 설계 §4 "재연결 정책(횟수·백오프·조건)은 주입" 의 주입구다(plan 결정②) — 이전에는
 * `internal` 이라 [BleConfig] 에서 주입할 수 없었다.
 */
public data class ReconnectPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
    /**
     * 재연결 시도 하나의 상한(plan blecore-lifecycle §감사결과 C, 결정 — 공개, 기본값이라
     * 비파괴). 이 시간 안에 `CONNECTED` 콜백이 오지 않으면 이 시도는 **포기가 아니라 다음
     * 백오프로** 넘어간다(`Reconnector.reconnect` 가 `Diagnostic("RECONNECT_ATTEMPT_TIMEOUT")`
     * 를 내고 `gatt.disconnect()` 해 `scheduleReconnect` 가 `retryCount` 로 다음 시도/give-up
     * 을 정한다) — `ConnectOptions.timeoutMillis`(사용자 connect 의 포기)와 의미가 다르다:
     * 정책상 재시도 예산이 아직 남아 있으므로 여기서 완전히 포기하면 안 된다.
     */
    val attemptTimeoutMillis: Long = 30_000,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts 는 음수일 수 없다: $maxAttempts" }
        require(baseDelayMillis > 0) { "baseDelayMillis 는 0보다 커야 한다: $baseDelayMillis" }
        require(maxDelayMillis >= baseDelayMillis) {
            "maxDelayMillis($maxDelayMillis) 는 baseDelayMillis($baseDelayMillis) 이상이어야 한다"
        }
        require(attemptTimeoutMillis > 0) {
            "attemptTimeoutMillis 는 0보다 커야 한다: $attemptTimeoutMillis"
        }
    }
}

/**
 * 코어 배선 설정. dispatcher / eventBufferCapacity / reconnectPolicy / operationTimeoutMillis와
 * lifecycle 단계별 timeout은 각각 설계 §12 미결정 항목(백프레셔 정책·스레드 계약)과
 * §4(재연결 정책 주입)의 주입 지점이다.
 *
 * dispatcher 가 null 이면 BleCore 가 자기 기본 디스패처(단일 스레드 확정)를 만든다.
 * 주입은 테스트에서 runTest 디스패처를 넣기 위한 것이다.
 *
 * ★ 주입 시 주의(D10/review L-2):
 * - **Job 을 포함한 컨텍스트를 넣지 마라** — 명령의 withContext 가 호출자 Job 을 그
 *   Job 으로 덮어 소비자의 withTimeout·취소가 전파되지 않게 된다(review #7 의 부활 경로).
 *   디스패처(+필요시 이름)만 넣어라.
 * - Dispatchers.Main 을 넣고 메인 스레드에서 close() 를 부르면 close() 내부의
 *   runBlocking 이 데드락한다. 코어 디스패처 위에서 close() 호출 금지와 같은 뿌리다.
 *
 * @param reconnectPolicy 재연결 정책(plan 결정②) — Reconnector 가 주입받는다.
 * @param operationTimeoutMillis write ack 와 CCCD ack 공용 대기 상한(plan 결정③). 느린 기기·
 * 혼잡 환경이면 늘려라.
 * @param linkTimeoutMillis connect 시작부터 link callback까지의 기본 상한. 개별
 * [ConnectOptions.timeoutMillis]가 있으면 그 값이 우선한다.
 * @param discoveryTimeoutMillis service discovery callback 대기 상한.
 * @param configurationTimeoutMillis MTU 등 필수 연결 설정 callback 대기 상한.
 * @param disconnectCleanupTimeoutMillis disconnect callback이 누락됐을 때 local cleanup을
 * 강제하는 상한.
 */
public data class BleConfig(
    val dispatcher: CoroutineContext? = null,
    val eventBufferCapacity: Int = 64,
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    val operationTimeoutMillis: Long = 5_000,
    val linkTimeoutMillis: Long = 15_000,
    val discoveryTimeoutMillis: Long = 10_000,
    val configurationTimeoutMillis: Long = 10_000,
    val disconnectCleanupTimeoutMillis: Long = 5_000,
    /** Optional non-authoritative diagnostics sink. Null keeps diagnostics delivery disabled. */
    val logger: BleLogger? = null,
    /** Privacy-first diagnostic capture policy; raw payload capture is disabled by default. */
    val diagnosticOptions: DiagnosticOptions = DiagnosticOptions(),
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
    }
}

/**
 * GATT characteristic 속성. v0.1 범위에서 의미 있는 것만 둔다 —
 * Broadcast/SignedWrite/ExtendedProps 는 v0.1 이 쓰지 않으므로 넣지 않는다.
 */
public enum class CharProperty {
    Read,
    Write,
    WriteNoResponse,
    Notify,
    Indicate,
}

/**
 * ServicesDiscovered 이벤트가 실어 나르는 characteristic 값 타입.
 *
 * properties 없이는 터미널(소비 앱)이 WRITE/NOTIFY 지원 여부를 몰라 목록 UI 를 그릴 수 없고,
 * 결국 터미널이 자기 GATT 접근을 갖게 된다 — 설계 §0 "BLE 코드 0줄" 목표가 깨진다.
 */
public data class DiscoveredCharacteristic(
    val charRef: CharRef,
    val properties: Set<CharProperty>,
)

/** ServicesDiscovered 이벤트가 실어 나르는 service 값 타입. */
public data class DiscoveredService(
    val serviceUuid: UUID,
    val characteristics: List<DiscoveredCharacteristic>,
    /** Same-UUID service ordinal from this discovery snapshot. */
    val instanceIndex: Int = 0,
)

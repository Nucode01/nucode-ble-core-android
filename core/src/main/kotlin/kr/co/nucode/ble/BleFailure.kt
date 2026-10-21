package kr.co.nucode.ble

/**
 * Stable, platform-neutral failure domain. New values are intentionally not an enum so an
 * older Android/iOS/Flutter consumer can retain a code it does not yet understand.
 */
@JvmInline
public value class FailureCategory(public val value: String)

/** Stable, platform-neutral failure code. This is a wire value, not a localized message. */
@JvmInline
public value class FailureCode(public val value: String) {
    /** Public constants for every code in contract/failure-codes.json; the value equals the name. */
    public companion object {
        public val ADAPTER_UNAVAILABLE: FailureCode = FailureCode("ADAPTER_UNAVAILABLE")
        public val BLE_UNSUPPORTED: FailureCode = FailureCode("BLE_UNSUPPORTED")
        public val PERMISSION_DENIED: FailureCode = FailureCode("PERMISSION_DENIED")
        public val SCAN_ALREADY_RUNNING: FailureCode = FailureCode("SCAN_ALREADY_RUNNING")
        public val SCAN_STOPPED_BY_ADAPTER: FailureCode = FailureCode("SCAN_STOPPED_BY_ADAPTER")
        /** Android 전용. */
        public val SCAN_START_FAILED: FailureCode = FailureCode("SCAN_START_FAILED")
        /** Android 전용. */
        public val SCAN_RUNTIME_FAILED: FailureCode = FailureCode("SCAN_RUNTIME_FAILED")
        /** Android 전용. */
        public val SCAN_START_THROTTLED: FailureCode = FailureCode("SCAN_START_THROTTLED")
        /** Android 전용. */
        public val SCANNER_UNAVAILABLE: FailureCode = FailureCode("SCANNER_UNAVAILABLE")
        public val PERIPHERAL_NOT_FOUND: FailureCode = FailureCode("PERIPHERAL_NOT_FOUND")
        public val UNKNOWN_DEVICE: FailureCode = FailureCode("UNKNOWN_DEVICE")
        public val CONNECT_DURING_DISCONNECT: FailureCode = FailureCode("CONNECT_DURING_DISCONNECT")
        public val CONNECTION_OPTIONS_CONFLICT: FailureCode = FailureCode("CONNECTION_OPTIONS_CONFLICT")
        /** Android 전용. */
        public val LINK_START_FAILED: FailureCode = FailureCode("LINK_START_FAILED")
        public val LINK_FAILED: FailureCode = FailureCode("LINK_FAILED")
        public val LINK_TIMEOUT: FailureCode = FailureCode("LINK_TIMEOUT")
        public val SERVICE_DISCOVERY_START_FAILED: FailureCode = FailureCode("SERVICE_DISCOVERY_START_FAILED")
        public val SERVICE_DISCOVERY_FAILED: FailureCode = FailureCode("SERVICE_DISCOVERY_FAILED")
        public val SERVICE_DISCOVERY_TIMEOUT: FailureCode = FailureCode("SERVICE_DISCOVERY_TIMEOUT")
        /** Android 전용. */
        public val CONFIGURATION_FAILED: FailureCode = FailureCode("CONFIGURATION_FAILED")
        public val CONFIGURATION_TIMEOUT: FailureCode = FailureCode("CONFIGURATION_TIMEOUT")
        /** Android 전용. */
        public val DISCONNECT_START_FAILED: FailureCode = FailureCode("DISCONNECT_START_FAILED")
        public val DISCONNECT_CLEANUP_TIMEOUT: FailureCode = FailureCode("DISCONNECT_CLEANUP_TIMEOUT")
        public val DUPLICATE_CHARACTERISTIC_REFERENCE: FailureCode = FailureCode("DUPLICATE_CHARACTERISTIC_REFERENCE")
        public val NOT_READY: FailureCode = FailureCode("NOT_READY")
        public val NOT_CONNECTED: FailureCode = FailureCode("NOT_CONNECTED")
        public val CHARACTERISTIC_NOT_FOUND: FailureCode = FailureCode("CHARACTERISTIC_NOT_FOUND")
        /** Android 전용. */
        public val GATT_OPERATION_START_FAILED: FailureCode = FailureCode("GATT_OPERATION_START_FAILED")
        public val GATT_STATUS_FAILED: FailureCode = FailureCode("GATT_STATUS_FAILED")
        public val GATT_OPERATION_CALLBACK_MISMATCH: FailureCode = FailureCode("GATT_OPERATION_CALLBACK_MISMATCH")
        public val GATT_OPERATION_DUPLICATE_CALLBACK: FailureCode = FailureCode("GATT_OPERATION_DUPLICATE_CALLBACK")
        public val OPERATION_TIMEOUT: FailureCode = FailureCode("OPERATION_TIMEOUT")
        public val OPERATION_INVALIDATED: FailureCode = FailureCode("OPERATION_INVALIDATED")
        public val PAYLOAD_TOO_LARGE: FailureCode = FailureCode("PAYLOAD_TOO_LARGE")
        public val CLIENT_CLOSED: FailureCode = FailureCode("CLIENT_CLOSED")
        public val CLEANUP_FAILURE: FailureCode = FailureCode("CLEANUP_FAILURE")
        public val INTERNAL_TRANSPORT_ERROR: FailureCode = FailureCode("INTERNAL_TRANSPORT_ERROR")
        public val GATT_PAYLOAD_OBSERVED: FailureCode = FailureCode("GATT_PAYLOAD_OBSERVED")
    }
}

/** The transport boundary at which a failure became known. */
public enum class FailurePhase {
    Validation,
    Adapter,
    Scan,
    Connect,
    Link,
    DiscoverServices,
    Configure,
    Operation,
    Close,
}

/**
 * Recovery classification for the transport only. It never authorizes automatic replay of an
 * application command, especially a partially delivered write.
 */
public enum class Retryability {
    Never,
    AfterEnvironmentChange,
    NewConnectionMayHelp,
    TransientMayHelp,
    Unknown,
}

/** Public operation vocabulary shared with the future Swift Package. */
public enum class OperationKind {
    Read,
    WriteWithResponse,
    WriteWithoutResponse,
    Subscribe,
    Unsubscribe,
    RequestMtu,
    ReadDescriptor,
    WriteDescriptor,
}

/** Why pending work was invalidated with the active connection generation. */
public enum class ConnectionInvalidationReason {
    UserRequested,
    LinkLost,
    GenerationPoisoned,
    AdapterUnavailable,
    PermissionDenied,
}

/**
 * Opaque correlation metadata for a transport failure.
 *
 * Device identifiers and GATT attribute identifiers are deliberately not part of this public
 * value. They are useful to the platform implementation while it routes a callback, but putting
 * them in a failure or diagnostic leaks stable peripheral metadata through every public error
 * sink. Consumers can correlate an error with their own request through the opaque session,
 * generation, and operation tokens instead.
 */
public data class FailureContext(
    val sessionId: String? = null,
    val connectionGeneration: Long? = null,
    val operationId: String? = null,
    val operationKind: OperationKind? = null,
    val invalidationReason: ConnectionInvalidationReason? = null,
    /** The descriptor a `readDescriptor`/`writeDescriptor` failure or payload observation refers to. */
    val descriptor: DescriptorRef? = null,
) {
    init {
        require(connectionGeneration == null || connectionGeneration >= 0L) {
            "connectionGeneration must be non-negative"
        }
    }
}

/**
 * Allowlisted platform metadata. It deliberately has no throwable, platform framework object,
 * identifier, payload, exception message, or arbitrary field map.
 */
public sealed interface PlatformDetail {
    public data class Android(
        val domain: String,
        val statusCode: Int? = null,
        val reasonCode: Int? = null,
        val apiOperation: String? = null,
        val symbolicName: String? = null,
    ) : PlatformDetail

    public data class Ios(
        val domain: String,
        val code: Int,
        val apiOperation: String? = null,
        val symbolicName: String? = null,
    ) : PlatformDetail
}

/** How far a failed write reached in the transport, never in the product protocol. */
public data class WriteProgress(
    val totalBytes: Int,
    val totalChunks: Int,
    val bytesCompleted: Int,
    val chunksCompleted: Int,
    val failedChunkIndex: Int?,
    val failedChunkBytes: Int?,
    val failedChunkWasStarted: Boolean,
    val delivery: DeliverySemantics,
    val currentChunkDeliveryUnknown: Boolean,
) {
    init {
        require(totalBytes >= 0) { "totalBytes must be non-negative" }
        require(totalChunks >= 0) { "totalChunks must be non-negative" }
        require(bytesCompleted in 0..totalBytes) { "bytesCompleted must be within totalBytes" }
        require(chunksCompleted in 0..totalChunks) { "chunksCompleted must be within totalChunks" }
        require(failedChunkIndex == null || failedChunkIndex in 0 until totalChunks) {
            "failedChunkIndex must identify a write chunk"
        }
        require(failedChunkBytes == null || failedChunkBytes >= 0) {
            "failedChunkBytes must be non-negative"
        }
        require(!currentChunkDeliveryUnknown || failedChunkWasStarted) {
            "delivery is only unknown after the failed chunk was started"
        }
    }
}

/**
 * 실패 코드 → category 의 내부 단일 표. 코드 하나에 category 하나이며, 실패를 만드는 모든 곳이
 * 호출마다 category 를 적지 않고 여기서 얻는다. FailureCategoryContractTest 가 이 표를
 * contract/failure-codes.json 과 대조한다.
 */
internal object FailureCategories {
    val byCode: Map<String, String> = mapOf(
        "ADAPTER_UNAVAILABLE" to "environment",
        "BLE_UNSUPPORTED" to "environment",
        "PERMISSION_DENIED" to "environment",
        "SCAN_ALREADY_RUNNING" to "scan",
        "SCAN_STOPPED_BY_ADAPTER" to "environment",
        "SCAN_START_FAILED" to "scan",
        "SCAN_RUNTIME_FAILED" to "scan",
        "SCAN_START_THROTTLED" to "scan",
        "SCANNER_UNAVAILABLE" to "scan",
        "PERIPHERAL_NOT_FOUND" to "connection",
        "UNKNOWN_DEVICE" to "validation",
        "CONNECT_DURING_DISCONNECT" to "connection",
        "CONNECTION_OPTIONS_CONFLICT" to "connection",
        "LINK_START_FAILED" to "connection",
        "LINK_FAILED" to "connection",
        "LINK_TIMEOUT" to "connection",
        "SERVICE_DISCOVERY_START_FAILED" to "connection",
        "SERVICE_DISCOVERY_FAILED" to "connection",
        "SERVICE_DISCOVERY_TIMEOUT" to "connection",
        "CONFIGURATION_FAILED" to "connection",
        "CONFIGURATION_TIMEOUT" to "connection",
        "DISCONNECT_START_FAILED" to "connection",
        "DISCONNECT_CLEANUP_TIMEOUT" to "connection",
        "DUPLICATE_CHARACTERISTIC_REFERENCE" to "connection",
        "NOT_READY" to "operation",
        "NOT_CONNECTED" to "connection",
        "CHARACTERISTIC_NOT_FOUND" to "attribute",
        "GATT_OPERATION_START_FAILED" to "operation",
        "GATT_STATUS_FAILED" to "operation",
        "GATT_OPERATION_CALLBACK_MISMATCH" to "operation",
        "GATT_OPERATION_DUPLICATE_CALLBACK" to "operation",
        "OPERATION_TIMEOUT" to "operation",
        "OPERATION_INVALIDATED" to "operation",
        "PAYLOAD_TOO_LARGE" to "operation",
        "CLIENT_CLOSED" to "connection",
        "CLEANUP_FAILURE" to "connection",
        "INTERNAL_TRANSPORT_ERROR" to "connection",
        "GATT_PAYLOAD_OBSERVED" to "diagnostic",
    )

    fun of(code: String): FailureCategory =
        FailureCategory(byCode[code] ?: error("failure code $code has no category entry"))
}

/** Delivery evidence for a completed write chunk. */
public enum class DeliverySemantics {
    RemoteAcknowledged,
    SubmittedToPlatform,
}

/**
 * Serializable public failure value. [message] is optional developer-facing text and is never a
 * branching or wire-contract field.
 */
public data class BleFailure(
    val category: FailureCategory,
    val code: FailureCode,
    val phase: FailurePhase,
    val retryability: Retryability,
    val context: FailureContext = FailureContext(),
    val platformDetail: PlatformDetail? = null,
    val writeProgress: WriteProgress? = null,
    val message: String? = null,
)

/** Direct-command failure shell. Consumers branch on [failure], not on this exception class. */
public class BleException(
    public val failure: BleFailure,
) : Exception(failure.message ?: failure.code.value)

/** Stable reason that a session is disconnected. */
internal sealed interface DisconnectReason {
    public data object Initial : DisconnectReason
    public data object Requested : DisconnectReason
    public data object ClientClosed : DisconnectReason
    public data class Failed(val failure: BleFailure) : DisconnectReason
}

/**
 * The one-way, pure Android-to-shared conversion boundary. Android adapters pass facts here once;
 * callers publish the returned [BleFailure] unchanged through state, command, and event surfaces.
 */
internal object FailureMapper {
    private const val GATT_SUCCESS = 0
    private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    private const val GATT_INSUFFICIENT_AUTHORIZATION = 8
    private const val GATT_INSUFFICIENT_ENCRYPTION = 15
    private const val HCI_AUTHENTICATION_FAILURE = 5

    /** AOSP system/stack/include/gatt_api.h tGATT_DISCONN_REASON (onConnectionStateChange status). */
    private val LINK_DISCONNECT_REASONS: Map<Int, String> = mapOf(
        1 to "GATT_CONN_L2C_FAILURE",
        8 to "GATT_CONN_TIMEOUT",
        19 to "GATT_CONN_TERMINATE_PEER_USER",
        22 to "GATT_CONN_TERMINATE_LOCAL_HOST",
        34 to "GATT_CONN_LMP_TIMEOUT",
        62 to "GATT_CONN_FAILED_ESTABLISHMENT",
        133 to "GATT_ERROR",
    )

    fun fromAdapterState(state: AdapterState): BleFailure = when (state) {
        AdapterState.Unsupported -> failure(
            code = "BLE_UNSUPPORTED",
            phase = FailurePhase.Adapter,
            retryability = Retryability.Never,
        )
        is AdapterState.Unauthorized -> failure(
            code = "PERMISSION_DENIED",
            phase = FailurePhase.Adapter,
            retryability = if (state.canRequest) Retryability.AfterEnvironmentChange else Retryability.Never,
        )
        AdapterState.PoweredOff,
        AdapterState.Resetting,
        AdapterState.Unknown,
        -> failure(
            code = "ADAPTER_UNAVAILABLE",
            phase = FailurePhase.Adapter,
            retryability = Retryability.AfterEnvironmentChange,
        )
        AdapterState.Ready -> invalidContext("Ready adapter state is not a failure")
    }

    fun permissionDenied(
        phase: FailurePhase,
        operation: AndroidApiOperation,
        canRequest: Boolean = true,
        context: FailureContext = FailureContext(),
    ): BleFailure = failure(
        code = "PERMISSION_DENIED",
        phase = phase,
        retryability = if (canRequest) Retryability.AfterEnvironmentChange else Retryability.Never,
        context = context,
        platformDetail = androidDetail(domain = "android.security", operation = operation),
    )

    fun scanStartFailed(
        context: FailureContext = FailureContext(),
    ): BleFailure = failure(
        code = "SCAN_START_FAILED",
        phase = FailurePhase.Scan,
        retryability = Retryability.Unknown,
        context = context,
        platformDetail = androidDetail(
            domain = "android.bluetooth.le.scan",
            operation = AndroidApiOperation.StartScan,
        ),
    )

    fun scanRuntimeFailed(
        errorCode: Int,
        context: FailureContext = FailureContext(),
    ): BleFailure = failure(
        code = "SCAN_RUNTIME_FAILED",
        phase = FailurePhase.Scan,
        retryability = Retryability.Unknown,
        context = context,
        platformDetail = androidDetail(
            domain = "android.bluetooth.le.scan.error",
            statusCode = errorCode,
            operation = AndroidApiOperation.OnScanFailed,
        ),
    )

    /**
     * Maps a GATT callback status. For [FailurePhase.Link] the status comes from
     * onConnectionStateChange and is a disconnect-reason (HCI) space distinct from GATT status;
     * values overlap (0x08 = GATT_CONN_TIMEOUT vs GATT_INSUFFICIENT_AUTHORIZATION).
     */
    fun gattStatusFailed(
        statusCode: Int,
        phase: FailurePhase,
        operation: AndroidApiOperation,
        context: FailureContext = FailureContext(),
        reasonCode: Int? = null,
        symbolicName: String? = null,
    ): BleFailure {
        require(statusCode != GATT_SUCCESS) { "GATT success cannot be mapped to a failure" }
        require(statusCode >= 0) {
            "synthetic status must use gattStartRejected, not android platform detail"
        }
        require(phase in GATT_CALLBACK_PHASES) { "phase $phase cannot receive a GATT callback status" }
        return failure(
            code = when (phase) {
                FailurePhase.Link -> "LINK_FAILED"
                FailurePhase.DiscoverServices -> "SERVICE_DISCOVERY_FAILED"
                FailurePhase.Configure -> "CONFIGURATION_FAILED"
                FailurePhase.Operation -> "GATT_STATUS_FAILED"
                else -> error("validated above")
            },
            phase = phase,
            retryability = if (phase == FailurePhase.Link) {
                linkRetryability(statusCode)
            } else {
                retryabilityForGattStatus(statusCode)
            },
            context = context,
            platformDetail = androidDetail(
                domain = if (phase == FailurePhase.Link) {
                    "android.bluetooth.gatt.connection"
                } else {
                    "android.bluetooth.gatt.status"
                },
                statusCode = statusCode,
                reasonCode = reasonCode,
                operation = operation,
                symbolicName = symbolicName ?: if (phase == FailurePhase.Link) {
                    linkSymbolicName(statusCode)
                } else {
                    symbolicNameForGattStatus(statusCode)
                },
            ),
        )
    }

    /**
     * Maps a platform disconnect that arrived with no latched terminal cause (결정 1). Unlike
     * [gattStatusFailed], [statusCode] may be GATT_SUCCESS(0): the link ended without any
     * attributed failure. Retryability follows link status failures ([Retryability.Unknown]
     * for non-security statuses).
     */
    fun unattributedLinkDisconnect(
        statusCode: Int,
        context: FailureContext = FailureContext(),
    ): BleFailure {
        require(statusCode >= 0) {
            "synthetic status must not be exposed as Android platform detail"
        }
        return failure(
            code = "LINK_FAILED",
            phase = FailurePhase.Link,
            retryability = linkRetryability(statusCode),
            context = context,
            platformDetail = androidDetail(
                domain = "android.bluetooth.gatt.connection",
                statusCode = statusCode,
                operation = AndroidApiOperation.OnConnectionStateChange,
                symbolicName = linkSymbolicName(statusCode),
            ),
        )
    }

    /**
     * Maps a discovery that reported GATT_SUCCESS with zero services. An LE GATT server always
     * exposes GAP (0x1800), so this is not a valid result (observed after a missing platform cache
     * and L2CAP write failures). Unlike [gattStatusFailed], the success status 0 is carried as-is.
     */
    fun emptyServiceDiscovery(context: FailureContext = FailureContext()): BleFailure = failure(
        code = "SERVICE_DISCOVERY_FAILED",
        phase = FailurePhase.DiscoverServices,
        retryability = Retryability.Unknown,
        context = context,
        platformDetail = androidDetail(
            domain = "android.bluetooth.gatt.status",
            statusCode = GATT_SUCCESS,
            operation = AndroidApiOperation.OnServicesDiscovered,
        ),
    )

    /**
     * Maps a local start rejection. Synthetic legacy values such as -1 are intentionally absent:
     * they were SDK sentinels, not Android status codes.
     */
    fun gattStartRejected(
        phase: FailurePhase,
        operation: AndroidApiOperation,
        context: FailureContext = FailureContext(),
        statusCode: Int? = null,
    ): BleFailure {
        require(phase in GATT_START_PHASES) { "phase $phase cannot receive a GATT start rejection" }
        require(statusCode == null || statusCode >= 0) {
            "synthetic status must not be exposed as Android platform detail"
        }
        return failure(
            code = when (phase) {
                FailurePhase.Connect -> "LINK_START_FAILED"
                FailurePhase.DiscoverServices -> "SERVICE_DISCOVERY_START_FAILED"
                FailurePhase.Configure,
                FailurePhase.Operation,
                -> "GATT_OPERATION_START_FAILED"
                else -> error("validated above")
            },
            phase = phase,
            retryability = Retryability.Unknown,
            context = context,
            platformDetail = androidDetail(
                domain = "android.bluetooth.gatt.start",
                statusCode = statusCode,
                operation = operation,
            ),
        )
    }

    private fun failure(
        code: String,
        phase: FailurePhase,
        retryability: Retryability,
        context: FailureContext = FailureContext(),
        platformDetail: PlatformDetail? = null,
    ): BleFailure = BleFailure(
        category = FailureCategories.of(code),
        code = FailureCode(code),
        phase = phase,
        retryability = retryability,
        context = context,
        platformDetail = platformDetail,
    )

    private fun androidDetail(
        domain: String,
        statusCode: Int? = null,
        reasonCode: Int? = null,
        operation: AndroidApiOperation,
        symbolicName: String? = null,
    ): PlatformDetail.Android = PlatformDetail.Android(
        domain = domain,
        statusCode = statusCode,
        reasonCode = reasonCode,
        apiOperation = operation.wireName,
        symbolicName = symbolicName,
    )

    private fun retryabilityForGattStatus(statusCode: Int): Retryability = when (statusCode) {
        GATT_INSUFFICIENT_AUTHENTICATION,
        GATT_INSUFFICIENT_AUTHORIZATION,
        GATT_INSUFFICIENT_ENCRYPTION,
        -> Retryability.AfterEnvironmentChange
        else -> Retryability.Unknown
    }

    private fun symbolicNameForGattStatus(statusCode: Int): String? = when (statusCode) {
        GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
        GATT_INSUFFICIENT_AUTHORIZATION -> "GATT_INSUFFICIENT_AUTHORIZATION"
        GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
        else -> null
    }

    private fun linkRetryability(statusCode: Int): Retryability = when (statusCode) {
        HCI_AUTHENTICATION_FAILURE -> Retryability.AfterEnvironmentChange
        else -> Retryability.Unknown
    }

    private fun linkSymbolicName(statusCode: Int): String? = LINK_DISCONNECT_REASONS[statusCode]

    private fun invalidContext(message: String): Nothing = throw IllegalArgumentException(message)

    private val GATT_CALLBACK_PHASES: Set<FailurePhase> = setOf(
        FailurePhase.Link,
        FailurePhase.DiscoverServices,
        FailurePhase.Configure,
        FailurePhase.Operation,
    )

    private val GATT_START_PHASES: Set<FailurePhase> = setOf(
        FailurePhase.Connect,
        FailurePhase.DiscoverServices,
        FailurePhase.Configure,
        FailurePhase.Operation,
    )
}

package kr.co.nucode.ble

import java.util.UUID
import java.util.Collections

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

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else this.contentEquals(other)

private fun <K> Map<K, ByteArray>.copyByteMap(): Map<K, ByteArray> =
    entries.associate { (key, value) -> key to value.copyOf() }

private fun <K> Map<K, ByteArray>.byteMapEquals(other: Map<K, ByteArray>): Boolean =
    size == other.size && entries.all { (key, value) -> other[key]?.contentEquals(value) == true }

private fun <K> Map<K, ByteArray>.byteMapHashCode(): Int =
    entries.fold(0) { result, (key, value) -> result + (key.hashCode() xor value.contentHashCode()) }

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(toList())

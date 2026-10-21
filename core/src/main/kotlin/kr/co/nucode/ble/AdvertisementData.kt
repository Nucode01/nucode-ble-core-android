package kr.co.nucode.ble

/**
 * Structured advertisement fields shared by Android and iOS.
 *
 * Byte values cross the boundary by copy in both directions so callers cannot mutate a stored
 * advertisement after construction or through a property getter.
 *
 * The Android platform adapter also keeps the raw scan record bytes here. They are not part of the common
 * surface: Android apps read them through the Android-only extension `androidScanRecordBytes`, and iOS never
 * sets them. Equality includes them, so two results that differ only in raw bytes are different values.
 */
public class AdvertisementData private constructor(
    public val localName: String?,
    serviceUuids: Set<BleUuid>,
    manufacturerData: Map<Int, ByteArray>,
    serviceData: Map<BleUuid, ByteArray>,
    public val txPower: Int?,
    public val isConnectable: Boolean?,
    scanRecordBytes: ByteArray?,
) {
    public constructor(
        localName: String?,
        serviceUuids: Set<BleUuid>,
        manufacturerData: Map<Int, ByteArray>,
        serviceData: Map<BleUuid, ByteArray>,
        txPower: Int?,
        isConnectable: Boolean?,
    ) : this(localName, serviceUuids, manufacturerData, serviceData, txPower, isConnectable, null)

    public val serviceUuids: Set<BleUuid> = serviceUuids.toSet()
    private val manufacturerDataCopy: Map<Int, ByteArray> = manufacturerData.copyByteMap()
    private val serviceDataCopy: Map<BleUuid, ByteArray> = serviceData.copyByteMap()
    private val scanRecordBytesCopy: ByteArray? = scanRecordBytes?.copyOf()

    public val manufacturerData: Map<Int, ByteArray>
        get() = manufacturerDataCopy.copyByteMap()

    public val serviceData: Map<BleUuid, ByteArray>
        get() = serviceDataCopy.copyByteMap()

    /** Raw scan record bytes as the platform gave them (a copy), or null. Synthetic so the JVM API stays unchanged. */
    @get:JvmSynthetic
    internal val scanRecordBytes: ByteArray?
        get() = scanRecordBytesCopy?.copyOf()

    /** The same advertisement with [bytes] (copied) as its raw scan record. Only the platform adapter calls this. */
    @JvmSynthetic
    internal fun withScanRecordBytes(bytes: ByteArray?): AdvertisementData = AdvertisementData(
        localName, serviceUuids, manufacturerDataCopy, serviceDataCopy, txPower, isConnectable, bytes,
    )

    override fun equals(other: Any?): Boolean =
        other is AdvertisementData &&
            localName == other.localName &&
            serviceUuids == other.serviceUuids &&
            manufacturerDataCopy.byteMapEquals(other.manufacturerDataCopy) &&
            serviceDataCopy.byteMapEquals(other.serviceDataCopy) &&
            txPower == other.txPower &&
            isConnectable == other.isConnectable &&
            scanRecordBytesCopy.contentEquals(other.scanRecordBytesCopy)

    override fun hashCode(): Int {
        var result = localName?.hashCode() ?: 0
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + manufacturerDataCopy.byteMapHashCode()
        result = 31 * result + serviceDataCopy.byteMapHashCode()
        result = 31 * result + (txPower ?: 0)
        result = 31 * result + (isConnectable?.hashCode() ?: 0)
        result = 31 * result + scanRecordBytesCopy.contentHashCode()
        return result
    }
}

private fun <K> Map<K, ByteArray>.copyByteMap(): Map<K, ByteArray> =
    entries.associate { (key, value) -> key to value.copyOf() }

private fun <K> Map<K, ByteArray>.byteMapEquals(other: Map<K, ByteArray>): Boolean =
    size == other.size && entries.all { (key, value) -> other[key]?.contentEquals(value) == true }

private fun <K> Map<K, ByteArray>.byteMapHashCode(): Int =
    entries.fold(0) { result, (key, value) -> result + (key.hashCode() xor value.contentHashCode()) }

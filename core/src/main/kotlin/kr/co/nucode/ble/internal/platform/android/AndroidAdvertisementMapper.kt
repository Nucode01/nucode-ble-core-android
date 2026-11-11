package kr.co.nucode.ble.internal.platform.android

import android.annotation.SuppressLint
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.ParcelUuid
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.PlatformAdvertising

/** A platform callback is reduced to these facts once, before it crosses the Android boundary. */
internal data class AndroidAdvertisementFacts(
    val localName: String?,
    val serviceUuids: Set<BleUuid>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<BleUuid, ByteArray>,
    val txPower: Int?,
    val isConnectable: Boolean?,
    val scanRecordBytes: ByteArray? = null,
    /** `BluetoothDevice.getType()` 값. 읽지 못했으면(권한 없음) null. */
    val deviceType: Int? = null,
    /** 광고 정보(API 26+). 그 아래에서는 null. */
    val advertising: PlatformAdvertising? = null,
    /** `ScanResult.getTimestampNanos()`(elapsedRealtimeNanos 기준). */
    val timestampNanos: Long,
)

internal object AndroidAdvertisementMapper {
    fun map(facts: AndroidAdvertisementFacts): AdvertisementData = AdvertisementData(
        localName = facts.localName,
        serviceUuids = facts.serviceUuids,
        manufacturerData = facts.manufacturerData.mapValues { (_, bytes) -> bytes.copyOf() },
        serviceData = facts.serviceData.mapValues { (_, bytes) -> bytes.copyOf() },
        txPower = facts.txPower,
        isConnectable = facts.isConnectable,
    ).withScanRecordBytes(facts.scanRecordBytes).withPlatformDeviceType(facts.deviceType)
        .withPlatformAdvertising(facts.advertising)

    @SuppressLint("MissingPermission") // 권한 없음은 아래 오버로드가 SecurityException 으로 받는다.
    fun facts(result: ScanResult): AndroidAdvertisementFacts {
        val oreo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return facts(
            // API 21 이고 권한이 필요 없어 가드 없이 결과마다 읽는다(배치 결과 포함).
            timestampNanos = result.timestampNanos,
            record = result.scanRecord,
            isConnectable = if (oreo) result.isConnectable else null,
            readDeviceType = { result.device.type },
            advertising = if (oreo) {
                PlatformAdvertising(
                    isLegacy = result.isLegacy,
                    primaryPhy = result.primaryPhy,
                    secondaryPhy = result.secondaryPhy,
                    advertisingSid = result.advertisingSid,
                    dataStatus = result.dataStatus,
                )
            } else {
                null
            },
        )
    }

    /** [readDeviceType] 는 결과마다 한 번 부른다. API 31+ 에서 BLUETOOTH_CONNECT 가 없으면 SecurityException 이라 null 로 둔다. */
    fun facts(
        timestampNanos: Long,
        record: ScanRecord?,
        isConnectable: Boolean?,
        readDeviceType: () -> Int,
        advertising: PlatformAdvertising? = null,
    ): AndroidAdvertisementFacts {
        val manufacturer = buildMap {
            val data = record?.manufacturerSpecificData
            for (index in 0 until (data?.size() ?: 0)) {
                put(data!!.keyAt(index), data.valueAt(index).copyOf())
            }
        }
        val serviceUuids = record?.serviceUuids.orEmpty().mapTo(linkedSetOf()) { BleUuid(it.uuid) }
        // 광고의 service data 전부 — service UUID 목록에 없는 UUID(예: 0x16 AD 로만 싣는 비콘)도 포함한다.
        val serviceData = buildMap {
            record?.serviceData.orEmpty().forEach { (uuid: ParcelUuid, bytes: ByteArray?) ->
                bytes?.let { put(BleUuid(uuid.uuid), it.copyOf()) }
            }
        }
        val txPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }
        return AndroidAdvertisementFacts(
            // Do not fall back to BluetoothDevice.name: it is cached/remote metadata, not advertised local name.
            localName = record?.deviceName,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturer,
            serviceData = serviceData,
            txPower = txPower,
            isConnectable = isConnectable,
            // 받은 그대로 복사한다. 자르거나(0 채움 포함) 정규화하지 않는다.
            scanRecordBytes = record?.bytes?.copyOf(),
            deviceType = try {
                readDeviceType()
            } catch (_: SecurityException) {
                null
            },
            advertising = advertising,
            timestampNanos = timestampNanos,
        )
    }
}

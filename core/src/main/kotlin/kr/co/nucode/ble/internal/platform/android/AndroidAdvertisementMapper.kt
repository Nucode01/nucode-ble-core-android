package kr.co.nucode.ble.internal.platform.android

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.ParcelUuid
import kr.co.nucode.ble.AdvertisementData
import kr.co.nucode.ble.BleUuid

/** A platform callback is reduced to these facts once, before it crosses the Android boundary. */
internal data class AndroidAdvertisementFacts(
    val localName: String?,
    val serviceUuids: Set<BleUuid>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<BleUuid, ByteArray>,
    val txPower: Int?,
    val isConnectable: Boolean?,
)

internal object AndroidAdvertisementMapper {
    fun map(facts: AndroidAdvertisementFacts): AdvertisementData = AdvertisementData(
        localName = facts.localName,
        serviceUuids = facts.serviceUuids,
        manufacturerData = facts.manufacturerData.mapValues { (_, bytes) -> bytes.copyOf() },
        serviceData = facts.serviceData.mapValues { (_, bytes) -> bytes.copyOf() },
        txPower = facts.txPower,
        isConnectable = facts.isConnectable,
    )

    fun facts(result: ScanResult): AndroidAdvertisementFacts {
        val record = result.scanRecord
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
            isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null,
        )
    }
}

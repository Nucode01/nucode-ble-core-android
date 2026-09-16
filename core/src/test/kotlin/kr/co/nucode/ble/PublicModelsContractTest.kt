package kr.co.nucode.ble

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicModelsContractTest {
    @Test
    fun `references reject negative indices`() {
        val uuid = BleUuid(UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) { ServiceRef(uuid, -1) }
        val service = ServiceRef(uuid)
        assertThrows(IllegalArgumentException::class.java) { CharacteristicRef(service, uuid, -1) }
    }

    @Test
    fun `advertisement data copies mutable byte boundaries and preserves empty payload`() {
        val service = BleUuid(UUID.randomUUID())
        val manufacturer = byteArrayOf(1, 2)
        val serviceBytes = byteArrayOf()
        val data = AdvertisementData(
            localName = null,
            serviceUuids = setOf(service),
            manufacturerData = mapOf(7 to manufacturer),
            serviceData = mapOf(service to serviceBytes),
            txPower = null,
            isConnectable = null,
        )

        manufacturer[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), data.manufacturerData.getValue(7))
        data.manufacturerData.getValue(7)[1] = 8
        assertArrayEquals(byteArrayOf(1, 2), data.manufacturerData.getValue(7))
        assertEquals(0, data.serviceData.getValue(service).size)
        assertEquals(null, data.localName)
        assertEquals(null, data.txPower)
        assertEquals(null, data.isConnectable)
    }

    @Test
    fun `byte-bearing scan filter compares by value and copies inputs`() {
        val bytes = byteArrayOf(1)
        val first = BleManufacturerDataFilter(1, bytes)
        bytes[0] = 2
        assertEquals(BleManufacturerDataFilter(1, byteArrayOf(1)), first)
        assertArrayEquals(byteArrayOf(1), first.data)
    }

    @Test
    fun `scan filter snapshots caller lists and nested byte values`() {
        val service = BleUuid(UUID.randomUUID())
        val serviceUuids = mutableListOf(service)
        val bytes = byteArrayOf(1)
        val manufacturerFilters = mutableListOf(BleManufacturerDataFilter(1, bytes))

        val filter = BleScanFilter(serviceUuids = serviceUuids, manufacturerData = manufacturerFilters)
        serviceUuids.clear()
        manufacturerFilters.clear()
        bytes[0] = 2

        assertEquals(listOf(service), filter.serviceUuids)
        assertEquals(listOf(BleManufacturerDataFilter(1, byteArrayOf(1))), filter.manufacturerData)
        assertTrue(runCatching { (filter.serviceUuids as MutableList<BleUuid>).clear() }.isFailure)
    }

    @Test
    fun `neutral reconnect options validate their bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleReconnectPolicy.ExponentialBackoff(-1, 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleReconnectPolicy.ExponentialBackoff(1, 0, 1)
        }
        BleConnectOptions(BleReconnectPolicy.ExponentialBackoff(1, 1, 1))
    }

    @Test
    fun `session snapshot documents desired subscription intent`() {
        val property = SessionSnapshot::class.java.getDeclaredField("subscriptions")

        assertEquals(Set::class.java, property.type)
    }
}

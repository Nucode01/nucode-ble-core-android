package kr.co.nucode.ble.sample

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kr.co.nucode.ble.BleChunking
import kr.co.nucode.ble.BleClient
import kr.co.nucode.ble.BleConnectOptions
import kr.co.nucode.ble.BleReconnectPolicy
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.BleWriteOptions
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ServiceRef
import kr.co.nucode.ble.android.AndroidBleClientFactory

/** Separate-module compile contract for the complete official command path. */
internal object OfficialApiCompileContract {
    fun create(context: Context): BleClient = AndroidBleClientFactory.create(context)

    suspend fun exercise(
        client: BleClient,
        peripheralValue: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
    ): Flow<ByteArray> {
        client.startScan(ScanRequest())
        client.stopScan()
        val peripheralId = PeripheralId(peripheralValue)
        val characteristic = CharacteristicRef(
            service = ServiceRef(BleUuid(serviceUuid)),
            uuid = BleUuid(characteristicUuid),
        )
        val session = client.connect(
            peripheralId,
            BleConnectOptions(
                reconnectPolicy = BleReconnectPolicy.ExponentialBackoff(
                    maxAttempts = 2,
                    initialDelayMillis = 100,
                    maxDelayMillis = 1_000,
                ),
            ),
        )
        session.read(characteristic)
        session.write(
            characteristic,
            byteArrayOf(1),
            BleWriteOptions(chunking = BleChunking.Automatic),
        )
        session.subscribe(characteristic)
        session.unsubscribe(characteristic)
        session.disconnect()
        client.snapshot()
        return session.notifications(characteristic)
    }
}

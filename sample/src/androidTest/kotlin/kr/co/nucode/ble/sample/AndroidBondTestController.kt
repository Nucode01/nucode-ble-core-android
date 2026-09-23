package kr.co.nucode.ble.sample

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.PeripheralId

internal sealed interface BondRemovalResult {
    data object Removed : BondRemovalResult
    data object AlreadyAbsent : BondRemovalResult
    data object StartRejected : BondRemovalResult
    data object TimedOut : BondRemovalResult
    data class Unsupported(val reason: String) : BondRemovalResult
}

/** Test-only adapter for Android's hidden BluetoothDevice.removeBond API. */
internal class AndroidBondTestController(context: Context) {
    private val applicationContext = context.applicationContext
    private val adapter = applicationContext.getSystemService(BluetoothManager::class.java).adapter

    /** 비파괴 조회 — 폰이 이 peripheral 의 본딩 키를 갖고 있는지만 본다. */
    @Suppress("MissingPermission")
    fun hasBond(peripheralId: PeripheralId): Boolean =
        adapter.getRemoteDevice(peripheralId.value).bondState != BluetoothDevice.BOND_NONE

    /**
     * 이 peripheral 에 대해 폰이 `BOND_BONDING` 에 한 번이라도 진입했는지 기록한다.
     * 본딩 시도 전에 호출하고, 끝나면 반드시 [BondingObservation.close] 한다.
     */
    fun observeBonding(peripheralId: PeripheralId): BondingObservation {
        val observation = BondingObservation(applicationContext, peripheralId)
        register(observation.receiver)
        return observation
    }

    @Suppress("MissingPermission", "DiscouragedPrivateApi")
    suspend fun removeBond(
        peripheralId: PeripheralId,
        timeoutMillis: Long = 15_000L,
    ): BondRemovalResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val device = adapter.getRemoteDevice(peripheralId.value)
        if (device.bondState == BluetoothDevice.BOND_NONE) return BondRemovalResult.AlreadyAbsent

        val removed = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.bluetoothDevice() ?: return
                if (!changedDevice.address.equals(peripheralId.value, ignoreCase = true)) return
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_NONE) {
                    removed.complete(Unit)
                }
            }
        }
        register(receiver)
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) return BondRemovalResult.AlreadyAbsent
            val method = try {
                BluetoothDevice::class.java.getMethod("removeBond")
            } catch (failure: ReflectiveOperationException) {
                return BondRemovalResult.Unsupported(failure.javaClass.simpleName)
            }
            val accepted = try {
                method.invoke(device) as? Boolean
            } catch (failure: ReflectiveOperationException) {
                return BondRemovalResult.Unsupported(failure.javaClass.simpleName)
            } catch (failure: SecurityException) {
                return BondRemovalResult.Unsupported(failure.javaClass.simpleName)
            }
            if (accepted != true) return BondRemovalResult.StartRejected
            if (device.bondState == BluetoothDevice.BOND_NONE) return BondRemovalResult.Removed
            return if (withTimeoutOrNull(timeoutMillis) { removed.await() } != null) {
                BondRemovalResult.Removed
            } else {
                BondRemovalResult.TimedOut
            }
        } finally {
            applicationContext.unregisterReceiver(receiver)
        }
    }

    private fun register(receiver: BroadcastReceiver) {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
    }
}

internal class BondingObservation(
    private val context: Context,
    private val peripheralId: PeripheralId,
) {
    @Volatile
    var sawBonding: Boolean = false
        private set

    private var closed = false

    internal val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changedDevice = intent.bluetoothDevice() ?: return
            if (!changedDevice.address.equals(peripheralId.value, ignoreCase = true)) return
            if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_BONDING) {
                sawBonding = true
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        context.unregisterReceiver(receiver)
    }
}

private fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

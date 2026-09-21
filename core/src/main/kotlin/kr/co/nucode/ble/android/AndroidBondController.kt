package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.PeripheralId

/** Android bond state projected without exposing Android framework types. */
public enum class AndroidBondState {
    None,
    Bonding,
    Bonded,
}

/** Terminal result of an Android bond request. */
public enum class AndroidBondResult {
    Bonded,
    AlreadyBonded,
    StartRejected,
    Failed,
    TimedOut,
}

/** Android-only bond operations kept outside the platform-neutral BLE client contract. */
public interface AndroidBondController {
    /** Returns the device's current Android bond state. */
    public fun bondState(peripheralId: PeripheralId): AndroidBondState

    /** Starts bonding if needed and waits for a terminal Android bond state. */
    public suspend fun createBond(
        peripheralId: PeripheralId,
        timeoutMillis: Long = 30_000L,
    ): AndroidBondResult
}

internal fun interface BondObservation {
    fun close()
}

internal interface AndroidBondPlatform {
    fun bondState(peripheralId: PeripheralId): AndroidBondState

    fun beginBond(peripheralId: PeripheralId): Boolean

    fun observeBondState(
        peripheralId: PeripheralId,
        listener: (AndroidBondState) -> Unit,
    ): BondObservation
}

internal class DefaultAndroidBondController(
    private val platform: AndroidBondPlatform,
) : AndroidBondController {
    override fun bondState(peripheralId: PeripheralId): AndroidBondState =
        platform.bondState(peripheralId)

    override suspend fun createBond(
        peripheralId: PeripheralId,
        timeoutMillis: Long,
    ): AndroidBondResult {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        if (platform.bondState(peripheralId) == AndroidBondState.Bonded) {
            return AndroidBondResult.AlreadyBonded
        }

        val terminal = CompletableDeferred<AndroidBondResult>()
        val bondingStarted = AtomicBoolean(false)
        val observation = platform.observeBondState(peripheralId) { state ->
            when (state) {
                AndroidBondState.Bonded -> terminal.complete(AndroidBondResult.Bonded)
                AndroidBondState.Bonding -> bondingStarted.set(true)
                AndroidBondState.None -> if (bondingStarted.get()) {
                    terminal.complete(AndroidBondResult.Failed)
                }
            }
        }

        try {
            when (platform.bondState(peripheralId)) {
                AndroidBondState.Bonded -> return AndroidBondResult.AlreadyBonded
                AndroidBondState.Bonding -> bondingStarted.set(true)
                AndroidBondState.None -> {
                    bondingStarted.set(true)
                    if (!platform.beginBond(peripheralId)) {
                        return when (platform.bondState(peripheralId)) {
                            AndroidBondState.Bonded -> AndroidBondResult.Bonded
                            AndroidBondState.Bonding -> awaitTerminal(terminal, timeoutMillis)
                            AndroidBondState.None -> AndroidBondResult.StartRejected
                        }
                    }
                }
            }
            return awaitTerminal(terminal, timeoutMillis)
        } finally {
            observation.close()
        }
    }

    private suspend fun awaitTerminal(
        terminal: CompletableDeferred<AndroidBondResult>,
        timeoutMillis: Long,
    ): AndroidBondResult = withTimeoutOrNull(timeoutMillis) { terminal.await() }
        ?: AndroidBondResult.TimedOut
}

internal class ContextAndroidBondPlatform(
    private val context: Context,
) : AndroidBondPlatform {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java).adapter

    override fun bondState(peripheralId: PeripheralId): AndroidBondState =
        device(peripheralId).bondState.toAndroidBondState()

    override fun beginBond(peripheralId: PeripheralId): Boolean =
        device(peripheralId).createBond()

    override fun observeBondState(
        peripheralId: PeripheralId,
        listener: (AndroidBondState) -> Unit,
    ): BondObservation {
        val closed = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.bluetoothDevice() ?: return
                if (!changedDevice.address.equals(peripheralId.value, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                listener(state.toAndroidBondState())
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        return BondObservation {
            if (closed.compareAndSet(false, true)) context.unregisterReceiver(receiver)
        }
    }

    @Suppress("MissingPermission")
    private fun device(peripheralId: PeripheralId): BluetoothDevice =
        adapter.getRemoteDevice(peripheralId.value)
}

private fun Int.toAndroidBondState(): AndroidBondState = when (this) {
    BluetoothDevice.BOND_BONDED -> AndroidBondState.Bonded
    BluetoothDevice.BOND_BONDING -> AndroidBondState.Bonding
    else -> AndroidBondState.None
}

private fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

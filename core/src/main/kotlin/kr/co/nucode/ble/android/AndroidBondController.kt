package kr.co.nucode.ble.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.normalized

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
    /**
     * Returns the device's current Android bond state.
     *
     * @throws SecurityException if the Bluetooth permission is missing.
     * @throws IllegalArgumentException if [peripheralId] is not a valid Bluetooth address.
     * @throws IllegalStateException if the device has no Bluetooth adapter.
     */
    public fun bondState(peripheralId: PeripheralId): AndroidBondState

    /**
     * Starts bonding if needed and waits for a terminal Android bond state.
     *
     * Platform failures (missing permission, malformed id, no adapter) are returned as
     * [AndroidBondResult.StartRejected] before bonding starts and [AndroidBondResult.Failed]
     * once it is being observed. Only [kotlinx.coroutines.CancellationException] and an invalid
     * [timeoutMillis] are thrown.
     */
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
        platform.bondState(peripheralId.normalized())

    override suspend fun createBond(
        peripheralId: PeripheralId,
        timeoutMillis: Long,
    ): AndroidBondResult {
        val id = peripheralId.normalized()
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val initial = try {
            platform.bondState(id)
        } catch (e: RuntimeException) {
            requirePlatformFailure(e)
            return AndroidBondResult.StartRejected
        }
        if (initial == AndroidBondState.Bonded) {
            return AndroidBondResult.AlreadyBonded
        }

        val terminal = CompletableDeferred<AndroidBondResult>()
        val bondingStarted = AtomicBoolean(false)
        val observation = try {
            platform.observeBondState(id) { state ->
                when (state) {
                    AndroidBondState.Bonded -> terminal.complete(AndroidBondResult.Bonded)
                    AndroidBondState.Bonding -> bondingStarted.set(true)
                    AndroidBondState.None -> if (bondingStarted.get()) {
                        terminal.complete(AndroidBondResult.Failed)
                    }
                }
            }
        } catch (e: RuntimeException) {
            requirePlatformFailure(e)
            return AndroidBondResult.StartRejected
        }

        var started = false
        try {
            when (platform.bondState(id)) {
                AndroidBondState.Bonded -> return AndroidBondResult.AlreadyBonded
                AndroidBondState.Bonding -> {
                    bondingStarted.set(true)
                    started = true
                }
                AndroidBondState.None -> {
                    bondingStarted.set(true)
                    val accepted = platform.beginBond(id)
                    started = true
                    if (!accepted) {
                        return when (platform.bondState(id)) {
                            AndroidBondState.Bonded -> AndroidBondResult.Bonded
                            AndroidBondState.Bonding -> awaitTerminal(terminal, timeoutMillis)
                            AndroidBondState.None -> AndroidBondResult.StartRejected
                        }
                    }
                }
            }
            return awaitTerminal(terminal, timeoutMillis)
        } catch (e: RuntimeException) {
            requirePlatformFailure(e)
            return if (started) AndroidBondResult.Failed else AndroidBondResult.StartRejected
        } finally {
            try {
                observation.close()
            } catch (e: RuntimeException) {
                requirePlatformFailure(e)
                // Closing is best effort; the result is already decided.
            }
        }
    }

    private suspend fun awaitTerminal(
        terminal: CompletableDeferred<AndroidBondResult>,
        timeoutMillis: Long,
    ): AndroidBondResult = withTimeoutOrNull(timeoutMillis) { terminal.await() }
        ?: AndroidBondResult.TimedOut
}

/** Rethrows anything that is not a platform failure (notably CancellationException). */
private fun requirePlatformFailure(e: RuntimeException) {
    if (e is CancellationException) throw e
    if (e !is SecurityException && e !is IllegalArgumentException && e !is IllegalStateException) throw e
}

internal class ContextAndroidBondPlatform(
    private val context: Context,
) : AndroidBondPlatform {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("Bluetooth adapter is not available")

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

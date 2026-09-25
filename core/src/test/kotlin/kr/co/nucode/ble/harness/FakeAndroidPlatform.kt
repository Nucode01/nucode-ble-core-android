package kr.co.nucode.ble.harness

import kr.co.nucode.ble.internal.scan.PlatformScanPort
import kr.co.nucode.ble.internal.scan.PlatformScanSpec
import kr.co.nucode.ble.internal.scan.ScanObservation
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kr.co.nucode.ble.AdapterState
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureMapper
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.internal.BleRuntime
import kr.co.nucode.ble.internal.gatt.GattCallbackSource
import kr.co.nucode.ble.internal.gatt.GattCharacteristicIdentity
import kr.co.nucode.ble.internal.gatt.GattCharacteristicSnapshot
import kr.co.nucode.ble.internal.gatt.GattDescriptorIdentity
import kr.co.nucode.ble.internal.gatt.GattDriver
import kr.co.nucode.ble.internal.gatt.GattDriverEvent
import kr.co.nucode.ble.internal.gatt.GattOperationTarget
import kr.co.nucode.ble.internal.gatt.GattOperationTargetResolver
import kr.co.nucode.ble.internal.gatt.GattServiceSnapshot

/**
 * Android 처럼 행동하는 단일 fake 플랫폼(plan platform-harness v1 목표 1).
 *
 * 이 하네스가 존재하는 핵심 이유: H1 은 기존 fake 가 콜백에 `connectionGeneration` 을 직접
 * 넣어 production 모양(Android 는 generation 을 주지 않으므로 null)과 달라서 놓쳤다. 그래서
 * 여기서 내는 ConnectionStateChanged/ServicesDiscovered/MtuChanged 는 **항상**
 * `connectionGeneration = null` 이다. 캡처한 generation 을 싣는 경로는 두지 않는다.
 *
 * 콜백 출처(source)는 handle epoch 로 흉내 낸다 — production `AndroidGattCallbackRouter.sourceOf`
 * 의 `currentGatt === gatt` 자리. 명령(connect·retained connect·discover 등)이 나갈 때 [epoch] 를
 * 캡처하고, driver [close] 와 새 [connect](새 BluetoothGatt) 가 epoch 를 올린다(OS 재연결은 같은 handle). 콜백 source 는 `fake.epoch == 캡처값` 이라 닫힌
 * handle 에서 온 콜백은 `CallbackAttribution` 이 production 처럼 버린다.
 *
 * 콜백은 driver 호출 안에서 동기로 sink 에 넣지 않고 [scope](코어와 같은 디스패처)에 예약한다 —
 * production `AndroidGattCallbackRouter.forward` 의 `scope.launch { sink(event) }` 홉과 같은 순서.
 */
internal class FakeAndroidPlatform(private val scope: CoroutineScope) : GattDriver {

    // ── 스크립트 ────────────────────────────────────────────────────────────

    // 근거(관측): 연결 성공은 status 0 STATE_CONNECTED 한 번.
    var onConnect: CallbackVariant = CallbackVariant.Arrive()

    /** null 이 아니면 연결 콜백을 STATE_DISCONNECTED + 이 status 로 낸다(예: 133). */
    var connectFailStatus: Int? = null

    /** null 이 아니면 connect 시작이 이 예외를 던진다(connectGatt 시작 거절 자리). */
    var connectThrows: Exception? = null

    // 근거(가정, 검증 필요): 사용자 disconnect 뒤 Android 는 status 0 STATE_DISCONNECTED 를 한 번 낸다.
    var onDisconnect: CallbackVariant = CallbackVariant.Arrive()

    // 근거(가정): 서비스 탐색 성공은 status 0 한 번, 서비스 목록은 스크립트 값.
    var onDiscover: CallbackVariant = CallbackVariant.Arrive()
    var services: List<GattServiceSnapshot> = emptyList()

    // 근거(가정): 요청한 MTU 를 그대로 허용해 status 0 으로 한 번 응답(mtuResult 로 바꿀 수 있음).
    var onMtu: CallbackVariant = CallbackVariant.Arrive()
    var mtuResult: Int? = null

    // 근거(가정): CCCD write 는 status 0 ack 한 번.
    var onDescriptorWrite: CallbackVariant = CallbackVariant.Arrive()

    // 근거(가정): read/write 는 status 0 ack 한 번. read 값은 [readValue].
    var onRead: CallbackVariant = CallbackVariant.Arrive()
    var readValue: ByteArray = byteArrayOf(1)
    var onWrite: CallbackVariant = CallbackVariant.Arrive()

    /** 명령 시작 반환값(Android 의 boolean 반환). false 면 콜백도 내지 않는다. */
    var discoverStarts: Boolean = true
    var mtuStarts: Boolean = true
    var descriptorStarts: Boolean = true
    var readStarts: Boolean = true
    var writeStarts: Boolean = true

    // ── 기록 ────────────────────────────────────────────────────────────────

    /** 현재 platform handle 의 세대. driver [close] 가 올린다(위 클래스 KDoc 의 출처 식별). */
    var epoch: Long = 0

    /** 모든 driver 호출 기록 — "connect", "disconnect", "close", "discover", "mtu", "descriptor", "read", "write". */
    val calls: MutableList<String> = mutableListOf()

    /** CCCD write 기록 — (ref, 켜기 여부). 값이 전부 0 이면 끄기다. */
    val cccdWrites: MutableList<Pair<CharacteristicRef?, Boolean>> = mutableListOf()

    // ── 어댑터 ──────────────────────────────────────────────────────────────

    // 근거(가정): 테스트 시작 시 어댑터는 켜져 있고 권한도 있다.
    val adapter = MutableStateFlow<AdapterState>(AdapterState.Ready)
    val adapterProbe: () -> AdapterState = { adapter.value }
    lateinit var runtime: BleRuntime

    /**
     * 어댑터 상태를 바꾸고 런타임이 관찰하게 한다(리시버 대신 `refreshAdapterState` seam).
     * production 리시버처럼 `refreshAdapterState()` 를 코어 디스패처에 예약한다(BleRuntime 의
     * `coreScope.launch { refreshAdapterState() }`). [scanFailureBeforeRefresh] 면 스캔 실패 콜백이
     * 그 홉보다 먼저 큐에 들어간다.
     */
    fun setAdapter(state: AdapterState, scanFailureBeforeRefresh: Boolean = false) {
        val scanBefore = scanStarts.lastOrNull()?.takeIf { runtime.scanner.isScanning }
        val failScan = scanBefore != null && state != AdapterState.Ready
        adapter.value = state
        if (failScan && scanFailureBeforeRefresh) scanBefore!!.fail(SCAN_FAILED_INTERNAL_ERROR, onScanFailedAfterAdapterOff)
        scope.launch { runtime.refreshAdapterState() }
        if (failScan && !scanFailureBeforeRefresh) scanBefore!!.fail(SCAN_FAILED_INTERNAL_ERROR, onScanFailedAfterAdapterOff)
    }

    // ── 스캔 ────────────────────────────────────────────────────────────────

    /** null 이면 시작 성공, 아니면 이 실패로 동기 거절(AndroidScanner.start 의 반환값 자리). */
    var scanStartFailure: BleFailure? = null

    // 근거(코드 주석 Scanner.stopForAdapter): "onScanFailed 는 이 경로에서 보장되지 않는다" — 기본은 오지 않음.
    var onScanFailedAfterAdapterOff: CallbackVariant = CallbackVariant.Missing

    /** 성공한 플랫폼 스캔 시작 기록. 각 항목으로 그 시작 시점의 콜백에서 실패를 낼 수 있다(M1 의 늦은 실패). */
    val scanStarts: MutableList<ScanStart> = mutableListOf()

    /** 성공한 시작 하나 — production 콜백 객체처럼 자기 요청을 알지만, 코어로 가는 홉은 요청을 싣지 않는다. */
    inner class ScanStart(
        val spec: PlatformScanSpec,
        private val onFailure: (BleFailure) -> Unit,
        private val onResult: (ScanObservation) -> Unit,
    ) {
        /**
         * production `onScanFailed(errorCode)` → `FailureMapper.scanRuntimeFailed` → Scanner 가 만든 onFailure
         * (자기 generation 캡처, 코어 홉 포함) 와 같은 경로 — fake 는 generation 을 계산하지 않는다.
         */
        fun fail(errorCode: Int, variant: CallbackVariant = CallbackVariant.Arrive()) {
            val deliver = { onFailure(FailureMapper.scanRuntimeFailed(errorCode)) }
            when (variant) {
                is CallbackVariant.Arrive, is CallbackVariant.ArriveBeforeStartReturns -> deliver()
                CallbackVariant.Missing -> Unit
                is CallbackVariant.Late -> scope.launch { delay(variant.delayMillis); deliver() }
                is CallbackVariant.Duplicate -> {
                    deliver()
                    deliver()
                }
            }
        }

        /** production `onScanResult` → Scanner 가 만든 onResult(코어 홉 포함) 와 같은 경로. */
        fun result(observation: ScanObservation) = onResult(observation)
    }

    /** 가장 최근 시작한 스캔의 런타임 실패. variant 의 status 는 쓰지 않는다(errorCode 가 대신한다). */
    fun scanFailed(errorCode: Int, variant: CallbackVariant = CallbackVariant.Arrive()) {
        scanStarts.last().fail(errorCode, variant)
    }

    /** 플랫폼 정지 횟수(AndroidScanner.stop 자리). */
    var scanStops: Int = 0

    /**
     * 플랫폼 스캔 문(AndroidScanner 자리) — [harness] 가 BleRuntime 에 주입한다. 시작마다 "scanStart" 를
     * [calls] 에 남기고, 성공한 시작은 Scanner 가 넘긴 같은 콜백 람다와 spec 을 [scanStarts] 에 보관한다.
     */
    val scanPort: PlatformScanPort = object : PlatformScanPort {
        override fun start(
            spec: PlatformScanSpec,
            onResult: (ScanObservation) -> Unit,
            onFailure: (BleFailure) -> Unit,
        ): BleFailure? {
            calls += "scanStart"
            return scanStartFailure ?: run {
                scanStarts += ScanStart(spec, onFailure, onResult)
                null
            }
        }

        override fun stop() {
            scanStops++
        }
    }

    // ── resolver ────────────────────────────────────────────────────────────

    private val characteristicRefs = mutableMapOf<BluetoothGattCharacteristic, CharacteristicRef>()
    private val descriptorRefs = mutableMapOf<BluetoothGattDescriptor, CharacteristicRef>()

    /** JVM 스텁 객체는 uuid 를 못 돌려주므로 만든 객체 → ref 를 기억해 콜백 identity 를 채운다. */
    val resolver = GattOperationTargetResolver { _, ref ->
        val characteristic = BluetoothGattCharacteristic(ref.uuid.value, 0, 0)
        val descriptor = BluetoothGattDescriptor(CCCD_UUID, 0)
        characteristicRefs[characteristic] = ref
        descriptorRefs[descriptor] = ref
        GattOperationTarget(
            characteristic = characteristic,
            identity = GattCharacteristicIdentity.from(ref),
            propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            cccdDescriptor = descriptor,
        )
    }

    // ── GattDriver ─────────────────────────────────────────────────────────

    private var sink: (GattDriverEvent) -> Unit = {}

    override fun setEventSink(sink: (GattDriverEvent) -> Unit) {
        this.sink = sink
    }

    // 근거(코드 주석 GattDriver): 테스트 driver 는 시작 성공 후 null 핸들을 돌려줄 수 있다.
    override fun connect(deviceId: PeripheralId, autoConnect: Boolean): BluetoothGatt? {
        calls += "connect"
        connectThrows?.let { throw it }
        // 근거(production): connectGatt 는 호출마다 새 BluetoothGatt 를 만든다 — 이전(닫히지 않은) handle 의 콜백은 출처가 달라진다.
        epoch++
        scheduleConnection(deviceId, onConnect, connectFailStatus, epoch, afterStartReturns = false)
        return null
    }

    /** generation 은 받아도 버린다 — Android 는 콜백에 generation 을 싣지 않는다. */
    override fun connect(deviceId: PeripheralId, autoConnect: Boolean, connectionGeneration: Long): BluetoothGatt? =
        connect(deviceId, autoConnect)

    override fun disconnect(deviceId: PeripheralId, gatt: BluetoothGatt?) {
        calls += "disconnect"
        val source = sourceAt(epoch)
        schedule(onDisconnect) { status -> connectionEvent(deviceId, source, status, BluetoothProfile.STATE_DISCONNECTED) }
    }

    // 근거(관측): close() 뒤에는 콜백이 오지 않는다 — epoch 를 올려 이미 예약된 콜백의 source 가 맞지 않게 한다.
    override fun close(deviceId: PeripheralId, gatt: BluetoothGatt?) {
        calls += "close"
        epoch++
    }

    override fun discoverServices(deviceId: PeripheralId, gatt: BluetoothGatt?): Boolean {
        calls += "discover"
        if (!discoverStarts) return false
        val source = sourceAt(epoch)
        schedule(onDiscover) { status ->
            GattDriverEvent.ServicesDiscovered(deviceId, source, status, services, connectionGeneration = null)
        }
        return true
    }

    override fun requestMtu(deviceId: PeripheralId, gatt: BluetoothGatt?, mtu: Int): Boolean {
        calls += "mtu"
        if (!mtuStarts) return false
        val source = sourceAt(epoch)
        schedule(onMtu) { status ->
            GattDriverEvent.MtuChanged(deviceId, source, mtuResult ?: mtu, status, connectionGeneration = null)
        }
        return true
    }

    // 근거(가정): setCharacteristicNotification 은 로컬 호출이라 콜백 없이 성공.
    override fun setCharacteristicNotification(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean = true

    override fun writeDescriptor(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        calls += "descriptor"
        if (!descriptorStarts) return false
        val ref = descriptorRefs[descriptor]
        cccdWrites += ref to value.any { it != 0.toByte() }
        val source = sourceAt(epoch)
        schedule(onDescriptorWrite) { status ->
            GattDriverEvent.DescriptorWrite(
                deviceId,
                source,
                status,
                ref?.let { GattDescriptorIdentity(GattCharacteristicIdentity.from(it), CCCD_UUID) },
            )
        }
        return true
    }

    override fun readCharacteristic(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        calls += "read"
        if (!readStarts) return false
        val snapshot = characteristicRefs[characteristic]?.let(::snapshotOf)
        val value = readValue
        val source = sourceAt(epoch)
        schedule(onRead) { status -> GattDriverEvent.CharacteristicRead(deviceId, source, snapshot, value, status) }
        return true
    }

    override fun writeCharacteristic(
        deviceId: PeripheralId,
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        calls += "write"
        if (!writeStarts) return false
        val identity = characteristicRefs[characteristic]?.let(GattCharacteristicIdentity::from)
        val source = sourceAt(epoch)
        schedule(onWrite) { status -> GattDriverEvent.CharacteristicWrite(deviceId, source, status, identity) }
        return true
    }

    // ── 재연결 시작 ─────────────────────────────────────────────────────────

    // ── 자발적 플랫폼 이벤트 ────────────────────────────────────────────────

    // 근거(관측): 링크 끊김은 status 8(GATT_CONN_TIMEOUT) STATE_DISCONNECTED.
    fun linkLoss(deviceId: PeripheralId, status: Int = 8) =
        emitConnectionState(deviceId, BluetoothProfile.STATE_DISCONNECTED, status)

    /** [fromEpoch] 는 콜백을 낸 handle 의 세대 — 기본은 현재 handle, 이전 값이면 닫힌 handle 의 늦은 콜백이다. */
    fun emitConnectionState(deviceId: PeripheralId, newState: Int, status: Int, fromEpoch: Long = epoch) {
        val source = sourceAt(fromEpoch)
        post(0) { connectionEvent(deviceId, source, status, newState) }
    }

    fun emitNotification(deviceId: PeripheralId, ref: CharacteristicRef, bytes: ByteArray) {
        val source = sourceAt(epoch)
        post(0) { GattDriverEvent.CharacteristicChanged(deviceId, source, snapshotOf(ref), bytes) }
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private fun scheduleConnection(
        deviceId: PeripheralId,
        variant: CallbackVariant,
        failStatus: Int?,
        handleEpoch: Long,
        afterStartReturns: Boolean,
    ) {
        val source = sourceAt(handleEpoch)
        schedule(variant, afterStartReturns) { status ->
            if (failStatus != null) {
                connectionEvent(deviceId, source, failStatus, BluetoothProfile.STATE_DISCONNECTED)
            } else {
                connectionEvent(deviceId, source, status, BluetoothProfile.STATE_CONNECTED)
            }
        }
    }

    private fun connectionEvent(deviceId: PeripheralId, source: GattCallbackSource, status: Int, newState: Int) =
        GattDriverEvent.ConnectionStateChanged(deviceId, source, status, newState, connectionGeneration = null)

    /** 캡처한 handle 세대가 아직 현재일 때만 맞는 source(production `currentGatt === gatt`). */
    private fun sourceAt(handleEpoch: Long) = GattCallbackSource { epoch == handleEpoch }

    /** [afterStartReturns] 면 Arrive/Duplicate 콜백이 홉을 한 번 더 거쳐, 시작 호출 뒤 큐에 쌓인 작업 다음에 온다. */
    private fun schedule(variant: CallbackVariant, afterStartReturns: Boolean = false, event: (Int) -> GattDriverEvent) {
        when (variant) {
            is CallbackVariant.Arrive -> post(0, afterStartReturns) { event(variant.status) }
            is CallbackVariant.ArriveBeforeStartReturns -> post(0, extraHop = false) { event(variant.status) }
            CallbackVariant.Missing -> Unit
            is CallbackVariant.Late -> post(variant.delayMillis) { event(variant.status) }
            is CallbackVariant.Duplicate -> {
                post(0, afterStartReturns) { event(variant.status) }
                post(0, afterStartReturns) { event(variant.status) }
            }
        }
    }

    private fun post(delayMillis: Long, extraHop: Boolean = false, event: () -> GattDriverEvent) {
        scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (extraHop) yield()
            sink(event())
        }
    }

    private fun snapshotOf(ref: CharacteristicRef) = GattCharacteristicSnapshot(
        serviceUuid = ref.service.uuid.value,
        charUuid = ref.uuid.value,
        instanceIndex = ref.instanceIndex,
        propertyMask = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        serviceInstanceIndex = ref.service.instanceIndex,
    )

    companion object {
        // 근거(Android ScanCallback 상수): SCAN_FAILED_INTERNAL_ERROR = 3.
        const val SCAN_FAILED_INTERNAL_ERROR = 3
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

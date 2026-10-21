package kr.co.nucode.ble.internal.gatt

import kr.co.nucode.ble.AndroidApiOperation
import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.OperationKind
import kr.co.nucode.ble.internal.model.GattHandle
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor
import kr.co.nucode.ble.internal.port.GattDriver
import kr.co.nucode.ble.internal.session.Session

// [OperationExecutor] 의 descriptor 시작 칸 — CCCD 쓰기(구독·해제·복원·앱 CCCD write)와 descriptor read·write 의 플랫폼 호출.
// 상태는 갖지 않는다. 대상 characteristic 해석과 시작 결과의 큐 사건 변환은 실행기가 한다.

/** subscribe·unsubscribe·복원이 쓸 CCCD 값. 꺼짐은 0000, 복원할 앱 값([stored])이 있으면 그 값, 없으면 notify 우선. */
internal fun subscriptionCccdValue(enabled: Boolean, propertyMask: Int, stored: ByteArray?): ByteArray = when {
    !enabled -> byteArrayOf(0x00, 0x00)
    stored != null -> stored
    propertyMask and GattProperties.NOTIFY != 0 -> byteArrayOf(0x01, 0x00)
    else -> byteArrayOf(0x02, 0x00)
}

/**
 * 로컬 알림 수신을 [value] 에 맞춘 뒤(0 이 아니면 켜고 0 이면 끈다) CCCD 에 [value] 를 그대로 쓴다. 켜는 쪽에서 로컬 수신이
 * 거절되면 쓰지 않고 시작 거절이다. 끄는 쪽은 로컬 결과와 무관하게 쓴다. [kind] 는 실패 문맥의 작업 종류다.
 */
internal fun startCccdWrite(
    driver: GattDriver,
    session: Session,
    gatt: GattHandle?,
    characteristic: PlatformCharacteristic,
    descriptor: PlatformDescriptor?,
    value: ByteArray,
    kind: OperationKind,
): BleFailure? {
    val enabled = value.enablesNotifications()
    val routingStarted = driver.setCharacteristicNotification(session.deviceId, gatt, characteristic, enabled)
    if (enabled && !routingStarted) {
        return GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = kind)
    }
    val resolvedDescriptor = descriptor ?: return GattFailureFactory.characteristicNotFound(session, kind)
    return if (driver.writeDescriptor(session.deviceId, gatt, resolvedDescriptor, value)) {
        null
    } else {
        GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = kind)
    }
}

/**
 * 앱 CCCD write(B.4 규칙 2·3). 0 이 아니면 쓰기 전에 로컬 알림 수신을 켜고(거절이면 쓰지 않고 시작 거절), 0 이면 건드리지 않는다.
 * 쓰기 전에 [QueuedDescriptorWrite.routingSettle] 을 남긴다 — 끄기·되돌리기는 기기 응답(또는 동기 거절의 실패 완료) 뒤에 한다.
 */
private fun startAppCccdWrite(
    driver: GattDriver,
    session: Session,
    gatt: GattHandle?,
    characteristic: PlatformCharacteristic,
    descriptor: PlatformDescriptor,
    operation: QueuedDescriptorWrite,
    value: ByteArray,
): BleFailure? {
    val enables = value.enablesNotifications()
    if (enables && !driver.setCharacteristicNotification(session.deviceId, gatt, characteristic, true)) {
        return GattFailureFactory.startRejected(
            session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = OperationKind.WriteDescriptor,
        )
    }
    operation.routingSettle = CccdRoutingSettle(
        driver, session, gatt, characteristic,
        enables = enables,
        wasActive = operation.descriptor.characteristic in session.subscriptionState.activeSnapshot,
        startGeneration = session.connectionCoordinator.generation,
    )
    return if (driver.writeDescriptor(session.deviceId, gatt, descriptor, value)) {
        null
    } else {
        GattFailureFactory.startRejected(
            session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = OperationKind.WriteDescriptor,
        )
    }
}

/**
 * 앱 CCCD write 가 끝난 뒤의 로컬 수신 정산. 성공한 0 이면 끈다. 실패한 0 이 아닌 값이면 쓰기 전에 활성이 아니었을 때만 켠 수신을 끈다.
 * 실패한 0·성공한 0 이 아닌 값은 할 일이 없다. 시작 generation 이 더 이상 Ready 가 아니면 아무것도 하지 않는다(링크가 떠나는 중이다).
 */
internal class CccdRoutingSettle(
    private val driver: GattDriver,
    private val session: Session,
    private val gatt: GattHandle?,
    private val characteristic: PlatformCharacteristic,
    private val enables: Boolean,
    private val wasActive: Boolean,
    private val startGeneration: Long,
) {
    /**
     * 끄기가 거절되거나 예외를 던지면 false — 호출자가 진단만 남긴다. completion 처리기에서 불리므로 예외를 밖으로 내지 않는다
     * (내면 큐 셸이 남은 할 일을 버린다).
     */
    fun settle(succeeded: Boolean): Boolean {
        val disable = if (succeeded) !enables else enables && !wasActive
        if (!disable || !session.connectionCoordinator.readyFor(startGeneration)) return true
        return try {
            driver.setCharacteristicNotification(session.deviceId, gatt, characteristic, false)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * descriptor read·write 를 플랫폼에 시작한다. descriptor 는 [target] 의 탐색 순서 목록에서 (uuid, 순번)으로 고르고, 없으면
 * `CHARACTERISTIC_NOT_FOUND` 다. 플랫폼이 동기로 거절하면 `GATT_OPERATION_START_FAILED` 다. write 는 시작이 받아들여지면
 * [onWriteStarted] 로 보낸 값을 알린다(payload 관측).
 */
internal fun startDescriptorOperation(
    driver: GattDriver,
    session: Session,
    gatt: GattHandle?,
    target: GattOperationTarget,
    operation: QueuedDescriptorOperation,
    onWriteStarted: (ByteArray) -> Unit,
): BleFailure? {
    val ref = operation.descriptor
    val kind = if (operation is QueuedDescriptorWrite) OperationKind.WriteDescriptor else OperationKind.ReadDescriptor
    val descriptor = target.descriptorAt(ref.uuid.value, ref.instanceIndex)
    if (operation !is QueuedDescriptorWrite) {
        descriptor ?: return GattFailureFactory.characteristicNotFound(session, kind)
        return if (driver.readDescriptor(session.deviceId, gatt, descriptor)) {
            null
        } else {
            GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.ReadDescriptor, kind = kind)
        }
    }
    val value = operation.valueCopy()
    val failure = when {
        descriptor == null -> GattFailureFactory.characteristicNotFound(session, kind)
        operation.isCccd -> startAppCccdWrite(driver, session, gatt, target.characteristic, descriptor, operation, value)
        driver.writeDescriptor(session.deviceId, gatt, descriptor, value) -> null
        else -> GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = kind)
    }
    if (failure == null) onWriteStarted(value)
    return failure
}

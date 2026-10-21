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
        operation.isCccd -> startCccdWrite(driver, session, gatt, target.characteristic, descriptor, value, kind)
        driver.writeDescriptor(session.deviceId, gatt, descriptor, value) -> null
        else -> GattFailureFactory.startRejected(session, FailurePhase.Operation, AndroidApiOperation.WriteDescriptor, kind = kind)
    }
    if (failure == null) onWriteStarted(value)
    return failure
}

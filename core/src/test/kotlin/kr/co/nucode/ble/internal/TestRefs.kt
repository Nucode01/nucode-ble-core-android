package kr.co.nucode.ble.internal

import java.util.UUID
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.ServiceRef

/**
 * Test-only constructor mirroring the deleted internal flat characteristic reference shape
 * (serviceUuid, charUuid, instanceIndex, serviceInstanceIndex), so mechanical test call sites
 * keep compiling against the public [CharacteristicRef].
 */
internal fun charRef(
    serviceUuid: UUID,
    charUuid: UUID,
    instanceIndex: Int = 0,
    serviceInstanceIndex: Int = 0,
): CharacteristicRef = CharacteristicRef(
    service = ServiceRef(BleUuid(serviceUuid), serviceInstanceIndex),
    uuid = BleUuid(charUuid),
    instanceIndex = instanceIndex,
)

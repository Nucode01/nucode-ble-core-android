package kr.co.nucode.ble.harness

import java.util.UUID
import kr.co.nucode.ble.internal.model.PlatformCharacteristic
import kr.co.nucode.ble.internal.model.PlatformDescriptor

/** 테스트용 불투명 characteristic. equals 는 재정의하지 않는다 — 동일성은 참조 기준이다. */
internal class FakePlatformCharacteristic(val uuid: UUID, val properties: Int = 0) : PlatformCharacteristic

/** 테스트용 불투명 descriptor. equals 는 재정의하지 않는다 — 동일성은 참조 기준이다. */
internal class FakePlatformDescriptor(val uuid: UUID) : PlatformDescriptor

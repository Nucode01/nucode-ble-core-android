package kr.co.nucode.ble.internal.model

// Session(session 패키지)도 들고 있어야 해서 gatt 가 아니라 leaf 패키지에 둔다 — gatt 에 두면 session→gatt 순환이 생긴다.

/** 연결된 플랫폼 GATT handle 을 가리키는 불투명 타입. 실제 객체는 플랫폼 driver 만 안다. */
internal interface GattHandle

/** 플랫폼 characteristic 객체를 가리키는 불투명 타입. 명령 시작 시 driver 에 되돌려준다. */
internal interface PlatformCharacteristic

/** 플랫폼 descriptor 객체를 가리키는 불투명 타입. CCCD write 시 driver 에 되돌려준다. */
internal interface PlatformDescriptor

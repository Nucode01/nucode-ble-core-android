package kr.co.nucode.ble.contract

internal object Lanes {
    const val STATE = "state"
    const val RESULT = "result"
    const val NOTIFY = "notify"
    const val ACTIVE = "active"

    // 확장 레인: 시나리오의 expect 에 있을 때만 기록·출력한다(README §기록).
    const val ADAPTER = "adapter"
    const val SCAN = "scan"
    const val FOUND = "found"
    const val SERVICES = "services"
    const val PROPERTIES = "properties"
    const val DESCRIPTORS = "descriptors"
    const val AD_TYPES = "adTypes"
    const val WRITE_LIMITS = "writeLimits"
    const val ANDROID_MTU = "androidMtu"
    const val PEER = "peer"

    val BASE = listOf(STATE, RESULT, NOTIFY, ACTIVE)
    val EXTENSION = listOf(ADAPTER, SCAN, FOUND, SERVICES, PROPERTIES, DESCRIPTORS, AD_TYPES, WRITE_LIMITS, ANDROID_MTU, PEER)

    /** CONTRACT 줄의 출력 순서. */
    val ALL = BASE + EXTENSION

    /** `await <레인> is` 를 쓸 수 있는 레인. */
    val AWAIT_IS = listOf(STATE, ACTIVE, ADAPTER, SCAN, SERVICES, AD_TYPES, WRITE_LIMITS, ANDROID_MTU)

    /** `await <레인> count` 를 쓸 수 있는 레인. */
    val AWAIT_COUNT = listOf(NOTIFY, FOUND)

    /** 연속으로 같은 값은 하나로 합치는 레인(현재 값 레인). */
    val COALESCED = listOf(ACTIVE, ADAPTER, SCAN, SERVICES, PROPERTIES, DESCRIPTORS, AD_TYPES, WRITE_LIMITS, ANDROID_MTU)
}

package kr.co.nucode.ble.contract

/** 기기 사건(README §기기 사건). 실기기 러너는 [command] 를 probe.control 에 쓰고, sim 은 가짜 기기로 흉내 낸다. */
internal sealed interface DeviceEvent {
    /** probe.control 에 쓸 펌웨어 명령. */
    val command: String

    data object LinkDrop : DeviceEvent {
        override val command = "LINK_DROP"
    }

    data class LinkDropHide(val ms: Long) : DeviceEvent {
        override val command get() = "LINK_DROP_HIDE $ms"
    }

    data class Notify(val count: Int) : DeviceEvent {
        override val command get() = "NOTIFY $count"
    }

    data class ReadNotify(val on: Boolean) : DeviceEvent {
        override val command get() = if (on) "READ_NOTIFY ON" else "READ_NOTIFY OFF"
    }

    /** extended 광고 세트를 [ms] 동안 켠다(Android 전용, CANDIDATE-36b). */
    data class ExtAdv(val ms: Long) : DeviceEvent {
        override val command get() = "ADV_EXT_FOR $ms"
    }
}

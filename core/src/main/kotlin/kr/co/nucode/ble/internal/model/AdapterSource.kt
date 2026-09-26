package kr.co.nucode.ble.internal.model

// AdapterMonitor(adapter 패키지)가 platform.android 를 이미 참조하므로 adapter 패키지에 두면 platform→adapter 순환이 생긴다 — leaf 패키지에 둔다.

/**
 * 어댑터 존재·on/off 조회와 on/off 변화 통지의 포트. 플랫폼 어댑터 객체와 브로드캐스트 리시버는
 * 구현(platform)만 안다 — [kr.co.nucode.ble.internal.adapter.AdapterMonitor] 는 이 포트만 본다.
 */
internal interface AdapterSource {
    /** 지금의 어댑터. 없으면 null. 매 호출 시 다시 얻는다(BT off → on 사이 낡은 참조 금지). */
    fun current(): AdapterHandle?

    /** on/off 변화 통지 등록 — [onStateChanged] 는 플랫폼 스레드에서 불린다(호출부가 홉한다). */
    fun startListening(onStateChanged: () -> Unit)

    /** 통지 해제. */
    fun stopListening()
}

/** 한 번 얻은 어댑터 참조 — 존재 확인과 on/off 조회가 같은 객체를 보도록 한다. */
internal fun interface AdapterHandle {
    fun isEnabled(): Boolean
}

package kr.co.nucode.ble.internal.session

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.FailureContext
import kr.co.nucode.ble.FailureMapper

internal object SessionFailures {
    private fun connectionContext(session: Session): FailureContext = session.lifecycleFailureContext()

    fun unattributedLinkDisconnect(session: Session, status: Int): BleFailure =
        FailureMapper.unattributedLinkDisconnect(
            statusCode = status,
            context = connectionContext(session),
        )
}

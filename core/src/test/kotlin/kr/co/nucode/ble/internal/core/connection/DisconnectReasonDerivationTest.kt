package kr.co.nucode.ble.internal.core.connection

import kr.co.nucode.ble.BleFailure
import kr.co.nucode.ble.DisconnectReason
import kr.co.nucode.ble.FailureCategory
import kr.co.nucode.ble.FailureCode
import kr.co.nucode.ble.FailurePhase
import kr.co.nucode.ble.Retryability
import org.junit.Assert.assertEquals
import org.junit.Test

/** 고정된 종결 원인 → 공개 [DisconnectReason] 파생([toDisconnectReason]) 표. */
class DisconnectReasonDerivationTest {

    private fun failure(code: String) = BleFailure(
        category = FailureCategory("connection"),
        code = FailureCode(code),
        phase = FailurePhase.Connect,
        retryability = Retryability.TransientMayHelp,
    )

    private val timeoutCause = TerminalCause(TerminalCleanupReason.LinkTimeout, failure("LINK_TIMEOUT"))
    private val platformFallback = TerminalCause(TerminalCleanupReason.PlatformDisconnected, null)

    @Test
    fun `종결 원인에서 DisconnectReason을 파생한다`() {
        val unattributed = failure("LINK_FAILED")

        assertEquals(
            DisconnectReason.Failed(timeoutCause.failure!!),
            timeoutCause.toDisconnectReason(generationStarted = true, unattributedFailure = unattributed),
        )
        assertEquals(
            DisconnectReason.Requested,
            TerminalCause(TerminalCleanupReason.UserRequested, null).toDisconnectReason(true, unattributed),
        )
        assertEquals(
            DisconnectReason.ClientClosed,
            TerminalCause(TerminalCleanupReason.ClientClosed, null).toDisconnectReason(true, unattributed),
        )
        assertEquals(DisconnectReason.Failed(unattributed), platformFallback.toDisconnectReason(true, unattributed))
        assertEquals(DisconnectReason.Failed(unattributed), null.toDisconnectReason(true, unattributed))
        assertEquals(DisconnectReason.Initial, null.toDisconnectReason(false, unattributed))
    }
}

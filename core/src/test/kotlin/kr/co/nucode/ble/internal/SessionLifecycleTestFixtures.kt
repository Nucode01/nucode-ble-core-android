package kr.co.nucode.ble.internal

import kr.co.nucode.ble.ConnectionState
import kr.co.nucode.ble.internal.session.Session
import kr.co.nucode.ble.setStateForTest as driveStateForTest

internal fun Session.setStateForTest(target: ConnectionState) =
    driveStateForTest(target)

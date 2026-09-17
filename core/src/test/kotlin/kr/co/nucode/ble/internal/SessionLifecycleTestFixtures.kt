package kr.co.nucode.ble.internal

import kr.co.nucode.ble.ConnState
import kr.co.nucode.ble.setStateForTest as driveStateForTest

internal fun Session.setStateForTest(target: ConnState) =
    driveStateForTest(target)

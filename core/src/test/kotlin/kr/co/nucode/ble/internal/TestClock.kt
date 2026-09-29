package kr.co.nucode.ble.internal

import kr.co.nucode.ble.internal.platform.android.AndroidClock
import kr.co.nucode.ble.internal.port.ClockPort

/** 시험 시계 — JVM 단위 시험에서 `SystemClock` 은 동작하지 않으므로 단조 시각을 nanoTime 으로 읽는다. */
internal val TestClock: ClockPort = AndroidClock(elapsed = { System.nanoTime() / 1_000_000 })

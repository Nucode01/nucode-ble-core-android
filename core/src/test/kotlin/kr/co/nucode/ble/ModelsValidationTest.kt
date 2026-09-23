package kr.co.nucode.ble

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 공개 옵션 타입의 require 가드 회귀 테스트(app-tunables review — plan §잠재에러가
 * "검증: 테스트"로 약속한 항목들). require 가 지워지면 여기서 red 가 난다 —
 * 컴파일·기능 테스트는 유효값만 지나가서 가드 소실을 못 잡는다.
 */
class ModelsValidationTest {

    @Test
    fun `Fixed chunking은 양수만 허용한다`() {
        assertThrows(IllegalArgumentException::class.java) { BleChunking.Fixed(0) }
        assertThrows(IllegalArgumentException::class.java) { BleChunking.Fixed(-1) }
        BleChunking.Fixed(1)
    }

    // ── BleConfig ────────────────────────────────────────────────────────────

    @Test
    fun `operationTimeoutMillis 0 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { BleConfig(operationTimeoutMillis = 0) }
    }

    @Test
    fun `operationTimeoutMillis 음수는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { BleConfig(operationTimeoutMillis = -1) }
    }

    @Test
    fun `BleConfig 진단 기본값은 logger 없이 raw capture를 끈다`() {
        val config = BleConfig()

        org.junit.Assert.assertEquals(null, config.logger)
        org.junit.Assert.assertFalse(config.diagnosticOptions.rawPayloadsEnabled)
    }

    // ── ConnectOptions.requestedMtu — ATT 범위 23..517 경계 ─────────────────

    @Test
    fun `requestedMtu 22 는 거부되고 23 은 허용된다`() {
        assertThrows(IllegalArgumentException::class.java) { ConnectOptions(requestedMtu = 22) }
        ConnectOptions(requestedMtu = 23)
    }

    @Test
    fun `requestedMtu 518 은 거부되고 517 은 허용된다`() {
        assertThrows(IllegalArgumentException::class.java) { ConnectOptions(requestedMtu = 518) }
        ConnectOptions(requestedMtu = 517)
    }

    // ── ReconnectPolicy ──────────────────────────────────────────────────────

    @Test
    fun `maxAttempts 음수는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(maxAttempts = -1) }
    }

    @Test
    fun `baseDelayMillis 0 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(baseDelayMillis = 0) }
    }

    @Test
    fun `maxDelay 가 baseDelay 보다 작으면 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(baseDelayMillis = 1_000, maxDelayMillis = 500)
        }
    }

    /** plan blecore-lifecycle §감사결과 C — attemptTimeoutMillis 신설 require 회귀. */
    @Test
    fun `attemptTimeoutMillis 0 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(attemptTimeoutMillis = 0) }
    }

    @Test
    fun `attemptTimeoutMillis 음수는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectPolicy(attemptTimeoutMillis = -1) }
    }

    // ── ManufacturerDataFilter.companyId — 16-bit 경계 ──────────────────────

    @Test
    fun `companyId 는 16비트 범위를 벗어나면 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { BleManufacturerDataFilter(companyId = -1) }
        assertThrows(IllegalArgumentException::class.java) { BleManufacturerDataFilter(companyId = 0x1_0000) }
        // 경계 유효값 — 여기가 통과해야 상한이 off-by-one 이 아니다
        BleManufacturerDataFilter(companyId = 0)
        BleManufacturerDataFilter(companyId = 0xFFFF)
    }

    // ── BleConfig.rediscoveryTimeoutMillis — cold-cache 재발견 v2 task 1 ────

    @Test
    fun `rediscoveryTimeoutMillis 는 linkTimeoutMillis 이하여야 하고 0 은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleConfig(linkTimeoutMillis = 1_000, rediscoveryTimeoutMillis = 2_000)
        }
        assertThrows(IllegalArgumentException::class.java) { BleConfig(rediscoveryTimeoutMillis = 0) }
    }

    // ── BleConfig.eventBufferCapacity — plan K task 1 ────────────────────────

    @Test
    fun `eventBufferCapacity 는 1 을 허용하고 0 과 음수는 거부된다`() {
        BleConfig(eventBufferCapacity = 1)
        assertThrows(IllegalArgumentException::class.java) { BleConfig(eventBufferCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { BleConfig(eventBufferCapacity = -1) }
    }
}

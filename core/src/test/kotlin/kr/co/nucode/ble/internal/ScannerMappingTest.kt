package kr.co.nucode.ble.internal

import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleServiceDataFilter
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.internal.scan.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공개 `ScanFilter` → Android `ScanFilter` 매핑([Scanner.toPlatformFilters], 순수 함수)을
 * 검증한다. `Scanner` 생성자가 요구하는 emit/scope/scanner 는 이 테스트에서 실제로 쓰이지
 * 않는다(콜백 스레드 홉이 없는 순수 계산만 호출) — 최소 스텁으로 인스턴스만 만든다.
 *
 * ⚠️ 실측(이 파일 작성 시): 이 프로젝트의 android 단위 테스트 스텁 jar(AGP 기본
 * `returnDefaultValues = false`)는 `BluetoothGattCharacteristic` 의 getter 만이 아니라
 * `android.bluetooth.le.ScanFilter.Builder` 의 **모든 인스턴스 메서드**(`setServiceUuid` ·
 * `setManufacturerData` · `setServiceData` · `build()` 포함)를 부르면 즉시
 * `RuntimeException("Method ... not mocked")` 를 던진다 — 생성자(`Builder()`)만 예외
 * (OperationQueueTest 의 `BluetoothGattCharacteristic` 생성자 선례와 같은 이유: 필드 대입만
 * 하는 코드는 스텁 변환에서 제외된다). 그래서 [Scanner.toPlatformFilters] 가 비어있지 않은
 * `ScanFilter` 를 받아 실제로 플랫폼 `ScanFilter` 를 만들어내는 경로(개수·매핑 내용 검증)는
 * 이 단위 테스트 환경에서 원천적으로 실행 불가능하다 — 실기기/계측 테스트의 몫이다. 여기서는
 * 그 경로를 타지 않는 범위(빈 입력 → 빈 리스트, 그리고 값 타입 자체의 require/equals)만 검증한다.
 */
class ScannerMappingTest {

    private val scanner = Scanner(
        emit = {},
        scope = CoroutineScope(EmptyCoroutineContext),
        scanner = { null },
    )

    // ── 경계: 전부 빈 ScanFilter ──────────────────────────────────

    @Test
    fun `전부 빈 ScanFilter면 빈 리스트`() {
        val result = scanner.toPlatformFilters(BleScanFilter())

        assertTrue(result.isEmpty())
    }

    // ── require: Models 쪽 mask 검증(여기서 커버) ───────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `ManufacturerDataFilter가 mask만 있고 data 없으면 IAE`() {
        BleManufacturerDataFilter(companyId = 1, data = null, mask = byteArrayOf(0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ManufacturerDataFilter가 mask와 data 길이가 다르면 IAE`() {
        BleManufacturerDataFilter(companyId = 1, data = byteArrayOf(0x01, 0x02), mask = byteArrayOf(0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ServiceDataFilter가 mask만 있고 data 없으면 IAE`() {
        BleServiceDataFilter(serviceUuid = BleUuid(UUID.randomUUID()), data = null, mask = byteArrayOf(0x01))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ServiceDataFilter가 mask와 data 길이가 다르면 IAE`() {
        BleServiceDataFilter(
            serviceUuid = BleUuid(UUID.randomUUID()),
            data = byteArrayOf(0x01, 0x02),
            mask = byteArrayOf(0x01),
        )
    }

    // ── equals: 같은 내용 ByteArray 두 필터 ────────────────────────

    @Test
    fun `ManufacturerDataFilter는 내용이 같은 참조 다른 ByteArray끼리 equals가 true`() {
        val a = BleManufacturerDataFilter(
            companyId = 1,
            data = byteArrayOf(1, 2, 3),
            mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        val b = BleManufacturerDataFilter(
            companyId = 1,
            data = byteArrayOf(1, 2, 3),
            mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

        assertTrue(a.data !== b.data)
        assertEquals(a, b)
    }

    @Test
    fun `ServiceDataFilter는 내용이 같은 참조 다른 ByteArray끼리 equals가 true`() {
        val uuid = BleUuid(UUID.randomUUID())
        val a = BleServiceDataFilter(serviceUuid = uuid, data = byteArrayOf(9, 8, 7))
        val b = BleServiceDataFilter(serviceUuid = uuid, data = byteArrayOf(9, 8, 7))

        assertTrue(a.data !== b.data)
        assertEquals(a, b)
    }
}

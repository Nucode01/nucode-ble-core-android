package kr.co.nucode.ble.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kr.co.nucode.ble.CharacteristicRef
import kr.co.nucode.ble.PeripheralId
import kr.co.nucode.ble.GattService
import kr.co.nucode.ble.BleManufacturerDataFilter
import kr.co.nucode.ble.BleScanFilter
import kr.co.nucode.ble.BleUuid
import kr.co.nucode.ble.OfficialBleClientEvent
import kr.co.nucode.ble.ScanRequest
import kr.co.nucode.ble.ScanResult

/**
 * plan .plans/blecore-sample/v1.md task 3 — 권한 플로우·컨트롤 전체·로그 뷰.
 *
 * SampleApp 이 이미 events 를 링버퍼로 쌓아 로그를 렌더한다(SampleApp.kt) — 이 Activity 는
 * ①로그 렌더 ②UI 상태용 이벤트 소비(기기·characteristic 리스트) ③명령 버튼만 담당한다.
 * 의존성 0 프로그래매틱 View — 레이아웃 XML·리소스를 만들지 않는다(plan §접근 결정②).
 */
class MainActivity : Activity() {

    private val app: SampleApp get() = application as SampleApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 1. 상태 표시줄
    private lateinit var adapterStateText: TextView
    private lateinit var selectionText: TextView

    // 3. 필터 입력
    private lateinit var noneRadio: RadioButton
    private lateinit var serviceRadio: RadioButton
    private lateinit var mfgRadio: RadioButton
    private lateinit var serviceUuidInput: EditText
    private lateinit var companyIdInput: EditText
    private lateinit var mfgDataInput: EditText
    private lateinit var mfgMaskInput: EditText

    // 5. 스캔 결과 — Map<PeripheralId, 최근표시문자열> 유지(plan §구조 UI 명세)
    private lateinit var scanResultsContainer: LinearLayout
    private val scanResultViews = mutableMapOf<PeripheralId, TextView>()
    private var selectedDeviceId: PeripheralId? = null

    // 7. characteristic 리스트
    private lateinit var charListContainer: LinearLayout
    private val charViews = mutableMapOf<CharacteristicRef, TextView>()
    private var selectedCharRef: CharacteristicRef? = null
    private var subscriptionJob: Job? = null
    private var notificationJob: Job? = null
    private var notificationGeneration: Long = 0

    // 9. 로그 뷰
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val controls = buildControls()
        val controlScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(controls)
        }

        val logArea = buildLogArea()

        root.addView(controlScroll)
        root.addView(logArea)
        setContentView(root)

        renderLogFromScratch()
        app.onLogAppended = { line -> appendLogLine(line) }

        scope.launch {
            app.client.adapterState.collect { state -> adapterStateText.text = "Adapter: $state" }
        }

        scope.launch {
            app.client.scanResults.collect(::handleScanResult)
        }
        scope.launch {
            app.client.events.collect { event ->
                if (event is OfficialBleClientEvent.Services) handleServicesDiscovered(event)
            }
        }

        // 콜드 스타트(화면 회전 등) 복원 — Activity 재생성 후에도 기기 선택·[해제]가 가능해진다.
        scope.launch {
            runCatching { app.client.snapshot() }.onSuccess { snap ->
                snap.sessions.forEach { session ->
                    addOrUpdateScanRow(
                        session.peripheralId,
                        "restored ${session.peripheralId} state=${session.connectionState}",
                    )
                }
            }
        }
    }

    // ── 컨트롤 영역 조립 ─────────────────────────────────────────────────────

    private fun buildControls(): LinearLayout {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }

        // 1. 상태 표시줄
        adapterStateText = TextView(this).apply { text = "Adapter: -" }
        selectionText = TextView(this).apply { text = "선택: 기기 없음 / characteristic 없음" }
        controls.addView(adapterStateText)
        controls.addView(selectionText)

        // 2. 권한 요청
        controls.addView(
            Button(this).apply {
                text = "권한 요청"
                setOnClickListener { requestBlePermissions() }
            },
        )

        // 3. 필터 입력
        noneRadio = RadioButton(this).apply { text = "없음"; id = View.generateViewId(); isChecked = true }
        serviceRadio = RadioButton(this).apply { text = "serviceUuid"; id = View.generateViewId() }
        mfgRadio = RadioButton(this).apply { text = "manufacturerData"; id = View.generateViewId() }
        val filterGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(noneRadio)
            addView(serviceRadio)
            addView(mfgRadio)
        }
        controls.addView(filterGroup)

        serviceUuidInput = EditText(this).apply {
            hint = "serviceUuid (예: 0000180d-0000-1000-8000-00805f9b34fb)"
        }
        controls.addView(serviceUuidInput)

        companyIdInput = EditText(this).apply { hint = "companyId (예: 0x004C 또는 76)" }
        controls.addView(companyIdInput)

        mfgDataInput = EditText(this).apply { hint = "mfg data hex (예: 0215, 빈칸=null)" }
        controls.addView(mfgDataInput)

        mfgMaskInput = EditText(this).apply { hint = "mfg mask hex (빈칸=null)" }
        controls.addView(mfgMaskInput)

        // 4. 스캔/정지
        val scanButton = Button(this).apply { text = "스캔" }
        val stopButton = Button(this).apply { text = "정지" }
        controls.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scanButton)
                addView(stopButton)
            },
        )
        scanButton.setOnClickListener {
            scope.launch {
                try {
                    app.client.startScan(ScanRequest(filter = buildScanFilter()))
                } catch (e: Exception) {
                    app.log("ERR startScan $e")
                }
            }
        }
        stopButton.setOnClickListener {
            scope.launch {
                try {
                    app.client.stopScan()
                } catch (e: Exception) {
                    app.log("ERR stopScan $e")
                }
            }
        }

        // 5. 스캔 결과 리스트 — ScrollView 안 max ~150dp
        controls.addView(TextView(this).apply { text = "스캔 결과 (탭하여 선택)" })
        scanResultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
                addView(scanResultsContainer)
            },
        )

        // 6. 연결/해제 — Android autoConnect와 requested MTU는 공통 API가 아니다.
        val connectButton = Button(this).apply { text = "연결" }
        val disconnectButton = Button(this).apply { text = "해제" }
        controls.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(connectButton)
                addView(disconnectButton)
            },
        )
        connectButton.setOnClickListener {
            // M-3: connect-on-connected 거동 검증용 — 연결 중에도 다시 누를 수 있어야 한다.
            // 가드를 두지 않는다(plan §구조 UI 명세 6).
            val target = selectedDeviceId
            if (target == null) {
                app.log("연결할 기기를 먼저 선택하라")
            } else {
                scope.launch {
                    try {
                        app.client.connect(target)
                    } catch (e: Exception) {
                        app.log("ERR connect $e")
                    }
                }
            }
        }
        disconnectButton.setOnClickListener {
            val target = selectedDeviceId
            if (target == null) {
                app.log("해제할 기기를 먼저 선택하라")
            } else {
                scope.launch {
                    try {
                        app.client.session(target).disconnect()
                    } catch (e: Exception) {
                        app.log("ERR disconnect $e")
                    }
                }
            }
        }

        // 7. characteristic 리스트
        controls.addView(TextView(this).apply { text = "Characteristic 목록 (탭하여 선택)" })
        charListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(charListContainer)

        // 8. 동작 버튼
        val readButton = Button(this).apply { text = "Read" }
        val write20Button = Button(this).apply { text = "Write 20B" }
        val write600Button = Button(this).apply { text = "Write 600B" }
        controls.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(readButton)
                addView(write20Button)
                addView(write600Button)
            },
        )
        readButton.setOnClickListener {
            val target = requireTarget() ?: return@setOnClickListener
            scope.launch {
                runCatching { app.client.session(target.first).read(target.second) }
                    .onSuccess { value -> app.log("READ ${value.size}B ${value.toHexPreview()}") }
                    .onFailure { failure -> app.log("ERR read $failure") }
            }
        }
        write20Button.setOnClickListener { onWrite(20) }
        write600Button.setOnClickListener { onWrite(600) }

        val subscribeButton = Button(this).apply { text = "Subscribe" }
        val unsubscribeButton = Button(this).apply { text = "Unsubscribe" }
        controls.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(subscribeButton)
                addView(unsubscribeButton)
            },
        )
        subscribeButton.setOnClickListener {
            val target = requireTarget() ?: return@setOnClickListener
            cancelNotificationWork()
            val generation = notificationGeneration
            subscriptionJob = scope.launch {
                var collector: Job? = null
                try {
                    val session = app.client.session(target.first)
                    collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        session.notifications(target.second).collect { value ->
                            app.log("NTFY ${target.second.uuid} ${value.size}B ${value.toHexPreview()}")
                        }
                    }
                    notificationJob = collector
                    session.subscribe(target.second)
                    if (
                        generation != notificationGeneration ||
                        selectedDeviceId != target.first ||
                        selectedCharRef != target.second
                    ) {
                        collector?.cancel()
                        return@launch
                    }
                    app.log("SUBSCRIBED ${target.second}")
                } catch (cancelled: CancellationException) {
                    collector?.cancel()
                    throw cancelled
                } catch (e: Exception) {
                    collector?.cancel()
                    if (notificationJob === collector) notificationJob = null
                    app.log("ERR subscribe $e")
                }
            }
        }
        unsubscribeButton.setOnClickListener {
            val target = requireTarget() ?: return@setOnClickListener
            cancelNotificationWork()
            scope.launch {
                try {
                    app.client.session(target.first).unsubscribe(target.second)
                    app.log("UNSUBSCRIBED ${target.second}")
                } catch (e: Exception) {
                    app.log("ERR unsubscribe $e")
                }
            }
        }

        val snapshotButton = Button(this).apply { text = "Snapshot" }
        val closeButton = Button(this).apply { text = "close()" }
        controls.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(snapshotButton)
                addView(closeButton)
            },
        )
        snapshotButton.setOnClickListener {
            scope.launch {
                try {
                    app.log("SNAPSHOT ${app.client.snapshot()}")
                } catch (e: Exception) {
                    app.log("ERR snapshot $e")
                }
            }
        }
        closeButton.setOnClickListener {
            cancelNotificationWork()
            scope.launch {
                try {
                    app.client.close()
                    app.log("closed — 이후 명령은 throw, 앱 재시작 필요")
                } catch (e: Exception) {
                    app.log("ERR close $e")
                }
            }
        }

        return controls
    }

    private fun buildLogArea(): LinearLayout {
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(logText)
        }
        val clearButton = Button(this).apply {
            text = "클리어"
            setOnClickListener {
                app.clearLog()
                renderLogFromScratch()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(clearButton)
            addView(logScroll)
        }
    }

    // ── 명령 핸들러 보조 ─────────────────────────────────────────────────────

    private fun onWrite(size: Int) {
        val target = requireTarget() ?: return
        scope.launch {
            try {
                val result = app.client.session(target.first)
                    .write(target.second, ByteArray(size) { it.toByte() })
                app.log("WRITE${size} $result")
            } catch (e: Exception) {
                app.log("ERR write$size $e")
            }
        }
    }

    private fun requireTarget(): Pair<PeripheralId, CharacteristicRef>? {
        val deviceId = selectedDeviceId
        val charRef = selectedCharRef
        if (deviceId == null || charRef == null) {
            app.log("기기와 characteristic 을 먼저 선택하라")
            return null
        }
        return deviceId to charRef
    }

    // "0x" 접두 허용 정수 파싱. 실패는 호출부의 try/catch → app.log("ERR ...") 로 처리한다
    // (plan §잠재 에러 "EditText UUID/companyId 파싱 실패 crash" — 여기서 crash 시키지 않는다).
    private fun parseCompanyId(raw: String): Int {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("0x", ignoreCase = true)) {
            trimmed.substring(2).toInt(16)
        } else {
            trimmed.toInt()
        }
    }

    // hex 문자열 → ByteArray. 빈칸/공백만이면 null, 짝수 길이가 아니면 예외 — 호출부의
    // try/catch → app.log("ERR ...") 로 처리한다(plan task 5 §①, parseCompanyId 와 동일 원칙).
    private fun parseHex(raw: String): ByteArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.length % 2 == 0) { "hex 길이는 짝수여야 한다: $trimmed" }
        return ByteArray(trimmed.length / 2) { i ->
            trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun buildScanFilter(): BleScanFilter = when {
        serviceRadio.isChecked -> BleScanFilter(
            serviceUuids = listOf(BleUuid(UUID.fromString(serviceUuidInput.text.toString().trim()))),
        )
        mfgRadio.isChecked -> BleScanFilter(
            manufacturerData = listOf(
                BleManufacturerDataFilter(
                    parseCompanyId(companyIdInput.text.toString()),
                    parseHex(mfgDataInput.text.toString()),
                    parseHex(mfgMaskInput.text.toString()),
                ),
            ),
        )
        else -> BleScanFilter() // "없음" — 기본 BleScanFilter() 로 UNFILTERED_SCAN Diagnostic 유도
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val summary = permissions.indices.joinToString(", ") { i ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            "${permissions[i]}=${if (granted) "granted" else "denied"}"
        }
        app.log("PERM result [$requestCode]: $summary")
        app.log("권한 변경은 다음 명령 진입 시 adapterState 에 반영된다 — [스캔] 등으로 확인")
    }

    // ── 이벤트 소비(UI 상태) ─────────────────────────────────────────────────

    private fun handleScanResult(result: ScanResult) {
        addOrUpdateScanRow(
            result.peripheralId,
            "${result.peripheralId}  rssi=${result.rssi}  ${result.advertisement.localName ?: ""}",
        )
    }

    // scanResultsContainer 행 추가/갱신 — handleScanResult 와 snapshot 복원 시드가 공유(plan task 5 §④).
    private fun addOrUpdateScanRow(deviceId: PeripheralId, display: String) {
        val existing = scanResultViews[deviceId]
        if (existing != null) {
            existing.text = display
        } else {
            val view = TextView(this).apply {
                text = display
                val pad = dp(4)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { selectDevice(deviceId) }
            }
            scanResultViews[deviceId] = view
            scanResultsContainer.addView(view)
        }
    }

    private fun selectDevice(deviceId: PeripheralId) {
        cancelNotificationWork()
        selectedDeviceId = deviceId
        scanResultViews.forEach { (id, view) ->
            view.setBackgroundColor(if (id == deviceId) Color.LTGRAY else Color.TRANSPARENT)
        }
        // 기기를 새로 선택하면 이전 기기의 characteristic 선택은 무효다 — SampleApp 캐시로 재렌더.
        renderCharList(app.discoveredServices[deviceId])
        updateSelectionText()
    }

    private fun handleServicesDiscovered(event: OfficialBleClientEvent.Services) {
        if (event.peripheralId != selectedDeviceId) return
        // 캐시 갱신은 SampleApp 몫 — 여기는 선택 기기일 때 렌더만.
        renderCharList(event.services)
        updateSelectionText()
    }

    private fun renderCharList(services: List<GattService>?) {
        cancelNotificationWork()
        charListContainer.removeAllViews()
        charViews.clear()
        selectedCharRef = null
        services?.forEach { service ->
            for (characteristic in service.characteristics) {
                val label = "${service.ref.uuid} / ${characteristic.ref.uuid} " +
                    "[${characteristic.properties.joinToString(",")}]"
                val view = TextView(this).apply {
                    text = label
                    val pad = dp(4)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { selectChar(characteristic.ref) }
                }
                charViews[characteristic.ref] = view
                charListContainer.addView(view)
            }
        }
    }

    private fun selectChar(charRef: CharacteristicRef) {
        cancelNotificationWork()
        selectedCharRef = charRef
        charViews.forEach { (ref, view) ->
            view.setBackgroundColor(if (ref == charRef) Color.LTGRAY else Color.TRANSPARENT)
        }
        updateSelectionText()
    }

    private fun updateSelectionText() {
        val deviceLabel = selectedDeviceId?.toString() ?: "없음"
        val charLabel = selectedCharRef?.let {
            "${it.service.uuid}/${it.uuid}#${it.instanceIndex}"
        } ?: "없음"
        selectionText.text = "선택: 기기=$deviceLabel / characteristic=$charLabel"
    }

    private fun cancelNotificationWork() {
        notificationGeneration++
        subscriptionJob?.cancel()
        subscriptionJob = null
        notificationJob?.cancel()
        notificationJob = null
    }

    // ── 로그 렌더(렌더 전용 — 수집은 SampleApp) ────────────────────────────────

    // append 만으로는 SampleApp 의 링버퍼 cap(2000) 이 렌더에 반영되지 않는다 — 마지막 전체
    // 렌더 이후의 append 수를 세다가 threshold 를 넘으면 renderLogFromScratch() 로 재동기화한다.
    // TextView 는 최대 버퍼 cap + threshold(≈4000줄)로 유계, 재렌더는 2000 append 당 1회다.
    // 버퍼 크기로 시드하면 버퍼가 cap 에 찬 뒤 매 append 가 전체 재렌더를 유발한다(원 결함보다
    // 악화) — 반드시 0 으로 리셋한다(plan task 5 §③).
    private var appendsSinceRender = 0

    private fun renderLogFromScratch() {
        logText.text = app.logLines.joinToString("\n")
        appendsSinceRender = 0
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendLogLine(line: String) {
        appendsSinceRender++
        if (appendsSinceRender > LOG_RESYNC_THRESHOLD) {
            renderLogFromScratch()
            return
        }
        if (logText.text.isNullOrEmpty()) {
            logText.text = line
        } else {
            logText.append("\n$line")
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        app.onLogAppended = null
        cancelNotificationWork()
        scope.cancel()
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        const val LOG_RESYNC_THRESHOLD = 2000
    }
}

private fun ByteArray.toHexPreview(): String =
    take(8).joinToString(separator = "") { byte -> "%02X".format(byte) }

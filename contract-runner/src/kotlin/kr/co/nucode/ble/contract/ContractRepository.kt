package kr.co.nucode.ble.contract

/** contract/ 폴더를 읽는 방법. JVM 테스트는 파일, 계측 테스트는 test APK 의 assets 로 읽는다. */
internal interface ContractSource {
    /** [dir] 안의 파일 이름(하위 폴더 제외). */
    fun list(dir: String): List<String>
    fun read(path: String): String
}

/** 기기 정의와 시나리오를 모두 읽는다. 한 파일이라도 형식에 어긋나면 [ContractFormatException]. */
internal class ContractRepository(private val source: ContractSource) {

    val peripherals: Map<String, PeripheralProfile> by lazy {
        jsonFiles("peripherals").associate { name ->
            name to wrap("peripherals/$name.json") { ContractLoader.loadPeripheral(name, source.read("peripherals/$name.json")) }
        }
    }

    val scenarioNames: List<String> by lazy { jsonFiles("scenarios") }

    fun scenario(name: String): Scenario =
        wrap("scenarios/$name.json") { ContractLoader.loadScenario(name, source.read("scenarios/$name.json"), peripherals) }

    fun readText(path: String): String = source.read(path)

    private fun jsonFiles(dir: String): List<String> =
        source.list(dir).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()

    private inline fun <T> wrap(path: String, block: () -> T): T = try {
        block()
    } catch (e: ContractFormatException) {
        throw ContractFormatException("$path: ${e.message}")
    }
}

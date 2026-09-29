package kr.co.nucode.ble.contract

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * JVM 테스트가 contract/ 를 찾는 곳. Gradle 이 `ble.contract.dir` 을 넘긴다(core/build.gradle.kts).
 * `-PbleContractDir` 로 경로를 준 경우(CI)에 폴더가 없으면 실패하고, 기본 경로(../contract)에 없으면 건너뛴다 —
 * android 저장소만 따로 받은 경우를 위해서다.
 */
internal object ContractFiles {
    private val dir: File? = System.getProperty("ble.contract.dir")?.let(::File)
    private val required: Boolean = System.getProperty("ble.contract.required") == "true"

    val available: Boolean get() = dir?.isDirectory == true

    val repository: ContractRepository by lazy {
        check(available) { "contract 폴더가 없다: $dir" }
        ContractRepository(object : ContractSource {
            override fun list(dir: String): List<String> =
                File(this@ContractFiles.dir, dir).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

            override fun read(path: String): String = File(this@ContractFiles.dir, path).readText()
        })
    }

    /** 테스트 시작 시 부른다. */
    fun assumeAvailable() {
        if (required) check(available) { "-PbleContractDir 로 준 contract 폴더가 없다: $dir" }
        assumeTrue("contract 폴더 없음($dir) — 루트 저장소 안에서 돌리거나 -PbleContractDir 을 준다", available)
    }

    /** Parameterized 목록용. 폴더가 없으면 빈 목록(테스트는 [assumeAvailable] 가 판정). */
    fun scenarioNames(): List<String> {
        if (required) check(available) { "-PbleContractDir 로 준 contract 폴더가 없다: $dir" }
        return if (available) repository.scenarioNames else emptyList()
    }
}

package kr.co.nucode.ble

import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.ContractJson
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FailureCodeConstantsTest {
    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    private fun contractCodes(): Set<String> {
        val root = ContractJson.parse(ContractFiles.repository.readText("failure-codes.json")) as Map<*, *>
        return (root["codes"] as List<*>).map { (it as Map<*, *>)["code"] as String }.toSet()
    }

    /** Companion getters of a value class are name-mangled (`getNAME-<hash>`), so read them reflectively. */
    private fun constants(): Map<String, String> {
        val companion = FailureCode.Companion
        return companion.javaClass.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
            .associate { it.name.removePrefix("get").substringBefore('-') to (it.invoke(companion) as String) }
    }

    @Test
    fun `constant set equals the contract code set`() {
        val expected = contractCodes()
        assertEquals(38, expected.size)
        assertEquals(expected, constants().keys)
    }

    @Test
    fun `every constant value equals its name`() {
        constants().forEach { (name, value) -> assertEquals(name, value) }
    }
}

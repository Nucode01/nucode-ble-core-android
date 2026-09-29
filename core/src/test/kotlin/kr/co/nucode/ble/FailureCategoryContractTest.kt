package kr.co.nucode.ble

import kr.co.nucode.ble.contract.ContractFiles
import kr.co.nucode.ble.contract.ContractJson
import kr.co.nucode.ble.internal.FailureCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FailureCategoryContractTest {
    @Before
    fun setUp() = ContractFiles.assumeAvailable()

    private fun androidCodes(): Map<String, String> {
        val root = ContractJson.parse(ContractFiles.repository.readText("failure-codes.json")) as Map<*, *>
        return (root["codes"] as List<*>)
            .map { it as Map<*, *> }
            .filter { "android" in (it["platforms"] as List<*>) }
            .associate { it["code"] as String to it["category"] as String }
    }

    @Test
    fun `category table equals failure-codes json for every android code`() {
        val expected = androidCodes()
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, FailureCategories.byCode)
    }

    @Test
    fun `every FailureCatalog code has a category entry`() {
        val missing = FailureCatalog.specs.map { it.code }.filterNot { it in FailureCategories.byCode }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `unknown device is a validation failure`() {
        assertEquals("validation", FailureCategories.of("UNKNOWN_DEVICE").value)
    }
}

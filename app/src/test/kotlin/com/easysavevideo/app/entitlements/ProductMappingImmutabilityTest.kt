package com.easysavevideo.app.entitlements

import com.easysavevideo.core.model.EntitlementId
import org.junit.Assert.*
import org.junit.Test

class ProductMappingImmutabilityTest {
    private val stream = EntitlementId.STREAM_SAVE
    private val record = EntitlementId.RECORD_SAVE
    @Test fun callerCannotReplaceOrInsertProductAfterConstruction() {
        val input = mutableMapOf("test.existing" to setOf(stream))
        val mapping = ProductMapping(input)
        input["test.existing"] = setOf(record)
        input["test.new"] = setOf(record)
        assertEquals(setOf(stream), mapping.entitlementsFor("test.existing"))
        assertTrue(mapping.entitlementsFor("test.new").isEmpty())
    }
    @Test fun callerCannotMutateNestedSetOrRemoveExistingProduct() {
        val ids = mutableSetOf(stream)
        val input = mutableMapOf("test.existing" to ids)
        val mapping = ProductMapping(input)
        ids.clear(); ids.add(record); input.clear()
        assertEquals(setOf(stream), mapping.entitlementsFor("test.existing"))
    }
    @Test fun returnedMappingCannotBeMutatedOrAffectFutureLookup() {
        val mapping = ProductMapping(mapOf("test.existing" to setOf(stream, record)))
        val result = mapping.entitlementsFor("test.existing")
        assertThrows(UnsupportedOperationException::class.java) { (result as MutableSet<EntitlementId>).clear() }
        assertEquals(setOf(stream, record), mapping.entitlementsFor("test.existing"))
    }
    @Test fun emptyUnknownAndCaseDifferentProductsHaveNoGrants() {
        val mapping = ProductMapping(mapOf("test.existing" to setOf(stream)))
        for (id in listOf("", "test.unknown", "TEST.EXISTING")) assertTrue(mapping.entitlementsFor(id).isEmpty())
        assertTrue(ProductMapping(emptyMap()).entitlementsFor("test.existing").isEmpty())
    }
}

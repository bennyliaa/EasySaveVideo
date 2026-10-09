package com.easysavevideo.app.entitlements

import com.easysavevideo.core.model.EntitlementId
import java.util.Collections

/** Store-side mapping. Neither product IDs nor payment SDK types are exposed to Core. */
class ProductMapping(products: Map<String, Set<EntitlementId>>) {
    // Both the outer map and every nested set are copied; output sets cannot mutate this mapping.
    private val products: Map<String, Set<EntitlementId>> = Collections.unmodifiableMap(
        products.mapValues { (_, ids) -> Collections.unmodifiableSet(LinkedHashSet(ids)) },
    )

    fun entitlementsFor(productId: String): Set<EntitlementId> = products[productId] ?: emptySet()
}

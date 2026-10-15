package com.easysavevideo.app.entitlements
import androidx.compose.runtime.Composable
import com.easysavevideo.core.model.*
import com.easysavevideo.app.distribution.BuildDistribution
/** No purchase SDK, Fake source, product selection or unlock state exists in Release. */
object BuildEntitlements {
    val distribution = BuildDistribution.configuration
    val store: StorePurchaseBoundary = distribution.store
    val source: EntitlementSource = store
}
@Composable fun DevelopmentEntitlementPanel(canAct: () -> Boolean = { true }) = Unit

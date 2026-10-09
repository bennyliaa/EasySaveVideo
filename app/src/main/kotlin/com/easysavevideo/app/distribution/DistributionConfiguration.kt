package com.easysavevideo.app.distribution

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.entitlements.StorePurchaseBoundary
import com.easysavevideo.app.entitlements.UnconfiguredStoreAdapter

/** Build provenance, not installer detection, payment policy, or store acceptance. */
enum class DistributionChannel(val buildValue: String) {
    UNCONFIGURED("unconfigured"),
    GOOGLE_PLAY("google_play"),
    HUAWEI("huawei"),
    XIAOMI("xiaomi"),
    OPPO("oppo"),
    VIVO("vivo"),
    TENCENT_MYAPP("tencent_myapp");

    companion object {
        /** Strict build validation is primary; unfamiliar runtime values remain unconfigured. */
        fun fromBuildValue(value: String): DistributionChannel =
            entries.firstOrNull { it.buildValue == value } ?: UNCONFIGURED
    }
}

/** All configured identities deliberately share the same unavailable payment implementation. */
class DistributionConfiguration(val channel: DistributionChannel) {
    val store: StorePurchaseBoundary = UnconfiguredStoreAdapter()
}

object BuildDistribution {
    val configuration = DistributionConfiguration(DistributionChannel.fromBuildValue(BuildConfig.DISTRIBUTION_CHANNEL))
}

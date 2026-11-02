package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod

/** A network-only admission gate. Rechecked by network ports for each actual request. */
fun interface SaveNetworkGate { fun check() }

/** Completed transfer recovery and all capture/local work remain independent of network policy. */
fun SaveRecord.needsNetworkTransfer(): Boolean = transfer == null &&
    request.method in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)

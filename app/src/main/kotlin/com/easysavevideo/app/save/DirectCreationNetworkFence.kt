package com.easysavevideo.app.save

import com.easysavevideo.save.DirectWorkspacePresence

/** Worker-only durable lookup before any browser credential/session capture. */
internal enum class DirectCreationNetworkDecision { FRESH, EXISTING, RECOVERY }
internal fun inspectDirectCreationNetwork(
    readExisting: () -> Boolean,
    inspectWorkspace: () -> DirectWorkspacePresence,
): DirectCreationNetworkDecision = try {
    if (readExisting()) DirectCreationNetworkDecision.EXISTING
    else if (inspectWorkspace() == DirectWorkspacePresence.ABSENT) DirectCreationNetworkDecision.FRESH
    else DirectCreationNetworkDecision.RECOVERY
} catch (_: Exception) { DirectCreationNetworkDecision.RECOVERY }

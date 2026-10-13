package com.easysavevideo.save.network

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.net.HttpURLConnection

/**
 * open() has transferred ownership, but the watchdog does not exist until its constructor succeeds.
 * A failed thread/guard initialization must release that connection before the lane can be reused.
 */
internal fun <T> initializeNetworkOwner(connection: HttpURLConnection, releaseUnconfirmed: () -> Unit,
                                       initialize: () -> T): T = try {
    initialize()
} catch (failure: Exception) {
    closeUnclaimedConnection(connection, releaseUnconfirmed)
    throw failure
} catch (_: LinkageError) {
    closeUnclaimedConnection(connection, releaseUnconfirmed)
    throw SaveFailure(SaveError.UNKNOWN_TECHNICAL_ERROR)
}

private fun closeUnclaimedConnection(connection: HttpURLConnection, releaseUnconfirmed: () -> Unit) {
    try { connection.disconnect() }
    catch (_: Exception) {
        releaseUnconfirmed()
        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    } catch (_: LinkageError) {
        releaseUnconfirmed()
        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
    }
}

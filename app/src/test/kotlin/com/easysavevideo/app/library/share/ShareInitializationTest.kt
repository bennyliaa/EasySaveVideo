package com.easysavevideo.app.library.share
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class ShareInitializationTest {
    @Test fun unclassifiedConstructorExceptionBecomesBoundedInitializationFailure() {
        try{initializeShareOwner<Any>{throw IOException("PRIVATE_DETAIL")};fail()}
        catch(failure:ShareFailure){assertEquals(ShareError.INITIALIZATION_FAILED,failure.code);assertFalse(failure.toString().contains("PRIVATE_DETAIL"))}
    }
    @Test fun knownReleaseAndCancellationFencesArePreserved() {
        for(code in listOf(ShareError.RELEASE_UNCONFIRMED,ShareError.CANCELLED,ShareError.CLEANUP_PENDING)) {
            val original=ShareFailure(code)
            try{initializeShareOwner<Any>{throw original};fail()}catch(failure:ShareFailure){assertSame(original,failure)}
        }
    }
    @Test fun successReturnsOnlyTheOwnerAndDoesNotLaunchAChooser() {
        val value=Any();assertSame(value,initializeShareOwner{value})
    }
}

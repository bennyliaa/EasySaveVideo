package com.easysavevideo.app.discovery
import com.easysavevideo.core.model.DiscoveryMode
import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
class DiscoveryOsPowerPresentationTest {
    @Test fun releaseTextDescribesOsObservationAndNoEnergyMeasurement() {
        val text=DiscoveryPowerPresentationPolicy.lines(DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(enabled=true),DiscoveryMode.BALANCED),false,false,DiscoveryOsPowerSnapshot(state=OsObservationState.OBSERVING)).joinToString()
        assertTrue(text.contains("已接入 Android"));assertTrue(text.contains("未测量实际耗电"));assertTrue(text.contains("未知或已到期"));assertTrue(text.contains("系统观察未知"))
        assertFalse(text.contains("当前版本未接入"));assertFalse(text.contains("注入信号"))
    }
    @Test fun stoppedAndUnavailableNeverPromiseHealthyOrSavings() {
        for(state in listOf(OsObservationState.STOPPED,OsObservationState.CLOSED,OsObservationState.UNAVAILABLE)){
            val text=DiscoveryPowerPresentationPolicy.lines(null,false,false,DiscoveryOsPowerSnapshot(state=state)).joinToString()
            assertTrue(text.contains("暂停"));assertTrue(text.contains("未测量"))
        }
    }
    @Test fun freshTextExplainsExpiryAndNoAutomaticDemand() {
        val s=DiscoveryOsPowerSnapshot(state=OsObservationState.OBSERVING,battery=OsSignalAge.FRESH,saver=OsSignalAge.FRESH,thermal=OsSignalAge.FRESH,network=OsSignalAge.FRESH)
        val text=DiscoveryPowerPresentationPolicy.lines(null,false,false,s).joinToString()
        assertTrue(text.contains("五分钟"));assertTrue(text.contains("不会自动补充"))
    }
}

package com.easysavevideo.app.validation
import android.app.Activity
import android.os.Bundle
import com.easysavevideo.discovery.DiscoveryCancellation
/** Empty test-only lifecycle surface; never opens MainActivity, media or user preferences. */
class OwnedFixtureActivity:Activity() {
    val cancellation=DiscoveryCancellation()
    override fun onCreate(state:Bundle?) {super.onCreate(state);setContentView(android.widget.FrameLayout(this))}
    override fun onDestroy() {cancellation.cancel();super.onDestroy()}
}

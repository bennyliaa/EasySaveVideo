package com.easysavevideo.app.library.destination

import java.util.concurrent.atomic.AtomicReference

/** No URI, defaults, saved state or executable policy; one explicit foreground confirmation. */
internal class DirectoryPickerLifecycle {
    private enum class Phase{WAITING,RECEIVED,CONFIRMED,CANCELLED}
    private val phase=AtomicReference(Phase.WAITING)
    @Volatile var confirmedOnce=false;private set
    fun expectedPickerPause()=phase.get()==Phase.WAITING
    fun active()=phase.get()!=Phase.CANCELLED
    fun receive()=phase.compareAndSet(Phase.WAITING,Phase.RECEIVED)
    fun canConfirm(current:Boolean)=current && phase.get()==Phase.RECEIVED
    fun confirm(current:Boolean):Boolean {
        if(!current)return false
        if(!phase.compareAndSet(Phase.RECEIVED,Phase.CONFIRMED))return false
        confirmedOnce=true;return true
    }
    fun cancel(){phase.set(Phase.CANCELLED)}
}

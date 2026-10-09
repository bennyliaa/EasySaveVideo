package androidx.lifecycle
class Lifecycle {
    enum class State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED; fun isAtLeast(value:State)=ordinal>=value.ordinal }
    var currentState=State.RESUMED
}

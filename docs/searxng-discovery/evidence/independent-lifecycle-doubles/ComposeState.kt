package androidx.compose.runtime
import kotlin.reflect.KProperty
/** Plain host state for deterministic controller tests; does not model Compose snapshots/UI. */
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
operator fun <T> MutableState<T>.getValue(thisRef: Any?, property: KProperty<*>) = value
operator fun <T> MutableState<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }

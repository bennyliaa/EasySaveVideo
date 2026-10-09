package android.content.pm
class PackageManager {
    fun resolveContentProvider(authority: String, flags: Int): ProviderInfo? = null
    companion object { const val PERMISSION_GRANTED = 0 }
}
class ProviderInfo { fun loadLabel(manager: PackageManager): CharSequence = "Synthetic provider" }

package android.content
import java.io.File
/** Original test double, not Android implementation. Only the two used properties exist. */
open class Context(open val noBackupFilesDir: File) { open val applicationContext: Context get() = this }

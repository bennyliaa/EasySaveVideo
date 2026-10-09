package android.system
object Os { data class Stat(val st_mode: Int); fun fstat(fd:java.io.FileDescriptor) = Stat(1) }
object OsConstants { fun S_ISREG(mode:Int) = true }

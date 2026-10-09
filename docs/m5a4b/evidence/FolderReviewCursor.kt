package android.database
class Cursor(private val rows: List<List<Any?>>) : AutoCloseable {
    private var at = -1
    fun moveToFirst(): Boolean { at = 0; return rows.isNotEmpty() }
    fun moveToNext(): Boolean { at++; return at < rows.size }
    fun getString(index: Int) = rows[at][index] as String
    fun getLong(index: Int) = (rows[at][index] as Number).toLong()
    fun getInt(index: Int) = getLong(index).toInt()
    fun isNull(index: Int) = rows[at][index] == null
    override fun close() = Unit
}

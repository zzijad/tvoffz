package rs.zzijad.tvoffz

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kratak dnevnik događaja servisa (poslednjih 80), za dijagnostiku na samom boxu. */
object EventLog {
    private const val MAX = 80
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast(fmt.format(Date()) + "  " + msg)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(prazno)" else lines.reversed().joinToString("\n")
}

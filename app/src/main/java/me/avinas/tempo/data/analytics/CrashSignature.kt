package me.avinas.tempo.data.analytics

/**
 * Reduces a [Throwable] to a crash signature with no readable content.
 *
 * The exception message is never read: messages routinely embed the
 * notification text Tempo parsed, which is exactly the listening data we promise not to
 * collect. Only the obfuscated class name and a single frame survive, and both are stripped
 * of anything outside a strict character set so no free text can pass through.
 *
 * Restricting the output here — rather than trusting callers — is what makes it structurally
 * impossible to leak content via a crash report.
 */
internal object CrashSignature {

    private const val UNKNOWN_METHOD = "unknown"
    private const val DEFAULT_SOURCE = "SourceFile"
    private const val MAX_METHOD_CHARS = 64
    private const val MAX_SOURCE_CHARS = 32
    private const val MAX_LINE = 9_999_999

    private val UNSAFE_CLASS = Regex("[^A-Za-z0-9_.$]")
    private val UNSAFE_METHOD = Regex("[^A-Za-z0-9_.$<>]")
    private val UNSAFE_SOURCE = Regex("[^A-Za-z0-9_.$]")

    /** Returns the obfuscated class name and the top frame, e.g. `a.b.c` / `d(SourceFile:412)`. */
    fun of(throwable: Throwable): Pair<String, String> {
        val className = throwable.javaClass.name
            .replace(UNSAFE_CLASS, "")
            .take(Crash.MAX_CLASS_CHARS)
            .ifBlank { UNKNOWN_METHOD }

        val frame = throwable.stackTrace.firstOrNull()
        val frameText = if (frame == null) {
            "$UNKNOWN_METHOD($DEFAULT_SOURCE:0)"
        } else {
            val method = frame.methodName
                .replace(UNSAFE_METHOD, "")
                .take(MAX_METHOD_CHARS)
                .ifBlank { UNKNOWN_METHOD }
            val source = (frame.fileName ?: DEFAULT_SOURCE)
                .replace(UNSAFE_SOURCE, "")
                .take(MAX_SOURCE_CHARS)
                .ifBlank { DEFAULT_SOURCE }
            // Unknown line numbers are reported as negative; clamp so the value stays numeric.
            val line = frame.lineNumber.coerceIn(0, MAX_LINE)
            "$method($source:$line)"
        }

        return className to frameText
    }
}

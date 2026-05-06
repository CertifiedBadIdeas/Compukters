package ru.lazyhat.compukterkraft.core.device.vm

internal data class StdioDescriptor(
    val stdin: Int,
    val stdout: Int,
    val stderr: Int,
    val argument: String,
) {
    fun encode(): String = "$TAG $stdin $stdout $stderr $argument"

    companion object {
        private const val TAG = "stdio-v1"

        fun decode(raw: String): StdioDescriptor? {
            if (!raw.startsWith("$TAG ")) return null
            val rest = raw.removePrefix("$TAG ")
            val stdinText = rest.substringBefore(' ', missingDelimiterValue = "")
            val restAfterStdin = rest.substringAfter(' ', missingDelimiterValue = "")
            val stdoutText = restAfterStdin.substringBefore(' ', missingDelimiterValue = "")
            val restAfterStdout = restAfterStdin.substringAfter(' ', missingDelimiterValue = "")
            val stderrText = restAfterStdout.substringBefore(' ', missingDelimiterValue = "")
            val argument = restAfterStdout.substringAfter(' ', missingDelimiterValue = "")
            val stdin = stdinText.toIntOrNull() ?: return null
            val stdout = stdoutText.toIntOrNull() ?: return null
            val stderr = stderrText.toIntOrNull() ?: return null
            return StdioDescriptor(stdin = stdin, stdout = stdout, stderr = stderr, argument = argument)
        }
    }
}

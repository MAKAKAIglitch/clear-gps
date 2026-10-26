package org.cleargps

/**
 * Local Wi-Fi SSID exclusion policy shared by radio recording and positioning.
 *
 * Patterns are comma/semicolon/newline separated glob-like strings. '*' matches zero or more
 * characters and '?' matches exactly one character. Matching is case-insensitive. Empty SSIDs
 * are intentionally kept because the app cannot infer a hidden SSID from a BSSID.
 */
internal object RadioSsidFilter {
    const val DEFAULT_PATTERNS = "70mai*"
    const val MAX_CONFIG_LENGTH = 512
    const val MAX_PATTERN_COUNT = 32
    const val MAX_PATTERN_LENGTH = 64

    fun compilePatterns(configured: String): List<Regex> =
        parsePatterns(configured).mapNotNull(::globRegex)

    fun parsePatterns(configured: String): List<String> =
        configured.take(MAX_CONFIG_LENGTH)
            .split(',', ';', '\n', '\r')
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAX_PATTERN_LENGTH }
            .take(MAX_PATTERN_COUNT)
            .toList()

    fun excludes(type: String, ssid: String, configuredPatterns: String): Boolean =
        excludes(type, ssid, compilePatterns(configuredPatterns))

    fun excludes(type: String, ssid: String, compiledPatterns: List<Regex>): Boolean {
        if (!type.equals("wifi", ignoreCase = true) || ssid.isBlank() || compiledPatterns.isEmpty()) {
            return false
        }
        val candidate = ssid.trim()
        return compiledPatterns.any { it.matches(candidate) }
    }

    private fun globRegex(pattern: String): Regex? {
        if (pattern.isBlank() || pattern.length > MAX_PATTERN_LENGTH) return null
        val expression = buildString {
            append('^')
            pattern.forEach { char ->
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> {
                        append('\\')
                        append(char)
                    }
                    else -> append(char)
                }
            }
            append('$')
        }
        return runCatching { Regex(expression, RegexOption.IGNORE_CASE) }.getOrNull()
    }
}

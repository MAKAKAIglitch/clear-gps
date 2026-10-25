package org.cleargps

/**
 * Detect a mock location supplied by a monitored Android GPS/network provider while Clear GPS's
 * own mock output is disabled. A mock is not an alert when our output is enabled because that
 * state can create expected mock callbacks.
 */
internal fun shouldFlagExternalMockLocation(
    provider: String?,
    isMockLocation: Boolean,
    ownMockEnabled: Boolean
): Boolean {
    if (!isMockLocation || ownMockEnabled || provider.isNullOrBlank()) return false
    return provider.equals("gps", ignoreCase = true) ||
        provider.equals("network", ignoreCase = true)
}

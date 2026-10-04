package dev.veneranative.feature.library

import dev.veneranative.data.settings.ScreenPreferenceRepository

internal class FakeScreenPreferences(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : ScreenPreferenceRepository {
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}

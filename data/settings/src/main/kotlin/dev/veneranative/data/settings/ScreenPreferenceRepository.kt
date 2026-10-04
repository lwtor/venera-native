package dev.veneranative.data.settings

import dev.veneranative.core.database.ScreenPreferenceDao
import dev.veneranative.core.database.ScreenPreferenceEntity

/** Persistence seam for small screen selections, separate from the feature's rendered UI state. */
interface ScreenPreferenceRepository {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class DefaultScreenPreferenceRepository(
    private val dao: ScreenPreferenceDao,
) : ScreenPreferenceRepository {
    override suspend fun get(key: String): String? = dao.get(key)

    override suspend fun put(key: String, value: String) {
        dao.put(ScreenPreferenceEntity(key, value))
    }

    override suspend fun remove(key: String) {
        dao.remove(key)
    }
}

package dev.veneranative.data.backup

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Public profile and this device's successful sync receipt; credentials remain in Keystore storage. */
class AndroidGitHubAccountStore(directory: File) : GitHubAccountStore {
    private val file = AtomicFile(File(directory, "github-account.json"))

    @Synchronized
    override fun read(): GitHubAccountState = try {
        val json = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val profile = GitHubProfile(
            json.getLong("id"), json.getString("login"),
            json.optString("name").takeIf { it.isNotBlank() && it != "null" },
            json.optString("avatarUrl").takeIf { it.startsWith("https://") },
        )
        val time = json.optLong("syncTime")
        val operation = runCatching { GitHubSyncOperation.valueOf(json.optString("syncOperation")) }.getOrNull()
        GitHubAccountState(profile = profile, lastSync = if (time > 0 && operation != null) GitHubSyncRecord(time, operation) else null)
    } catch (_: java.io.FileNotFoundException) {
        GitHubAccountState()
    } catch (_: org.json.JSONException) {
        GitHubAccountState()
    }

    @Synchronized
    override fun write(state: GitHubAccountState) {
        val profile = state.profile ?: return clear()
        val json = JSONObject().put("id", profile.id).put("login", profile.login)
            .put("name", profile.name).put("avatarUrl", profile.avatarUrl)
        state.lastSync?.let { json.put("syncTime", it.completedAtEpochMillis).put("syncOperation", it.operation.name) }
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    @Synchronized
    override fun clear() = file.delete()
}

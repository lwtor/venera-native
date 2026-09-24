package dev.veneranative.data.download

import dev.veneranative.core.database.DownloadDao
import dev.veneranative.core.database.DownloadPageEntity
import dev.veneranative.core.database.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-in for the download DAO, so queue and recovery logic runs on the JVM.
 *
 * It reproduces what the real SQL does — the ordering in `queuedPages` and the state filters in the
 * pause/resume/claim statements included — because those are the behaviour under test. What it
 * cannot reproduce (foreign keys, real concurrency) is checked by the instrumented DAO tests.
 */
internal class FakeDownloadDao : DownloadDao {

    val tasks = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
    val pages = MutableStateFlow<List<DownloadPageEntity>>(emptyList())
    var beforeUpdatePage: (() -> Unit)? = null

    override fun observeTasks(): Flow<List<DownloadTaskEntity>> =
        tasks.map { rows -> rows.sortedBy { it.createdAt } }

    override fun observeTask(taskId: String): Flow<DownloadTaskEntity?> =
        tasks.map { rows -> rows.firstOrNull { it.taskId == taskId } }

    override suspend fun task(taskId: String): DownloadTaskEntity? =
        tasks.value.firstOrNull { it.taskId == taskId }

    override suspend fun tasks(): List<DownloadTaskEntity> = tasks.value.sortedBy { it.createdAt }

    override suspend fun pages(taskId: String): List<DownloadPageEntity> =
        pages.value.filter { it.taskId == taskId }.sortedBy { it.pageIndex }

    override fun observePages(taskId: String): Flow<List<DownloadPageEntity>> =
        pages.map { rows -> rows.filter { it.taskId == taskId }.sortedBy { it.pageIndex } }

    override suspend fun queuedPages(limit: Int): List<DownloadPageEntity> {
        val order = tasks.value.sortedBy { it.createdAt }.map { it.taskId }
            .withIndex().associate { it.value to it.index }
        return pages.value
            .filter { it.state == DownloadPageState.Queued.name }
            .sortedWith(compareBy({ order[it.taskId] ?: Int.MAX_VALUE }, { it.pageIndex }))
            .take(limit)
    }

    override suspend fun succeededPages(): List<DownloadPageEntity> =
        pages.value.filter { it.state == DownloadPageState.Succeeded.name }

    override suspend fun insertTask(task: DownloadTaskEntity) {
        check(tasks.value.none { it.taskId == task.taskId }) { "task ${task.taskId} already exists" }
        tasks.value = tasks.value + task
    }

    override suspend fun upsertTask(task: DownloadTaskEntity) {
        tasks.value = tasks.value.filterNot { it.taskId == task.taskId } + task
    }

    override suspend fun upsertPages(pages: List<DownloadPageEntity>) {
        val incoming = pages.map { it.taskId to it.pageIndex }.toSet()
        this.pages.value = this.pages.value.filterNot { it.taskId to it.pageIndex in incoming } + pages
    }

    override suspend fun page(taskId: String, pageIndex: Int): DownloadPageEntity? =
        pages.value.firstOrNull { it.taskId == taskId && it.pageIndex == pageIndex }

    override suspend fun updatePage(
        taskId: String,
        pageIndex: Int,
        expectedState: String,
        state: String,
        relativePath: String?,
        bytes: Long,
        attempts: Int,
        lastError: String?,
    ): Int {
        beforeUpdatePage?.invoke()
        if (page(taskId, pageIndex)?.state != expectedState) return 0
        replace(taskId, pageIndex) {
            it.copy(
                state = state,
                relativePath = relativePath,
                bytes = bytes,
                attempts = attempts,
                lastError = lastError,
            )
        }
        return 1
    }

    override suspend fun pauseQueuedPages(taskId: String) {
        retag(taskId, from = DownloadPageState.Queued, to = DownloadPageState.Paused) { it }
    }

    override suspend fun requeuePausedPages(taskId: String) {
        retag(taskId, from = DownloadPageState.Paused, to = DownloadPageState.Queued) { it }
    }

    override suspend fun requeueFailedPages(taskId: String) {
        retag(taskId, from = DownloadPageState.Failed, to = DownloadPageState.Queued) {
            it.copy(attempts = 0, lastError = null)
        }
    }

    override suspend fun requeuePage(taskId: String, pageIndex: Int, reason: String) {
        replace(taskId, pageIndex) {
            it.copy(state = DownloadPageState.Queued.name, relativePath = null, bytes = 0L, lastError = reason)
        }
    }

    override suspend fun updateTaskProgress(taskId: String, state: String, completedPages: Int, updatedAt: Long) {
        tasks.value = tasks.value.map { row ->
            if (row.taskId == taskId) {
                row.copy(state = state, completedPages = completedPages, updatedAt = updatedAt)
            } else {
                row
            }
        }
    }

    override suspend fun claimTasks(workerId: String, now: Long, staleBefore: Long) {
        tasks.value = tasks.value.map { row ->
            if (row.workerId == null || row.workerId == workerId || row.heartbeatAt < staleBefore) {
                row.copy(workerId = workerId, heartbeatAt = now)
            } else {
                row
            }
        }
    }

    override suspend fun heartbeat(workerId: String, now: Long) {
        tasks.value = tasks.value.map { row ->
            if (row.workerId == workerId) row.copy(heartbeatAt = now) else row
        }
    }

    override suspend fun tasksOf(workerId: String): List<DownloadTaskEntity> =
        tasks.value.filter { it.workerId == workerId }

    override suspend fun countZombiePages(workerId: String, staleBefore: Long): Int =
        zombiePages(workerId, staleBefore).size

    override suspend fun resetZombiePages(workerId: String, staleBefore: Long) {
        val doomed = zombiePages(workerId, staleBefore).map { it.taskId to it.pageIndex }.toSet()
        pages.value = pages.value.map { row ->
            if (row.taskId to row.pageIndex in doomed) {
                row.copy(state = DownloadPageState.Queued.name)
            } else {
                row
            }
        }
    }

    override suspend fun deleteTask(taskId: String) {
        tasks.value = tasks.value.filterNot { it.taskId == taskId }
        pages.value = pages.value.filterNot { it.taskId == taskId }
    }

    private fun zombiePages(workerId: String, staleBefore: Long): List<DownloadPageEntity> {
        val dead = tasks.value
            .filter { it.workerId == workerId || (it.workerId != null && it.heartbeatAt < staleBefore) }
            .map { it.taskId }
            .toSet()
        return pages.value.filter { it.state == DownloadPageState.Running.name && it.taskId in dead }
    }

    private fun replace(taskId: String, pageIndex: Int, change: (DownloadPageEntity) -> DownloadPageEntity) {
        pages.value = pages.value.map { row ->
            if (row.taskId == taskId && row.pageIndex == pageIndex) change(row) else row
        }
    }

    private fun retag(
        taskId: String,
        from: DownloadPageState,
        to: DownloadPageState,
        change: (DownloadPageEntity) -> DownloadPageEntity,
    ) {
        pages.value = pages.value.map { row ->
            if (row.taskId == taskId && row.state == from.name) change(row).copy(state = to.name) else row
        }
    }
}

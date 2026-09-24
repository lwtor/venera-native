package dev.veneranative.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The download queue as rows.
 *
 * States are stored as `DownloadPageState.name` / `DownloadChapterState.name`, so the literals below
 * are part of that contract: renaming an enum value in `:data:download` changes what these queries
 * match, which is why the names are asserted in a test there.
 *
 * Ordered reads matter: the queue takes pages in task creation order and, inside a task, by page
 * index, so a chapter becomes readable from its first page rather than from a random one.
 */
@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_task ORDER BY created_at ASC")
    fun observeTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE task_id = :taskId")
    fun observeTask(taskId: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_task WHERE task_id = :taskId")
    suspend fun task(taskId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_task ORDER BY created_at ASC")
    suspend fun tasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_page WHERE task_id = :taskId ORDER BY page_index ASC")
    suspend fun pages(taskId: String): List<DownloadPageEntity>

    @Query("SELECT * FROM download_page WHERE task_id = :taskId ORDER BY page_index ASC")
    fun observePages(taskId: String): Flow<List<DownloadPageEntity>>

    /** Every page still waiting, oldest task first and lowest page index first. */
    @Query(
        """SELECT download_page.* FROM download_page
           JOIN download_task ON download_task.task_id = download_page.task_id
           WHERE download_page.state = 'Queued'
           ORDER BY download_task.created_at ASC, download_page.page_index ASC
           LIMIT :limit""",
    )
    suspend fun queuedPages(limit: Int): List<DownloadPageEntity>

    @Query("SELECT * FROM download_page WHERE state = 'Succeeded'")
    suspend fun succeededPages(): List<DownloadPageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Upsert
    suspend fun upsertTask(task: DownloadTaskEntity)

    @Upsert
    suspend fun upsertPages(pages: List<DownloadPageEntity>)

    @Query("SELECT * FROM download_page WHERE task_id = :taskId AND page_index = :pageIndex")
    suspend fun page(taskId: String, pageIndex: Int): DownloadPageEntity?

    @Query(
        """UPDATE download_page SET state = :state, relative_path = :relativePath, bytes = :bytes,
           attempts = :attempts, last_error = :lastError
           WHERE task_id = :taskId AND page_index = :pageIndex AND state = :expectedState""",
    )
    suspend fun updatePage(
        taskId: String,
        pageIndex: Int,
        expectedState: String,
        state: String,
        relativePath: String?,
        bytes: Long,
        attempts: Int,
        lastError: String?,
    ): Int

    /** Pausing stops future work; a page already in flight is allowed to finish. */
    @Query("UPDATE download_page SET state = 'Paused' WHERE task_id = :taskId AND state = 'Queued'")
    suspend fun pauseQueuedPages(taskId: String)

    @Query("UPDATE download_page SET state = 'Queued' WHERE task_id = :taskId AND state = 'Paused'")
    suspend fun requeuePausedPages(taskId: String)

    @Query(
        """UPDATE download_page SET state = 'Queued', attempts = 0, last_error = NULL
           WHERE task_id = :taskId AND state = 'Failed'""",
    )
    suspend fun requeueFailedPages(taskId: String)

    @Query(
        """UPDATE download_page SET state = 'Queued', relative_path = NULL, bytes = 0,
           last_error = :reason
           WHERE task_id = :taskId AND page_index = :pageIndex""",
    )
    suspend fun requeuePage(taskId: String, pageIndex: Int, reason: String)

    @Query(
        """UPDATE download_task SET state = :state, completed_pages = :completedPages,
           updated_at = :updatedAt WHERE task_id = :taskId""",
    )
    suspend fun updateTaskProgress(taskId: String, state: String, completedPages: Int, updatedAt: Long)

    /** Takes ownership of unclaimed, own, or stale tasks after old running pages are requeued. */
    @Query(
        """UPDATE download_task SET worker_id = :workerId, heartbeat_at = :now
           WHERE worker_id IS NULL OR worker_id = :workerId OR heartbeat_at < :staleBefore""",
    )
    suspend fun claimTasks(workerId: String, now: Long, staleBefore: Long)

    @Query("UPDATE download_task SET heartbeat_at = :now WHERE worker_id = :workerId")
    suspend fun heartbeat(workerId: String, now: Long)

    @Query("SELECT * FROM download_task WHERE worker_id = :workerId")
    suspend fun tasksOf(workerId: String): List<DownloadTaskEntity>

    /** Pages left by this WorkSpec's previous attempt or another worker with a stale heartbeat. */
    @Query(
        """SELECT COUNT(*) FROM download_page
           WHERE state = 'Running'
           AND task_id IN (SELECT task_id FROM download_task
                           WHERE worker_id = :workerId OR
                           (worker_id IS NOT NULL AND heartbeat_at < :staleBefore))""",
    )
    suspend fun countZombiePages(workerId: String, staleBefore: Long): Int

    /**
     * Sends pages back to the queue when the worker that owned them stopped reporting.
     *
     * Recovery runs once at the start of a WorkManager attempt, so any page still marked Running
     * under the same WorkSpec ID belongs to its previous attempt, even with a fresh heartbeat.
     */
    @Query(
        """UPDATE download_page SET state = 'Queued'
           WHERE state = 'Running'
           AND task_id IN (SELECT task_id FROM download_task
                           WHERE worker_id = :workerId OR
                           (worker_id IS NOT NULL AND heartbeat_at < :staleBefore))""",
    )
    suspend fun resetZombiePages(workerId: String, staleBefore: Long)

    /** Removes the task; its pages go with it through the foreign key. */
    @Query("DELETE FROM download_task WHERE task_id = :taskId")
    suspend fun deleteTask(taskId: String)
}

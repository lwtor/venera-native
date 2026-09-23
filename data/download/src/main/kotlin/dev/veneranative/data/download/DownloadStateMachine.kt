package dev.veneranative.data.download

/**
 * Which page states may follow which, as a pure function so the rules can be tested on the JVM and
 * so no caller has to remember them.
 *
 * The rules exist because the states are not labels but promises. `Succeeded` means "the bytes are
 * on disk and are an image", so letting it fall back to `Queued` would re-download a page that is
 * already there; `Canceled` means "the user is done with this", so letting it come back would
 * restart work that was explicitly stopped.
 */
object DownloadStateMachine {

    /** After this many attempts a page waits for the user instead of silently retrying forever. */
    const val MAX_ATTEMPTS: Int = 3

    private val allowed: Map<DownloadPageState, Set<DownloadPageState>> = mapOf(
        DownloadPageState.Queued to setOf(DownloadPageState.Running, DownloadPageState.Canceled),
        DownloadPageState.Running to setOf(
            DownloadPageState.Succeeded,
            DownloadPageState.Failed,
            DownloadPageState.Paused,
            DownloadPageState.Canceled,
        ),
        DownloadPageState.Failed to setOf(DownloadPageState.Queued, DownloadPageState.Canceled),
        DownloadPageState.Paused to setOf(DownloadPageState.Queued, DownloadPageState.Canceled),
        DownloadPageState.Succeeded to emptySet(),
        DownloadPageState.Canceled to emptySet(),
    )

    fun canMove(from: DownloadPageState, to: DownloadPageState): Boolean =
        to in allowed.getValue(from)

    /**
     * The state a stored name means, or null when the row holds a name this version never had.
     *
     * Null rather than a fallback state: guessing `Queued` for an unknown name would re-download a
     * page whose real state the app can no longer read, which is worse than leaving it alone.
     */
    fun stateNamed(name: String): DownloadPageState? =
        DownloadPageState.entries.firstOrNull { it.name == name }

    /**
     * The state to record, or nothing: an illegal move is refused rather than quietly applied,
     * because a queue that accepts impossible transitions is a queue whose rows stop meaning anything.
     */
    fun move(from: DownloadPageState, to: DownloadPageState): DownloadPageState {
        check(canMove(from, to)) { "a page cannot go from $from to $to" }
        return to
    }

    /**
     * The one way back from `Succeeded`.
     *
     * Recovery found the file gone or truncated — the promise turned out to be false — so the page
     * has to be fetched again. Every other path into `Queued` goes through [move].
     */
    fun requeueAfterVerification(): DownloadPageState = DownloadPageState.Queued

    fun shouldRetry(attempts: Int): Boolean = attempts < MAX_ATTEMPTS

    /** A chapter's state is whatever its pages add up to, so it can never disagree with them. */
    fun chapterStateOf(pageStates: List<DownloadPageState>): DownloadChapterState {
        if (pageStates.isEmpty()) return DownloadChapterState.Queued
        val unfinished = pageStates.filter { it != DownloadPageState.Succeeded }
        if (unfinished.isEmpty()) return DownloadChapterState.Completed
        if (pageStates.all { it == DownloadPageState.Canceled }) return DownloadChapterState.Canceled
        if (unfinished.any { it == DownloadPageState.Paused }) return DownloadChapterState.Paused
        if (unfinished.any { it == DownloadPageState.Running }) return DownloadChapterState.Running
        // A failure outranks a page that is still waiting: "some pages are missing" is the thing the
        // user has to be told, while "some pages are still to come" is true of every download.
        if (unfinished.any { it == DownloadPageState.Failed }) return DownloadChapterState.Partial
        if (unfinished.any { it == DownloadPageState.Queued }) return DownloadChapterState.Queued
        return DownloadChapterState.Canceled
    }
}

package dev.veneranative.source.engine

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine

private val directExecutor = Executor { command -> command.run() }

internal suspend fun <T> ListenableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        addListener(
            {
                if (completed.compareAndSet(false, true)) {
                    val result =
                        runCatching { get() }.fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { throwable ->
                                val cause =
                                    if (throwable is ExecutionException) {
                                        throwable.cause ?: throwable
                                    } else {
                                        throwable
                                    }
                                Result.failure(cause)
                            },
                        )
                    continuation.resumeWith(result)
                }
            },
            directExecutor,
        )
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) cancel(true)
        }
    }

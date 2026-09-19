package dev.veneranative.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    data class Network(val cause: Throwable) : AppError
    data class Source(val sourceId: String, val message: String) : AppError
    data class Unexpected(val cause: Throwable) : AppError
}

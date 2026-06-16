package com.flexsentlabs.koncerto.core.result

sealed class Result<out T, out E : Throwable> {
    data class Success<T>(val value: T) : Result<T, Nothing>()
    data class Failure<E : Throwable>(val error: E) : Result<Nothing, E>()

    inline fun <R> map(transform: (T) -> R): Result<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): Result<T, E> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (E) -> Unit): Result<T, E> {
        if (this is Failure) block(error)
        return this
    }

    inline fun getOrNull(): T? = (this as? Success)?.value

    inline fun exceptionOrNull(): E? = (this as? Failure)?.error
}

typealias EmptyResult<E> = Result<Unit, E>

inline fun <T, E : Throwable> runCatchingResult(block: () -> T): Result<T, E> = try {
    Result.Success(block())
} catch (e: Throwable) {
    @Suppress("UNCHECKED_CAST")
    Result.Failure(e as E)
}

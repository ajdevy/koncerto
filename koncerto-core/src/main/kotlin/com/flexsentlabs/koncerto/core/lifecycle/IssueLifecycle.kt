package com.flexsentlabs.koncerto.core.lifecycle

sealed interface IssueLifecycle {
    data object Todo : IssueLifecycle
    data object InProgress : IssueLifecycle
    data object InReview : IssueLifecycle
    data object Done : IssueLifecycle

    val normalizedName: String
        get() = when (this) {
            Todo -> "todo"
            InProgress -> "in progress"
            InReview -> "in review"
            Done -> "done"
        }

    companion object {
        private val transitions: Set<AllowedTransition> = setOf(
            AllowedTransition(Todo, InProgress),
            AllowedTransition(Todo, InReview),
            AllowedTransition(InProgress, InReview),
            AllowedTransition(InProgress, Todo),
            AllowedTransition(InReview, Done),
            AllowedTransition(InReview, Todo),
        )

        fun validate(from: IssueLifecycle, to: IssueLifecycle): Boolean =
            transitions.any { it.from == from && it.to == to }

        fun fromNormalized(name: String): IssueLifecycle? = when (name.lowercase()) {
            "todo" -> Todo
            "in progress" -> InProgress
            "in review" -> InReview
            "done" -> Done
            else -> null
        }
    }

    private data class AllowedTransition(
        val from: IssueLifecycle,
        val to: IssueLifecycle
    )
}

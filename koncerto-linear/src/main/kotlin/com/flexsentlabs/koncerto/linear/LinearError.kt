package com.flexsentlabs.koncerto.linear

sealed class LinearError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingApiKey : LinearError("missing_tracker_api_key")
    class MissingProjectSlug : LinearError("missing_tracker_project_slug")
    class Request(message: String, cause: Throwable? = null) : LinearError("linear_api_request: $message", cause)
    class Status(code: Int) : LinearError("linear_api_status: $code")
    class GraphQlErrors(message: String) : LinearError("linear_graphql_errors: $message")
    class UnknownPayload : LinearError("linear_unknown_payload")
    class MissingEndCursor : LinearError("linear_missing_end_cursor")
    class RateLimited(message: String) : LinearError("linear_rate_limited: $message")
    class CircuitOpen : LinearError("linear_circuit_open")
}
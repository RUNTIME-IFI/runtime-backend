package io.github.runtimeifi.routes

import io.github.runtimeifi.models.StravaErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    detail: String,
    error: String = status.description,
) {
    respond(status, StravaErrorResponse(error = error, detail = detail))
}

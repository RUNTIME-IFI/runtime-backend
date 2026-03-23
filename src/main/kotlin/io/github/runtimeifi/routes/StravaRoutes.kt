package io.github.runtimeifi.routes

import io.github.runtimeifi.config.AppConfig
import io.github.runtimeifi.models.ApiEnvelope
import io.github.runtimeifi.models.LeaderboardResponse
import io.github.runtimeifi.services.StravaService
import io.ktor.http.Cookie
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLQueryComponent
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun Route.stravaRoutes(
    service: StravaService,
    config: AppConfig,
) {
    registerStravaRoutes("/strava", service, config)
    registerStravaRoutes("/api/strava", service, config)
}

private fun Route.registerStravaRoutes(
    basePath: String,
    service: StravaService,
    config: AppConfig,
) {
    route(basePath) {
        get("/authorize") {
            val page = call.request.queryParameters["page"] ?: "leaderboard"
            call.respondRedirect(service.buildAuthorizationUrl(page))
        }

        get("/callback") {
            val error = call.request.queryParameters["error"]
            if (!error.isNullOrBlank()) {
                call.respondRedirect(frontendRedirect(config.frontendBaseUrl, "leaderboard", false, error))
                return@get
            }

            val code = call.request.queryParameters["code"]
            if (code.isNullOrBlank()) {
                call.respondRedirect(frontendRedirect(config.frontendBaseUrl, "leaderboard", false, "Missing authorization code"))
                return@get
            }

            runCatching {
                service.completeSignup(code, call.request.queryParameters["state"])
            }.onSuccess { (athlete, page) ->
                call.response.cookies.append(
                    Cookie(
                        name = config.cookieName,
                        value = signCookieValue(athlete.athleteId, config.cookieSigningSecret),
                        path = "/",
                        httpOnly = true,
                        secure = config.cookieSecure,
                        extensions = mapOf("SameSite" to if (config.cookieSecure) "None" else "Lax"),
                    )
                )
                call.respondRedirect(frontendRedirect(config.frontendBaseUrl, page, true, null))
            }.onFailure { throwable ->
                call.application.environment.log.error("Strava signup failed", throwable)
                call.respondRedirect(frontendRedirect(config.frontendBaseUrl, "leaderboard", false, "Failed to connect Strava"))
            }
        }

        route("/stats") {
            get("/ytd") {
                val athleteId = currentAthleteId(call.request.cookies[config.cookieName], config.cookieSigningSecret)
                if (athleteId == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Connect Strava first to view your stats.")
                    return@get
                }

                runCatching {
                    service.getYearToDateStats(athleteId)
                }.onSuccess { stats ->
                    call.respond(
                        ApiEnvelope(
                            type = "ytd",
                            data = mapOf(
                                "run_totals" to stats.ytd_run_totals,
                                "ride_totals" to stats.ytd_ride_totals,
                            ),
                            fetched_at = Instant.now().toString(),
                        )
                    )
                }.onFailure { throwable ->
                    call.respondError(HttpStatusCode.BadGateway, throwable.message ?: "Failed to load YTD stats")
                }
            }

            get("/activities") {
                val athleteId = currentAthleteId(call.request.cookies[config.cookieName], config.cookieSigningSecret)
                if (athleteId == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Connect Strava first to view your activities.")
                    return@get
                }

                runCatching {
                    service.getRecentActivities(athleteId)
                }.onSuccess { activities ->
                    call.respond(ApiEnvelope(type = "recent_activities", data = activities, fetched_at = Instant.now().toString()))
                }.onFailure { throwable ->
                    call.respondError(HttpStatusCode.BadGateway, throwable.message ?: "Failed to load activities")
                }
            }

            get("/monthly") {
                val athleteId = currentAthleteId(call.request.cookies[config.cookieName], config.cookieSigningSecret)
                if (athleteId == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "Connect Strava first to view your monthly stats.")
                    return@get
                }

                runCatching {
                    service.getMonthlySummary(athleteId)
                }.onSuccess { summary ->
                    call.respond(ApiEnvelope(type = "monthly", data = summary, fetched_at = Instant.now().toString()))
                }.onFailure { throwable ->
                    call.respondError(HttpStatusCode.BadGateway, throwable.message ?: "Failed to load monthly stats")
                }
            }

            get("/leaderboard") {
                val activity = call.request.queryParameters["activity"] ?: "run"
                val period = call.request.queryParameters["period"] ?: "ytd"
                val normalizedActivity = when (activity.lowercase()) {
                    "ride", "cycling", "bike" -> "ride"
                    "swim", "swimming" -> "swim"
                    else -> "run"
                }
                val normalizedPeriod = when (period.lowercase()) {
                    "7d", "7days", "week" -> "7d"
                    "30d", "30days", "month" -> "30d"
                    else -> "ytd"
                }
                runCatching {
                    service.getLeaderboard(normalizedActivity, normalizedPeriod)
                }.onSuccess { entries ->
                    call.respond(
                        ApiEnvelope(
                            type = "leaderboard",
                            data = LeaderboardResponse(
                                activity = normalizedActivity,
                                period = normalizedPeriod,
                                total_athletes = entries.size,
                                entries = entries,
                            ),
                            fetched_at = Instant.now().toString(),
                        )
                    )
                }.onFailure { throwable ->
                    call.respondError(HttpStatusCode.BadGateway, throwable.message ?: "Failed to load leaderboard")
                }
            }
        }

        get("/info") {
            val response = service.getClubInfo()
            if (response == null) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "Club info unavailable. Configure STRAVA_CLUB_ID and at least one signup.")
                return@get
            }
            call.respondText(response, ContentType.Application.Json)
        }

        get("/members") {
            val response = service.getClubMembers()
            if (response == null) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "Members unavailable. Configure STRAVA_CLUB_ID and at least one signup.")
                return@get
            }
            call.respondText(response, ContentType.Application.Json)
        }

        get("/admins") {
            val response = service.getClubAdmins()
            if (response == null) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "Admins unavailable. Configure STRAVA_CLUB_ID and at least one signup.")
                return@get
            }
            call.respondText(response, ContentType.Application.Json)
        }
    }
}

private fun frontendRedirect(frontendBaseUrl: String, page: String, success: Boolean, message: String?): String {
    val state = if (success) "success" else "error"
    val query = buildList {
        add("page=${page.encodeURLQueryComponent()}")
        add("strava=$state")
        if (!message.isNullOrBlank()) {
            add("message=${message.encodeURLQueryComponent()}")
        }
    }.joinToString("&")
    return "$frontendBaseUrl/?$query"
}

private fun currentAthleteId(cookieValue: String?, secret: String): Long? {
    val raw = cookieValue ?: return null
    val parts = raw.split('.')
    if (parts.size != 2) {
        return null
    }

    val athleteId = parts[0].toLongOrNull() ?: return null
    val signature = parts[1]
    return if (signature == signCookieValue(athleteId, secret).substringAfter('.')) athleteId else null
}

private fun signCookieValue(athleteId: Long, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val signature = mac.doFinal(athleteId.toString().toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$athleteId.$signature"
}

package io.github.runtimeifi.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant

fun Route.stravaRoutes() {
    val client = HttpClient()
    val clubId = 1766412

    val clientId = System.getenv("STRAVA_CLIENT_ID")?.toInt()
    val clientSecret = System.getenv("STRAVA_CLIENT_SECRET")
    var accessToken = System.getenv("STRAVA_ACCESS_TOKEN")
    val refreshToken = System.getenv("STRAVA_REFRESH_TOKEN")

    // Track when token expires (default 6 hours from start)
    var tokenExpiry: Instant = Instant.now().plusSeconds(6 * 3600)

    // Coroutine scope for background tasks
    val scope = CoroutineScope(Dispatchers.Default)

    // Refresh token function
    suspend fun refreshAccessToken() {
        val response: String = client.post("https://www.strava.com/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "client_id" to clientId.toString(),
                    "client_secret" to clientSecret,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken
                ).formUrlEncode()
            )
        }.bodyAsText()

        val json = Json.parseToJsonElement(response).jsonObject
        accessToken = json["access_token"]!!.jsonPrimitive.content
        val expiresIn = json["expires_in"]!!.jsonPrimitive.int
        tokenExpiry = Instant.now().plusSeconds(expiresIn.toLong())
        println("Strava access token refreshed, expires in $expiresIn seconds")
    }

    // Background refresh every 5 minutes before expiry
    scope.launch {
        while (true) {
            val now = Instant.now()
            val delayMs = if (tokenExpiry.minusSeconds(300).isAfter(now)) {
                tokenExpiry.minusSeconds(300).toEpochMilli() - now.toEpochMilli()
            } else {
                0
            }
            delay(delayMs.coerceAtLeast(0))
            try {
                refreshAccessToken()
            } catch (e: Exception) {
                println("Error refreshing Strava token: ${e.message}")
                delay(60000) // retry in 1 min if failed
            }
        }
    }

    // Helper to make authorized requests
    suspend fun getStrava(url: String): String {
        return client.get(url) {
            headers { append("Authorization", "Bearer $accessToken") }
        }.bodyAsText()
    }

    route("/api/strava") {

        get("/info") {
            val response = getStrava("https://www.strava.com/api/v3/clubs/$clubId")
            call.respondText(response, ContentType.Application.Json)
        }

        get("/leaderboard") {
            val response = getStrava("https://www.strava.com/api/v3/clubs/$clubId/activities")
            call.respondText(response, ContentType.Application.Json)
        }

        get("/members") {
            val response = getStrava("https://www.strava.com/api/v3/clubs/$clubId/members")
            call.respondText(response, ContentType.Application.Json)
        }

        get("/admins") {
            val response = getStrava("https://www.strava.com/api/v3/clubs/$clubId/admins")
            call.respondText(response, ContentType.Application.Json)
        }
    }
}

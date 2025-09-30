// StravaService.kt
package io.github.runtimeifi.service

import io.github.runtimeifi.config.StravaProperties
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StravaService(private val props: StravaProperties) {

    private val client = HttpClient()
    private var accessToken: String = props.accessToken
    private var tokenExpiry: Instant = Instant.now().plusSeconds(6 * 3600) // default 6h

    init {
        // Background coroutine to refresh token before it expires
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val now = Instant.now()
                val delayMs = if (tokenExpiry.minusSeconds(300).isAfter(now))
                    tokenExpiry.minusSeconds(300).toEpochMilli() - now.toEpochMilli()
                else 0
                delay(delayMs.coerceAtLeast(0))
                try {
                    refreshAccessToken()
                } catch (e: Exception) {
                    println("Error refreshing Strava token: ${e.message}")
                    delay(60000) // retry in 1 min
                }
            }
        }
    }

    private suspend fun refreshAccessToken() {
        val response: String = client.post("https://www.strava.com/api/v3/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "client_id" to props.clientId.toString(),
                    "client_secret" to props.clientSecret,
                    "grant_type" to "refresh_token",
                    "refresh_token" to props.refreshToken
                ).formUrlEncode()
            )
        }.bodyAsText()

        val json = Json.parseToJsonElement(response).jsonObject
        accessToken = json["access_token"]!!.jsonPrimitive.content
        val expiresIn = json["expires_in"]!!.jsonPrimitive.int
        tokenExpiry = Instant.now().plusSeconds(expiresIn.toLong())
        println("Strava access token refreshed, expires in $expiresIn seconds")
    }

    suspend fun get(url: String): String {
        return client.get(url) {
            headers { append("Authorization", "Bearer $accessToken") }
        }.bodyAsText()
    }
}

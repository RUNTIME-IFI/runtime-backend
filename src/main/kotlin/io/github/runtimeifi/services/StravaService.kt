package io.github.runtimeifi.services

import io.github.runtimeifi.config.AppConfig
import io.github.runtimeifi.models.ActivityTotals
import io.github.runtimeifi.models.LeaderboardEntry
import io.github.runtimeifi.models.LeaderboardMetrics
import io.github.runtimeifi.models.MonthlyAggregate
import io.github.runtimeifi.models.MonthlySummary
import io.github.runtimeifi.models.MonthlySummaryTotals
import io.github.runtimeifi.models.OAuthStatePayload
import io.github.runtimeifi.models.RecentActivitiesResponse
import io.github.runtimeifi.models.SignedUpAthlete
import io.github.runtimeifi.models.StravaActivity
import io.github.runtimeifi.models.StravaAthlete
import io.github.runtimeifi.models.StravaAthleteStats
import io.github.runtimeifi.models.StravaTokenResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Parameters

class StravaService(
    private val config: AppConfig,
    private val client: HttpClient,
    private val store: StravaSignupStore,
    private val json: Json,
) {
    fun buildAuthorizationUrl(page: String): String {
        val safePage = normalizePage(page)
        val state = signState(OAuthStatePayload(safePage, Instant.now().epochSecond))
        val query = listOf(
            "client_id=${urlEncode(config.stravaClientId)}",
            "response_type=code",
            "redirect_uri=${urlEncode(config.stravaRedirectUri)}",
            "approval_prompt=force",
            "scope=${urlEncode("read,activity:read_all")}",
            "state=${urlEncode(state)}",
        ).joinToString("&")

        return "https://www.strava.com/oauth/authorize?$query"
    }

    suspend fun completeSignup(code: String, state: String?): Pair<SignedUpAthlete, String> {
        val tokenResponse = exchangeCodeForToken(code)
        val athlete = tokenResponse.athlete ?: error("Strava did not return athlete details during signup")
        val now = Instant.now().toString()
        val signup = SignedUpAthlete(
            athleteId = athlete.id,
            firstName = athlete.firstname,
            lastName = athlete.lastname,
            profileUrl = athlete.profileUrl,
            profileMediumUrl = athlete.profileMediumUrl,
            city = athlete.city,
            state = athlete.state,
            country = athlete.country,
            refreshToken = tokenResponse.refresh_token,
            accessToken = tokenResponse.access_token,
            expiresAt = tokenResponse.expires_at,
            signedUpAt = now,
            lastSyncedAt = now,
        )
        store.upsert(signup)

        val returnPage = verifyState(state).page
        return signup to returnPage
    }

    suspend fun getYearToDateStats(athleteId: Long): StravaAthleteStats {
        val athlete = getSignedUpAthlete(athleteId)
        val token = getValidAccessToken(athlete)
        return client.get("https://www.strava.com/api/v3/athletes/$athleteId/stats") {
            header("Authorization", "Bearer $token")
        }.body()
    }

    suspend fun getRecentActivities(athleteId: Long): RecentActivitiesResponse {
        val athlete = getSignedUpAthlete(athleteId)
        val token = getValidAccessToken(athlete)
        val activities = client.get("https://www.strava.com/api/v3/athlete/activities") {
            header("Authorization", "Bearer $token")
            parameter("per_page", 30)
            parameter("page", 1)
        }.body<List<StravaActivity>>()

        return RecentActivitiesResponse(
            activities = activities,
            count = activities.size,
        )
    }

    suspend fun getMonthlySummary(athleteId: Long): MonthlySummary {
        val athlete = getSignedUpAthlete(athleteId)
        val token = getValidAccessToken(athlete)
        val activities = fetchActivitiesSince(token, LocalDate.now(ZoneOffset.UTC).minusMonths(11).withDayOfMonth(1))
        return aggregateMonthly(activities)
    }

    suspend fun getLeaderboard(activity: String, period: String): List<LeaderboardEntry> = coroutineScope {
        val normalizedActivity = normalizeActivity(activity)
        val normalizedPeriod = normalizePeriod(period)
        val startDate = leaderboardStartDate(normalizedPeriod)
        val signups = store.getAll()
        val entries = signups.map { signup ->
            async {
                runCatching {
                    val token = getValidAccessToken(signup)
                    val activities = fetchActivitiesSince(token, startDate)
                    val totals = aggregateLeaderboardTotals(activities, normalizedActivity)

                    signup to totals
                }.getOrNull()
            }
        }.mapNotNull { it.await() }

        entries
            .filter { (_, totals) -> totals.distance > 0.0 || totals.count > 0 }
            .sortedWith(
                compareByDescending<Pair<SignedUpAthlete, ActivityTotals>> { it.second.distance }
                    .thenByDescending { it.second.moving_time }
                    .thenBy { athleteDisplayName(it.first) }
            )
            .mapIndexed { index, (signup, totals) ->
                LeaderboardEntry(
                    rank = index + 1,
                    athlete_id = signup.athleteId,
                    athlete_name = athleteDisplayName(signup),
                    avatar_url = signup.profileMediumUrl ?: signup.profileUrl,
                    city = signup.city,
                    state = signup.state,
                    country = signup.country,
                    metrics = leaderboardMetrics(normalizedActivity, totals),
                    totals = totals,
                )
            }
    }

    suspend fun getClubInfo(): String? {
        val clubId = config.stravaClubId ?: return null
        val token = getAnyClubToken() ?: return null
        return client.get("https://www.strava.com/api/v3/clubs/$clubId") {
            header("Authorization", "Bearer $token")
        }.body()
    }

    suspend fun getClubMembers(): String? {
        val clubId = config.stravaClubId ?: return null
        val token = getAnyClubToken() ?: return null
        return client.get("https://www.strava.com/api/v3/clubs/$clubId/members") {
            header("Authorization", "Bearer $token")
        }.body()
    }

    suspend fun getClubAdmins(): String? {
        val clubId = config.stravaClubId ?: return null
        val token = getAnyClubToken() ?: return null
        return client.get("https://www.strava.com/api/v3/clubs/$clubId/admins") {
            header("Authorization", "Bearer $token")
        }.body()
    }

    private suspend fun getSignedUpAthlete(athleteId: Long): SignedUpAthlete {
        return store.getAll().firstOrNull { it.athleteId == athleteId }
            ?: error("No Strava signup found for athlete $athleteId")
    }

    private suspend fun getAnyClubToken(): String? {
        val signup = store.getAll().firstOrNull() ?: return null
        return getValidAccessToken(signup)
    }

    private suspend fun getValidAccessToken(athlete: SignedUpAthlete): String {
        val now = Instant.now().epochSecond
        if (athlete.expiresAt > now + 120) {
            return athlete.accessToken
        }

        val refreshed = refreshAccessToken(athlete.refreshToken)
        val updated = athlete.copy(
            accessToken = refreshed.access_token,
            refreshToken = refreshed.refresh_token,
            expiresAt = refreshed.expires_at,
            firstName = refreshed.athlete?.firstname?.ifBlank { athlete.firstName } ?: athlete.firstName,
            lastName = refreshed.athlete?.lastname?.ifBlank { athlete.lastName } ?: athlete.lastName,
            profileUrl = refreshed.athlete?.profileUrl ?: athlete.profileUrl,
            profileMediumUrl = refreshed.athlete?.profileMediumUrl ?: athlete.profileMediumUrl,
            city = refreshed.athlete?.city ?: athlete.city,
            state = refreshed.athlete?.state ?: athlete.state,
            country = refreshed.athlete?.country ?: athlete.country,
            lastSyncedAt = Instant.now().toString(),
        )
        store.upsert(updated)
        return updated.accessToken
    }

    private suspend fun exchangeCodeForToken(code: String): StravaTokenResponse {
        return client.post("https://www.strava.com/api/v3/oauth/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", config.stravaClientId)
                        append("client_secret", config.stravaClientSecret)
                        append("code", code)
                        append("grant_type", "authorization_code")
                    }
                )
            )
        }.body()
    }

    private suspend fun refreshAccessToken(refreshToken: String): StravaTokenResponse {
        return client.post("https://www.strava.com/api/v3/oauth/token") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("client_id", config.stravaClientId)
                        append("client_secret", config.stravaClientSecret)
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    }
                )
            )
        }.body()
    }

    private suspend fun fetchActivitiesSince(accessToken: String, startDate: LocalDate): List<StravaActivity> {
        val afterEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val activities = mutableListOf<StravaActivity>()
        var page = 1

        while (true) {
            val batch = client.get("https://www.strava.com/api/v3/athlete/activities") {
                header("Authorization", "Bearer $accessToken")
                parameter("after", afterEpoch)
                parameter("per_page", 200)
                parameter("page", page)
            }.body<List<StravaActivity>>()

            if (batch.isEmpty()) {
                break
            }

            activities += batch

            if (batch.size < 200) {
                break
            }

            page += 1
        }

        return activities
    }

    private fun aggregateLeaderboardTotals(activities: List<StravaActivity>, activity: String): ActivityTotals {
        val matchingActivities = activities.filter { matchesLeaderboardActivity(it.type, activity) }

        return ActivityTotals(
            count = matchingActivities.size,
            distance = matchingActivities.sumOf { it.distance },
            moving_time = matchingActivities.sumOf { it.moving_time },
            elapsed_time = matchingActivities.sumOf { it.elapsed_time },
            elevation_gain = matchingActivities.sumOf { it.total_elevation_gain },
            achievement_count = matchingActivities.sumOf { it.achievement_count ?: 0 },
        )
    }

    private fun leaderboardMetrics(activity: String, totals: ActivityTotals): LeaderboardMetrics {
        return when (activity) {
            "ride" -> LeaderboardMetrics(
                primary_label = "distance_km",
                primary_value = totals.distance / 1000.0,
                secondary_label = "avg_speed_kph",
                secondary_value = averageSpeedKph(totals.distance, totals.moving_time),
                tertiary_label = "elevation_m",
                tertiary_value = totals.elevation_gain,
            )
            "swim" -> LeaderboardMetrics(
                primary_label = "distance_m",
                primary_value = totals.distance,
                secondary_label = "pace_per_100m_seconds",
                secondary_value = pacePer100mSeconds(totals.distance, totals.moving_time),
                tertiary_label = "sessions",
                tertiary_value = totals.count.toDouble(),
            )
            else -> LeaderboardMetrics(
                primary_label = "distance_km",
                primary_value = totals.distance / 1000.0,
                secondary_label = "pace_per_km_seconds",
                secondary_value = pacePerKmSeconds(totals.distance, totals.moving_time),
                tertiary_label = "elevation_m",
                tertiary_value = totals.elevation_gain,
            )
        }
    }

    private fun aggregateMonthly(activities: List<StravaActivity>): MonthlySummary {
        val today = LocalDate.now(ZoneOffset.UTC)
        val months = (0..11)
            .map { today.minusMonths(it.toLong()).withDayOfMonth(1) }
            .sorted()

        val aggregates = months.map { monthStart ->
            val monthKey = monthStart.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val items = activities.filter { activity ->
                activity.start_date.startsWith(monthKey)
            }
            val runs = items.filter { it.type == "Run" }
            val rides = items.filter { it.type == "Ride" }

            MonthlyAggregate(
                month = monthKey,
                run_count = runs.size,
                run_distance = runs.sumOf { it.distance },
                run_moving_time = runs.sumOf { it.moving_time },
                run_elevation_gain = runs.sumOf { it.total_elevation_gain },
                ride_count = rides.size,
                ride_distance = rides.sumOf { it.distance },
                ride_moving_time = rides.sumOf { it.moving_time },
                ride_elevation_gain = rides.sumOf { it.total_elevation_gain },
                total_count = items.size,
                total_distance = items.sumOf { it.distance },
                total_moving_time = items.sumOf { it.moving_time },
                total_elevation_gain = items.sumOf { it.total_elevation_gain },
            )
        }

        return MonthlySummary(
            months = aggregates.reversed(),
            summary = MonthlySummaryTotals(
                total_activities = aggregates.sumOf { it.total_count },
                total_distance = aggregates.sumOf { it.total_distance },
                total_moving_time = aggregates.sumOf { it.total_moving_time },
                total_elevation_gain = aggregates.sumOf { it.total_elevation_gain },
            ),
        )
    }

    private fun athleteDisplayName(athlete: SignedUpAthlete): String {
        val fullName = listOf(athlete.firstName, athlete.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (fullName.isNotBlank()) fullName else "Athlete ${athlete.athleteId}"
    }

    private fun normalizeActivity(activity: String): String {
        return when (activity.lowercase()) {
            "ride", "cycling", "bike" -> "ride"
            "swim", "swimming" -> "swim"
            else -> "run"
        }
    }

    private fun normalizePeriod(period: String): String {
        return when (period.lowercase()) {
            "7d", "7days", "week" -> "7d"
            "30d", "30days", "month" -> "30d"
            else -> "ytd"
        }
    }

    private fun leaderboardStartDate(period: String): LocalDate {
        val today = LocalDate.now(ZoneOffset.UTC)
        return when (period) {
            "7d" -> today.minusDays(6)
            "30d" -> today.minusDays(29)
            else -> today.withDayOfYear(1)
        }
    }

    private fun matchesLeaderboardActivity(stravaType: String, activity: String): Boolean {
        return when (activity) {
            "ride" -> stravaType in setOf("Ride", "VirtualRide", "EBikeRide", "MountainBikeRide", "GravelRide")
            "swim" -> stravaType in setOf("Swim")
            else -> stravaType in setOf("Run", "TrailRun", "VirtualRun")
        }
    }

    private fun averageSpeedKph(distanceMeters: Double, movingTimeSeconds: Int): Double {
        if (distanceMeters <= 0.0 || movingTimeSeconds <= 0) {
            return 0.0
        }

        return (distanceMeters / movingTimeSeconds) * 3.6
    }

    private fun pacePerKmSeconds(distanceMeters: Double, movingTimeSeconds: Int): Double {
        if (distanceMeters <= 0.0 || movingTimeSeconds <= 0) {
            return 0.0
        }

        return movingTimeSeconds / (distanceMeters / 1000.0)
    }

    private fun pacePer100mSeconds(distanceMeters: Double, movingTimeSeconds: Int): Double {
        if (distanceMeters <= 0.0 || movingTimeSeconds <= 0) {
            return 0.0
        }

        return movingTimeSeconds / (distanceMeters / 100.0)
    }

    private fun normalizePage(page: String): String {
        return if (page == "strava") "strava" else "home"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    @OptIn(ExperimentalEncodingApi::class)
    private fun verifyState(state: String?): OAuthStatePayload {
        if (state.isNullOrBlank()) {
            return OAuthStatePayload(page = "home", issuedAtEpochSeconds = Instant.now().epochSecond)
        }

        val parts = state.split('.')
        require(parts.size == 2) { "Invalid OAuth state" }
        val payload = parts[0]
        val signature = parts[1]
        val expectedSignature = hmac(payload)
        require(signature == expectedSignature) { "Invalid OAuth state signature" }

        val decodedJson = Base64.UrlSafe.decode(payload).decodeToString()
        val data = json.decodeFromString<OAuthStatePayload>(decodedJson)
        val ageSeconds = max(0, Instant.now().epochSecond - data.issuedAtEpochSeconds)
        require(ageSeconds <= 600) { "OAuth state expired" }
        return data.copy(page = normalizePage(data.page))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun signState(data: OAuthStatePayload): String {
        val payload = Base64.UrlSafe.encode(json.encodeToString(data).encodeToByteArray())
        return "$payload.${hmac(payload)}"
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.stravaClientSecret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

package io.github.runtimeifi.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val type: String,
    val data: T,
    val fetched_at: String,
)

@Serializable
data class StravaErrorResponse(
    val error: String,
    val detail: String,
)

@Serializable
data class StravaTokenResponse(
    val token_type: String,
    val access_token: String,
    val refresh_token: String,
    val expires_at: Long,
    val athlete: StravaAthlete? = null,
)

@Serializable
data class StravaAthlete(
    val id: Long,
    val firstname: String = "",
    val lastname: String = "",
    val username: String? = null,
    @SerialName("profile") val profileUrl: String? = null,
    @SerialName("profile_medium") val profileMediumUrl: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
data class StravaAthleteStats(
    val biggest_ride_distance: Double = 0.0,
    val biggest_climb_elevation_gain: Double = 0.0,
    val recent_run_totals: ActivityTotals,
    val all_run_totals: ActivityTotals,
    val recent_ride_totals: ActivityTotals,
    val all_ride_totals: ActivityTotals,
    val ytd_run_totals: ActivityTotals,
    val ytd_ride_totals: ActivityTotals,
)

@Serializable
data class ActivityTotals(
    val count: Int,
    val distance: Double,
    val moving_time: Int,
    val elapsed_time: Int,
    val elevation_gain: Double,
    val achievement_count: Int = 0,
)

@Serializable
data class StravaActivity(
    val id: Long,
    val name: String,
    val type: String,
    val distance: Double,
    val moving_time: Int,
    val elapsed_time: Int,
    val total_elevation_gain: Double = 0.0,
    val average_speed: Double? = null,
    val max_speed: Double? = null,
    val average_heartrate: Double? = null,
    val max_heartrate: Double? = null,
    val achievement_count: Int? = null,
    val kudos_count: Int? = null,
    val start_date: String,
)

@Serializable
data class SignedUpAthlete(
    val athleteId: Long,
    val firstName: String,
    val lastName: String,
    val profileUrl: String? = null,
    val profileMediumUrl: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val refreshToken: String,
    val accessToken: String,
    val expiresAt: Long,
    val signedUpAt: String,
    val lastSyncedAt: String? = null,
) {
    override fun toString(): String {
        return "SignedUpAthlete(athleteId=$athleteId, firstName=$firstName, lastName=$lastName, refreshToken=***, accessToken=***, expiresAt=$expiresAt, signedUpAt=$signedUpAt, lastSyncedAt=$lastSyncedAt)"
    }
}

@Serializable
data class RecentActivitiesResponse(
    val activities: List<StravaActivity>,
    val count: Int,
)

@Serializable
data class MonthlySummary(
    val months: List<MonthlyAggregate>,
    val summary: MonthlySummaryTotals,
)

@Serializable
data class MonthlyAggregate(
    val month: String,
    val run_count: Int,
    val run_distance: Double,
    val run_moving_time: Int,
    val run_elevation_gain: Double,
    val ride_count: Int,
    val ride_distance: Double,
    val ride_moving_time: Int,
    val ride_elevation_gain: Double,
    val total_count: Int,
    val total_distance: Double,
    val total_moving_time: Int,
    val total_elevation_gain: Double,
)

@Serializable
data class MonthlySummaryTotals(
    val total_activities: Int,
    val total_distance: Double,
    val total_moving_time: Int,
    val total_elevation_gain: Double,
)

@Serializable
data class LeaderboardResponse(
    val activity: String,
    val period: String,
    val total_athletes: Int,
    val entries: List<LeaderboardEntry>,
)

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val athlete_id: Long,
    val athlete_name: String,
    val avatar_url: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val metrics: LeaderboardMetrics,
    val totals: ActivityTotals,
)

@Serializable
data class LeaderboardMetrics(
    val primary_label: String,
    val primary_value: Double,
    val secondary_label: String,
    val secondary_value: Double,
    val tertiary_label: String,
    val tertiary_value: Double? = null,
)

@Serializable
data class OAuthStatePayload(
    val page: String,
    val issuedAtEpochSeconds: Long,
)

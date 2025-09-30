// StravaController.kt
package io.github.runtimeifi.controller

import io.github.runtimeifi.service.StravaService
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StravaController(private val stravaService: StravaService) {

    private val clubId = 1766412

    @GetMapping("/api/strava/info", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getClubInfo(): String = runBlocking {
        stravaService.get("https://www.strava.com/api/v3/clubs/$clubId")
    }

    @GetMapping("/api/strava/leaderboard", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getLeaderboard(): String = runBlocking {
        stravaService.get("https://www.strava.com/api/v3/clubs/$clubId/activities")
    }

    @GetMapping("/api/strava/members", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getMembers(): String = runBlocking {
        stravaService.get("https://www.strava.com/api/v3/clubs/$clubId/members")
    }

    @GetMapping("/api/strava/admins", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getAdmins(): String = runBlocking {
        stravaService.get("https://www.strava.com/api/v3/clubs/$clubId/admins")
    }
}

// StravaProperties.kt
package io.github.runtimeifi.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "strava")
data class StravaProperties(
    var clientId: Int = 0,
    var clientSecret: String = "",
    var accessToken: String = "",
    var refreshToken: String = ""
)

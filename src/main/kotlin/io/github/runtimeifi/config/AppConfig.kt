package io.github.runtimeifi.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

data class AppConfig(
    val frontendBaseUrl: String,
    val allowedOrigins: List<String>,
    val stravaClientId: String,
    val stravaClientSecret: String,
    val cookieSigningSecret: String,
    val stravaRedirectUri: String,
    val stravaClubId: Long?,
    val signupStorePath: Path,
    val cookieName: String,
    val cookieSecure: Boolean,
) {
    override fun toString(): String {
        return "AppConfig(frontendBaseUrl=$frontendBaseUrl, allowedOrigins=$allowedOrigins, stravaClientId=***, cookieSigningSecret=***, stravaRedirectUri=$stravaRedirectUri, stravaClubId=$stravaClubId, signupStorePath=$signupStorePath, cookieName=$cookieName, cookieSecure=$cookieSecure)"
    }
}

fun loadAppConfig(): AppConfig {
    val frontendBaseUrl = env("FRONTEND_BASE_URL", "http://localhost:5173").trimEnd('/')
    val configuredAllowedOrigins = env("CORS_ALLOWED_ORIGINS", frontendBaseUrl)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val allowedOrigins = if (configuredAllowedOrigins.isEmpty()) {
        System.err.println("CORS_ALLOWED_ORIGINS was empty after parsing, falling back to FRONTEND_BASE_URL=$frontendBaseUrl")
        listOf(frontendBaseUrl)
    } else {
        configuredAllowedOrigins
    }

    return AppConfig(
        frontendBaseUrl = frontendBaseUrl,
        allowedOrigins = allowedOrigins,
        stravaClientId = env("STRAVA_CLIENT_ID"),
        stravaClientSecret = env("STRAVA_CLIENT_SECRET"),
        cookieSigningSecret = env("STRAVA_COOKIE_SIGNING_SECRET", System.getenv("STRAVA_CLIENT_SECRET")),
        stravaRedirectUri = env("STRAVA_REDIRECT_URI"),
        stravaClubId = envOrNull("STRAVA_CLUB_ID")?.toLongOrNull(),
        signupStorePath = Path(env("STRAVA_SIGNUP_STORE_PATH", "data/strava-signups.json")),
        cookieName = env("STRAVA_AUTH_COOKIE_NAME", "runtime_strava_auth"),
        cookieSecure = env("STRAVA_AUTH_COOKIE_SECURE", "true").toBooleanStrictOrNull() ?: true,
    )
}

private fun env(name: String, defaultValue: String? = null): String {
    val value = System.getenv(name)?.trim().orEmpty()
    if (value.isNotEmpty()) {
        return value
    }

    val localValue = readLocalEnv()[name]?.trim().orEmpty()
    if (localValue.isNotEmpty()) {
        return localValue
    }

    if (defaultValue != null) {
        return defaultValue
    }

    error("Missing required environment variable: $name. Set it in your shell or in runtime-backend/.env.local")
}

private fun envOrNull(name: String): String? {
    val value = System.getenv(name)?.trim().orEmpty()
    if (value.isNotEmpty()) {
        return value
    }

    val localValue = readLocalEnv()[name]?.trim().orEmpty()
    return localValue.ifEmpty { null }
}

private val localEnvCache: Map<String, String> by lazy {
    val envPath = Path(".env.local")
    if (!envPath.exists()) {
        emptyMap()
    } else {
        Files.readAllLines(envPath)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || !trimmed.contains('=')) {
                    null
                } else {
                    val separatorIndex = trimmed.indexOf('=')
                    val key = trimmed.substring(0, separatorIndex).trim()
                    val rawValue = trimmed.substring(separatorIndex + 1).trim()
                    key to rawValue.removeSurrounding("\"").removeSurrounding("'")
                }
            }
            .toMap()
    }
}

private fun readLocalEnv(): Map<String, String> = localEnvCache

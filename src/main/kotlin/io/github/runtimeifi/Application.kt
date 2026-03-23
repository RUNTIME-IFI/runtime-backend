package io.github.runtimeifi

import io.github.runtimeifi.config.loadAppConfig
import io.github.runtimeifi.routes.respondError
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.*
import io.github.runtimeifi.routes.stravaRoutes
import io.github.runtimeifi.services.StravaService
import io.github.runtimeifi.services.StravaSignupStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main() {
    embeddedServer(Netty, port = 8080) {
        val config = loadAppConfig()
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
            install(ClientContentNegotiation) {
                json(json)
            }
        }
        environment.monitor.subscribe(ApplicationStopped) {
            httpClient.close()
        }
        val signupStore = StravaSignupStore(config.signupStorePath, json)
        val stravaService = StravaService(config, httpClient, signupStore, json)

        configureHttp(config, json)
        routing {
            stravaRoutes(stravaService, config)
        }
    }.start(wait = true)
}

private fun Application.configureHttp(config: io.github.runtimeifi.config.AppConfig, json: Json) {
    install(CallLogging) {
        level = Level.INFO
    }

    install(ContentNegotiation) {
        json(json)
    }

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowCredentials = true
        config.allowedOrigins.forEach { origin ->
            try {
                val parsed = java.net.URI(origin)
                val host = if (parsed.port == -1) parsed.host else "${parsed.host}:${parsed.port}"
                allowHost(host, schemes = listOf(parsed.scheme))
            } catch (exception: Exception) {
                throw IllegalStateException("Invalid CORS_ALLOWED_ORIGINS entry: $origin", exception)
            }
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            this@configureHttp.environment.log.error("Unhandled request failure", cause)
            call.respondError(HttpStatusCode.InternalServerError, cause.message ?: "Unexpected server error")
        }
    }
}

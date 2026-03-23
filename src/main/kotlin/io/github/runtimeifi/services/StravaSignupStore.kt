package io.github.runtimeifi.services

import io.github.runtimeifi.models.SignedUpAthlete
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.pathString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class StravaSignupStore(
    private val filePath: Path,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(SignedUpAthlete.serializer())

    suspend fun getAll(): List<SignedUpAthlete> = mutex.withLock {
        ensureFileExists()
        json.decodeFromString(serializer, Files.readString(filePath))
    }

    suspend fun upsert(athlete: SignedUpAthlete) = mutex.withLock {
        ensureFileExists()
        val current = json.decodeFromString(serializer, Files.readString(filePath))
        val updated = current
            .filterNot { it.athleteId == athlete.athleteId } + athlete
        val parent = filePath.parent
        val tempFile = Files.createTempFile(parent, "strava-signup", ".tmp")

        try {
            Files.writeString(tempFile, json.encodeToString(serializer, updated.sortedBy { it.athleteId }))
            try {
                tempFile.moveTo(filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                tempFile.moveTo(filePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            Files.deleteIfExists(tempFile)
            throw exception
        }
    }

    private fun ensureFileExists() {
        val parent = filePath.parent
        if (parent != null && !parent.exists()) {
            parent.createDirectories()
        }

        if (!filePath.exists()) {
            Files.writeString(filePath, "[]")
        }

        require(Files.isRegularFile(filePath)) {
            "Signup store path is not a file: ${filePath.pathString}"
        }
    }
}

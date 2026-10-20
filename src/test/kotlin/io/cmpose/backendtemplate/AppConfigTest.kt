package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.config.ConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppConfigTest {
    private val required = mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db/cmpose_app",
        "DATABASE_USER" to "cmpose_app",
        "DATABASE_PASSWORD" to "secret",
        "FIREBASE_PROJECT_ID" to "example-project",
        "FILES_DIR" to "/data/files",
    )
    private val quotaVars = listOf(
        "MAX_STORAGE_BYTES_PER_USER",
        // cmpose:sharing
        "MAX_SPACES_PER_USER",
        "MAX_MEMBERS_PER_SPACE",
        "MAX_PENDING_INVITES_PER_SPACE",
        // /cmpose:sharing
    )

    @Test
    fun missingRequiredListsAllNames() {
        val error = assertFailsWith<ConfigException> { AppConfig.fromEnv(emptyMap()) }
        assertEquals(
            listOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "FIREBASE_PROJECT_ID", "FILES_DIR"),
            error.missing,
        )
    }

    @Test
    fun blankRequiredCountsAsMissing() {
        val error = assertFailsWith<ConfigException> { AppConfig.fromEnv(required + ("FILES_DIR" to " ")) }
        assertEquals(listOf("FILES_DIR"), error.missing)
    }

    @Test
    fun malformedNumberIsRejected() {
        val error = assertFailsWith<ConfigException> { AppConfig.fromEnv(required + ("PORT" to "eighty")) }
        assertEquals(listOf("PORT"), error.invalid)
        assertFalse("eighty" in error.message.orEmpty(), "the value itself stays out of the message")
    }

    @Test
    fun defaultsApplied() {
        val config = AppConfig.fromEnv(required)
        assertEquals(8080, config.port)
        assertEquals(120, config.rateLimitPerMinute)
        assertEquals(10_485_760L, config.maxUploadBytes)
        assertEquals(4, config.maxConcurrentUploads)
        assertNull(config.minClientVersion)
        assertEquals("dev", config.appVersion)
    }

    @Test
    fun quotaDefaults() {
        val config = AppConfig.fromEnv(required)
        assertEquals(104_857_600L, config.maxStorageBytesPerUser)
        // cmpose:sharing
        assertEquals(20, config.maxSpacesPerUser)
        assertEquals(50, config.maxMembersPerSpace)
        assertEquals(20, config.maxPendingInvitesPerSpace)
        // /cmpose:sharing
    }

    @Test
    fun zeroTurnsAQuotaOff() {
        val config = AppConfig.fromEnv(required + quotaVars.associateWith { "0" })
        assertEquals(0L, config.maxStorageBytesPerUser)
        // cmpose:sharing
        assertEquals(0, config.maxSpacesPerUser)
        assertEquals(0, config.maxMembersPerSpace)
        assertEquals(0, config.maxPendingInvitesPerSpace)
        // /cmpose:sharing
    }

    @Test
    fun negativeOrMalformedQuotaIsRejected() {
        for (value in listOf("-1", "abc")) {
            val error = assertFailsWith<ConfigException> {
                AppConfig.fromEnv(required + quotaVars.associateWith { value })
            }
            assertEquals(quotaVars, error.invalid, "value $value")
        }
    }

    @Test
    fun toStringHidesPassword() {
        val config = AppConfig.fromEnv(required)
        assertFalse("secret" in config.toString())
    }

    @Test
    fun corsListParsed() {
        val config = AppConfig.fromEnv(required + ("CORS_ALLOWED_ORIGINS" to "https://a.test, https://b.test"))
        assertEquals(listOf("https://a.test", "https://b.test"), config.corsAllowedOrigins)
    }
}

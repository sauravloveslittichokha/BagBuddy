package com.saurav.bagbuddy.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.saurav.bagbuddy.data.LyricLine
import com.saurav.bagbuddy.data.LyricsSourceInfo
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random
import java.util.TimeZone

interface LyricsProvider {
    val providerId: String
    val capabilities: LyricsProviderCapabilities
        get() = LyricsProviderCapabilities.LINE_ONLY

    /**
     * Returns normalized source lyrics or null when the provider has no match.
     * Implementations must preserve source timing and must not infer missing times.
     */
    suspend fun fetchLyrics(song: Song): LyricsTrack?
}

class LyricsProviderNotConfiguredException(providerId: String) :
    IllegalStateException("$providerId network integration is not configured.")

class LiriQoLyricsProvider : LyricsProvider {
    override val providerId: String = "LiriQo"
    override val capabilities: LyricsProviderCapabilities =
        LyricsProviderCapabilities.LINE_AND_WORD

    override suspend fun fetchLyrics(song: Song): LyricsTrack? {
        throw LyricsProviderNotConfiguredException(providerId)
    }
}

class LrclibLyricsProvider(
    private val transport: LyricsHttpTransport = UrlConnectionLyricsHttpTransport(),
    private val parser: LyricsParser = LyricsParser(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val retryJitterSeconds: (Long) -> Long = { retryAfterSeconds ->
        val maxJitter = minOf(
            retryAfterSeconds / 10L * 3L + retryAfterSeconds % 10L * 3L / 10L,
            MAX_RETRY_JITTER_SECONDS
        )
        Random.nextLong(maxJitter + 1L)
    }
) : LyricsProvider {
    init {
        require(userAgent.isNotBlank()) { "LRCLIB requires a client-identifying User-Agent." }
        require(connectTimeoutMs > 0) { "Connect timeout must be positive." }
        require(readTimeoutMs > 0) { "Read timeout must be positive." }
    }

    override val providerId: String = "LRCLIB"
    override val capabilities: LyricsProviderCapabilities =
        LyricsProviderCapabilities.LINE_ONLY

    @Volatile private var blockedUntilNanos: Long? = null

    override suspend fun fetchLyrics(song: Song): LyricsTrack? {
        if (!LyricsTrackMatcher.hasSearchMetadata(song)) return null

        val nowNanos = System.nanoTime()
        val remainingCooldownNanos = blockedUntilNanos?.minus(nowNanos) ?: 0L
        if (remainingCooldownNanos > 0L) {
            val secondsRemaining = remainingCooldownNanos / NANOS_PER_SECOND +
                if (remainingCooldownNanos % NANOS_PER_SECOND == 0L) 0L else 1L
            throw LyricsRateLimitException(
                retryAfterSeconds = secondsRemaining
            )
        }

        val url = buildRequestUrl(song)
        val response = transport.get(
            url = url,
            userAgent = userAgent,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )

        when (response.statusCode) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_NOT_FOUND -> return null
            HTTP_TOO_MANY_REQUESTS -> {
                val retryAfterSeconds = parseRetryAfter(response.retryAfter)
                    .takeIf { it > 0L } ?: DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS
                val jitterSeconds = retryJitterSeconds(retryAfterSeconds)
                    .coerceIn(0L, MAX_RETRY_JITTER_SECONDS)
                val cooldownSeconds = if (retryAfterSeconds > Long.MAX_VALUE - jitterSeconds) {
                    Long.MAX_VALUE
                } else {
                    retryAfterSeconds + jitterSeconds
                }
                val cooldownNanos = if (cooldownSeconds > Long.MAX_VALUE / NANOS_PER_SECOND) {
                    Long.MAX_VALUE
                } else {
                    cooldownSeconds * NANOS_PER_SECOND
                }
                blockedUntilNanos = System.nanoTime() + cooldownNanos
                throw LyricsRateLimitException(retryAfterSeconds)
            }
            else -> throw LyricsHttpException(
                statusCode = response.statusCode,
                retryAfterSeconds = response.retryAfter?.let(::parseRetryAfter)?.takeIf { it > 0L }
            )
        }

        val record = try {
            JsonParser.parseString(response.body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (failure: JsonSyntaxException) {
            throw LyricsResponseException("LRCLIB returned invalid JSON.", failure)
        } ?: throw LyricsResponseException("LRCLIB returned an unexpected response.")

        if (record.has("error") && !record.get("error").isJsonNull) {
            throw LyricsResponseException(
                "LRCLIB returned an error: ${record.get("error")}"
            )
        }

        val title = record.stringOrNull("trackName")?.trim().orEmpty()
        val artist = record.stringOrNull("artistName")?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) {
            throw LyricsResponseException("LRCLIB response is missing track or artist metadata.")
        }

        val durationMs = record.numberOrNull("duration")
            ?.takeIf { it > 0.0 && it <= 3_600.0 }
            ?.let { (it * 1_000.0).toLong() }
            ?: 0L
        val resultSong = Song(
            id = record.get("id")
                ?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }
                ?.asString ?: "lrclib",
            title = title,
            artist = artist,
            album = record.stringOrNull("albumName").orEmpty(),
            durationMs = durationMs,
            artworkEmoji = song.artworkEmoji
        )
        val sourceInfo = LyricsSourceInfo(providerId = providerId)

        val syncedLyrics = record.stringOrNull("syncedLyrics").orEmpty()
        val plainLyrics = record.stringOrNull("plainLyrics").orEmpty()
        val timedTrack = syncedLyrics.takeIf(String::isNotBlank)
            ?.let { parser.parseLrc(resultSong, it) }
            ?.takeIf { it.lines.isNotEmpty() }
            ?.copy(sourceInfo = sourceInfo)
        val plainTrack = plainLyrics.takeIf(String::isNotBlank)
            ?.let { text ->
                LyricsTrack(
                    song = resultSong,
                    lines = text.lineSequence()
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map(::LyricLine)
                        .toList(),
                    sourceInfo = sourceInfo
                )
            }

        return timedTrack ?: plainTrack
    }

    private fun buildRequestUrl(song: Song): String {
        val query = linkedMapOf(
            "track_name" to song.title.trim(),
            "artist_name" to song.artist.trim()
        )
        song.album.trim().takeIf(String::isNotEmpty)?.let { query["album_name"] = it }
        song.durationMs.takeIf { it > 0L }?.let { durationMs ->
            val seconds = durationMs / 1_000L
            if (seconds in 1L..3_600L) query["duration"] = seconds.toString()
        }
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$BASE_URL/api/get?$encoded"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun parseRetryAfter(value: String?): Long {
        val header = value?.trim().orEmpty()
        header.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        if (header.isEmpty()) return 0L

        val date = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(header)
        }.getOrNull() ?: return 0L
        return ((date.time - System.currentTimeMillis()) / 1_000L).coerceAtLeast(0L)
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }
            ?.asJsonPrimitive?.takeIf { it.isString }?.asString

    private fun JsonObject.numberOrNull(name: String): Double? =
        get(name)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }
            ?.asJsonPrimitive?.takeIf { it.isNumber }?.asDouble

    private companion object {
        const val BASE_URL = "https://lrclib.net"
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MAX_RETRY_JITTER_SECONDS = 300L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_USER_AGENT =
            "Bag Buddy/1.0 (https://github.com/sauravloveslittichokha/BagBuddy)"
    }
}

data class LyricsHttpResponse(
    val statusCode: Int,
    val body: String,
    val retryAfter: String? = null
)

fun interface LyricsHttpTransport {
    suspend fun get(
        url: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): LyricsHttpResponse
}

class UrlConnectionLyricsHttpTransport : LyricsHttpTransport {
    override suspend fun get(
        url: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): LyricsHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(BODY_READ_BUFFER_SIZE)
                while (body.length <= MAX_RESPONSE_CHARS) {
                    val remaining = MAX_RESPONSE_CHARS + 1 - body.length
                    val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    body.append(buffer, 0, read)
                }
                if (body.length > MAX_RESPONSE_CHARS) {
                    throw LyricsResponseException("LRCLIB response exceeded the size limit.")
                }
                body.toString()
            }.orEmpty()
            LyricsHttpResponse(
                statusCode = statusCode,
                body = body,
                retryAfter = connection.getHeaderField("Retry-After")
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val BODY_READ_BUFFER_SIZE = 8_192
    }
}

open class LyricsHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null
) : IOException(
    buildString {
        append("LRCLIB HTTP error ")
        append(statusCode)
        if (retryAfterSeconds != null) append("; retry after $retryAfterSeconds seconds.")
    }
)

class LyricsRateLimitException(retryAfterSeconds: Long) :
    LyricsHttpException(429, retryAfterSeconds)

class LyricsResponseException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

package com.saurav.bagbuddy.lyrics

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.saurav.bagbuddy.data.LyricsTrack
import com.saurav.bagbuddy.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class CachedLyrics(
    val track: LyricsTrack,
    val providerId: String
)

interface LyricsCache {
    suspend fun get(song: Song): CachedLyrics?
    suspend fun put(song: Song, lyrics: CachedLyrics)
    suspend fun remove(song: Song)
}

class InMemoryLyricsCache : LyricsCache {
    private val tracks = mutableMapOf<String, CachedLyrics>()

    override suspend fun get(song: Song): CachedLyrics? = tracks[cacheKey(song)]

    override suspend fun put(song: Song, lyrics: CachedLyrics) {
        tracks[cacheKey(song)] = lyrics
    }

    override suspend fun remove(song: Song) {
        tracks.remove(cacheKey(song))
    }
}

class SharedPreferencesLyricsCache(context: Context) : LyricsCache {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun get(song: Song): CachedLyrics? {
        return withContext(Dispatchers.IO) {
            val key = cacheKey(song)
            val value = preferences.getString(key, null) ?: return@withContext null
            try {
                val cached = gson.fromJson(value, CachedLyrics::class.java)
                check(cached?.track != null && cached.providerId.isNotBlank()) {
                    "Cached lyrics are incomplete."
                }
                cached
            } catch (failure: JsonParseException) {
                preferences.edit().remove(key).commit()
                throw IllegalStateException("Cached lyrics could not be decoded.", failure)
            } catch (failure: IllegalStateException) {
                preferences.edit().remove(key).commit()
                throw failure
            }
        }
    }

    override suspend fun put(song: Song, lyrics: CachedLyrics) {
        withContext(Dispatchers.IO) {
            val saved = preferences.edit()
                .putString(cacheKey(song), gson.toJson(lyrics))
                .commit()
            check(saved) { "Cached lyrics could not be saved." }
        }
    }

    override suspend fun remove(song: Song) {
        withContext(Dispatchers.IO) {
            val removed = preferences.edit().remove(cacheKey(song)).commit()
            check(removed) { "Invalid cached lyrics could not be removed." }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "lyrics_cache"
    }
}

private fun cacheKey(song: Song): String {
    val normalized = "${song.artist.trim().lowercase()}|${song.title.trim().lowercase()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
    return "lyrics_${digest.joinToString("") { byte -> "%02x".format(byte) }}"
}

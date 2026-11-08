package com.saurav.bagbuddy.lyrics

import android.util.Log

fun interface LyricsDiagnostics {
    fun record(message: String)

    companion object {
        val NONE = LyricsDiagnostics { }
    }
}

class AndroidLyricsDiagnostics : LyricsDiagnostics {
    override fun record(message: String) {
        Log.i(TAG, message.take(MAX_LOG_LENGTH))
    }

    private companion object {
        const val TAG = "BagBuddyLyrics"
        const val MAX_LOG_LENGTH = 2_000
    }
}

internal fun String.asDiagnosticValue(): String =
    replace(Regex("[\\r\\n\\t]"), " ").take(MAX_DIAGNOSTIC_VALUE_LENGTH)

private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 120

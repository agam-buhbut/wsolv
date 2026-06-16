package com.wsolv.app.persist

import android.content.Context

/**
 * Tiny disk-backed set of words the player has invalidated for one game, so a
 * rejected word is never suggested again — across resets, screen navigation,
 * and app restarts.
 *
 * Backed by [android.content.SharedPreferences] (no extra dependency). Each game
 * gets its own [gameKey]; the value is a `Set<String>` of lowercase words.
 *
 * @param context any context; the application context is used to avoid leaks.
 * @param gameKey a stable per-game key, e.g. `"poople"` or `"dontwordle"`.
 */
class RejectStore(context: Context, private val gameKey: String) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted words, or an empty set if none. Always a private copy. */
    fun load(): Set<String> = prefs.getStringSet(gameKey, null)?.toHashSet() ?: emptySet()

    /** Replace the persisted set with [words]. */
    fun save(words: Set<String>) {
        // SharedPreferences must not be handed a set it might later mutate.
        prefs.edit().putStringSet(gameKey, HashSet(words)).apply()
    }

    /** Forget every word for this game. */
    fun clear() {
        prefs.edit().remove(gameKey).apply()
    }

    private companion object {
        const val PREFS_NAME = "wsolv_rejects"
    }
}

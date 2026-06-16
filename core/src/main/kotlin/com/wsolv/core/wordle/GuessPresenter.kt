package com.wsolv.core.wordle

/**
 * Presents the solver's next suggested guess to the user.
 *
 * This is a V2 insertion point. In V1 the implementation renders the
 * suggestion on the app screen; in V2 it draws an on-screen overlay above the
 * live Wordle app. The solver core depends only on this seam.
 */
interface GuessPresenter {
    /** Show [suggestion], the next word the user should play. */
    fun present(suggestion: String)
}

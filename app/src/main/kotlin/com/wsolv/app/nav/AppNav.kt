package com.wsolv.app.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wsolv.app.home.HomeScreen
import com.wsolv.app.poople.PoopleScreen
import com.wsolv.app.reverse.ReverseScreen
import com.wsolv.app.wordle.WordleScreen
import com.wsolv.core.poople.PoopleSolver
import com.wsolv.core.wordle.WordleData

/** Navigation routes for the app. */
private object Routes {
    const val HOME = "home"
    const val WORDLE = "wordle"
    const val POOPLE = "poople"
    const val REVERSE = "reverse"
}

/**
 * Top-level navigation graph. Receives the already-loaded solver inputs and
 * hands them to each screen, which builds its own view model.
 */
@Composable
fun AppNav(
    wordle: WordleData,
    poople: PoopleSolver,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onWordle = { navController.navigate(Routes.WORDLE) },
                onPoople = { navController.navigate(Routes.POOPLE) },
                onReverse = { navController.navigate(Routes.REVERSE) },
            )
        }
        composable(Routes.WORDLE) {
            WordleScreen(data = wordle)
        }
        composable(Routes.POOPLE) {
            PoopleScreen(solver = poople)
        }
        composable(Routes.REVERSE) {
            ReverseScreen(data = wordle)
        }
    }
}

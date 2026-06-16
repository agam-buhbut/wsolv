package com.wsolv.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wsolv.app.ui.theme.WsolvTheme

/** Landing screen: app title and entry points to each solver. */
@Composable
fun HomeScreen(
    onWordle: () -> Unit,
    onPoople: () -> Unit,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "wsolv",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Word puzzle solvers",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 48.dp),
        )
        Button(
            onClick = onWordle,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(text = "Wordle Solver", style = MaterialTheme.typography.titleLarge)
        }
        Button(
            onClick = onPoople,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(text = "Poople Solver", style = MaterialTheme.typography.titleLarge)
        }
        Button(
            onClick = onReverse,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(text = "Reverse Wordle (lose!)", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    WsolvTheme {
        HomeScreen(onWordle = {}, onPoople = {}, onReverse = {})
    }
}

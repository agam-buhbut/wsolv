package com.wsolv.app.reverse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wsolv.app.persist.RejectStore
import com.wsolv.app.ui.theme.OnTile
import com.wsolv.app.ui.theme.WsolvTheme
import com.wsolv.app.ui.theme.tileColor
import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.LosingStatus
import com.wsolv.core.wordle.LosingWordleSolver
import com.wsolv.core.wordle.WordleData

private val colorLabels = listOf("Gray", "Yellow", "Green")
private val colorCodes = listOf(Feedback.GRAY, Feedback.YELLOW, Feedback.GREEN)

/**
 * Reverse ("losing") Wordle screen.
 *
 * Constructs (or reuses) a [ReverseViewModel] bound to [data] and renders its
 * state. All interaction is delegated to the view model; this composable holds
 * no game logic.
 */
@Composable
fun ReverseScreen(
    data: WordleData,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: ReverseViewModel =
        viewModel { ReverseViewModel(data, RejectStore(context, "dontwordle")) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    ReverseContent(
        state = state,
        onSetColor = vm::setColor,
        onSubmit = vm::submit,
        onSkip = vm::skip,
        onReset = vm::reset,
        onClearRejected = vm::clearRejected,
        modifier = modifier,
    )
}

@Composable
private fun ReverseContent(
    state: ReverseUiState,
    onSetColor: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onReset: () -> Unit,
    onClearRejected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = state.status == LosingStatus.PLAYING

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Reverse Wordle",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            item {
                StatusBanner(status = state.status, guessCount = state.guessCount)
            }

            if (playing && state.suggestion.isNotEmpty()) {
                item {
                    Text(
                        text = "Play this word (you don't want to win)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    SuggestionTiles(suggestion = state.suggestion, colors = state.pendingColors)
                }
                item {
                    ColorPickers(
                        suggestion = state.suggestion,
                        colors = state.pendingColors,
                        onSetColor = onSetColor,
                    )
                }
                item {
                    Text(
                        text =
                            "guess ${state.guessCount + 1}/${LosingWordleSolver.MAX_GUESSES} • " +
                                "${state.remainingCount} possible answers remaining",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Button(
                        onClick = onSubmit,
                        enabled = !state.thinking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Submit feedback")
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = !state.thinking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Skip this word (game rejected it)")
                    }
                }
            }

            if (state.thinking) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset")
                }
            }

            if (state.rejectedCount > 0) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Avoiding ${state.rejectedCount} rejected word(s)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onClearRejected, enabled = !state.thinking) {
                            Text("Clear")
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                // Most recent first.
                items(state.history.asReversed()) { row ->
                    HistoryRow(row = row)
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: LosingStatus, guessCount: Int) {
    when (status) {
        LosingStatus.PLAYING ->
            Banner(
                text = "Pick a word that won't win — survive ${LosingWordleSolver.MAX_GUESSES} guesses",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        LosingStatus.SURVIVED ->
            Banner(
                text = "Survived $guessCount guesses — you lost Wordle! 🎉",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        LosingStatus.CORNERED ->
            Banner(
                text = "Cornered — only 1 answer left, you'd be forced to solve",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        LosingStatus.SOLVED ->
            Banner(
                text = "All green — you accidentally won Wordle 😅",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
    }
}

@Composable
private fun Banner(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun SuggestionTiles(suggestion: String, colors: List<Int>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (i in 0 until Feedback.WORD_LEN) {
            val letter = suggestion.getOrNull(i)?.uppercaseChar()?.toString().orEmpty()
            val code = colors.getOrElse(i) { Feedback.GRAY }
            Box(
                modifier = Modifier.weight(1f).aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = tileColor(code),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = letter,
                            color = OnTile,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPickers(
    suggestion: String,
    colors: List<Int>,
    onSetColor: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until Feedback.WORD_LEN) {
            val letter = suggestion.getOrNull(i)?.uppercaseChar()?.toString().orEmpty()
            val selected = colors.getOrElse(i) { Feedback.GRAY }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp),
                )
                Spacer(Modifier.size(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    colorCodes.forEachIndexed { index, code ->
                        SegmentedButton(
                            selected = selected == code,
                            onClick = { onSetColor(i, code) },
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = colorCodes.size,
                                ),
                        ) {
                            Text(colorLabels[index])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(row: ReverseGuessRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until Feedback.WORD_LEN) {
                val letter = row.guess.getOrNull(i)?.uppercaseChar()?.toString().orEmpty()
                val code = row.colors.getOrElse(i) { Feedback.GRAY }
                Surface(
                    color = tileColor(code),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = letter,
                            color = OnTile,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReverseContentPreview() {
    WsolvTheme {
        ReverseContent(
            state =
                ReverseUiState(
                    suggestion = "fuzzy",
                    pendingColors =
                        listOf(
                            Feedback.GRAY,
                            Feedback.GRAY,
                            Feedback.GRAY,
                            Feedback.GRAY,
                            Feedback.GRAY,
                        ),
                    history =
                        listOf(
                            ReverseGuessRow(
                                guess = "jumpy",
                                colors =
                                    listOf(
                                        Feedback.GRAY,
                                        Feedback.GRAY,
                                        Feedback.GRAY,
                                        Feedback.GRAY,
                                        Feedback.GRAY,
                                    ),
                            ),
                        ),
                    guessCount = 1,
                    remainingCount = 120,
                    status = LosingStatus.PLAYING,
                ),
            onSetColor = { _, _ -> },
            onSubmit = {},
            onSkip = {},
            onReset = {},
            onClearRejected = {},
        )
    }
}

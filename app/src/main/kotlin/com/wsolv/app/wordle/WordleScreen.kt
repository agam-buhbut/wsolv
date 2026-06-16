package com.wsolv.app.wordle

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wsolv.app.ui.theme.OnTile
import com.wsolv.app.ui.theme.WsolvTheme
import com.wsolv.app.ui.theme.tileColor
import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.WordleData
import com.wsolv.core.wordle.WordleStatus

private val colorLabels = listOf("Gray", "Yellow", "Green")
private val colorCodes = listOf(Feedback.GRAY, Feedback.YELLOW, Feedback.GREEN)

/**
 * Wordle solver screen.
 *
 * Constructs (or reuses) a [WordleViewModel] bound to [data] and renders its
 * state. All interaction is delegated to the view model; this composable holds
 * no game logic.
 */
@Composable
fun WordleScreen(
    data: WordleData,
    modifier: Modifier = Modifier,
) {
    val vm: WordleViewModel = viewModel { WordleViewModel(data) }
    val state by vm.uiState.collectAsStateWithLifecycle()

    WordleContent(
        state = state,
        onSetColor = vm::setColor,
        onSubmit = vm::submit,
        onReset = vm::reset,
        onSetHardMode = vm::setHardMode,
        modifier = modifier,
    )
}

@Composable
private fun WordleContent(
    state: WordleUiState,
    onSetColor: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
    onSetHardMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = state.status == WordleStatus.PLAYING

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HardModeToggle(hardMode = state.hardMode, onSetHardMode = onSetHardMode)
        }

        item {
            StatusBanner(status = state.status, solvedAt = state.history.size)
        }

        if (state.offTree) {
            item {
                AssistChip(
                    onClick = {},
                    label = { Text("off-tree: live solver") },
                )
            }
        }

        if (playing && state.suggestion.isNotEmpty()) {
            item {
                Text(
                    text = "Play this word",
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
                    text = "${state.remainingCount} possible answers remaining",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
                    Text("Submit feedback")
                }
            }
        }

        item {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
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

@Composable
private fun HardModeToggle(hardMode: Boolean, onSetHardMode: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Hard mode",
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(
                checked = hardMode,
                onCheckedChange = onSetHardMode,
            )
        }
        Text(
            text = "Hard mode reuses every revealed letter and keeps greens in place.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusBanner(status: WordleStatus, solvedAt: Int) {
    when (status) {
        WordleStatus.SOLVED ->
            Banner(
                text = "Solved in $solvedAt!",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        WordleStatus.CONTRADICTION ->
            Banner(
                text = "No consistent answer — check your inputs, or this may be a Wordle variant",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        WordleStatus.PLAYING -> Unit
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
private fun HistoryRow(row: GuessRow) {
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
private fun WordleContentPreview() {
    WsolvTheme {
        WordleContent(
            state =
                WordleUiState(
                    suggestion = "slate",
                    pendingColors =
                        listOf(
                            Feedback.GRAY,
                            Feedback.YELLOW,
                            Feedback.GREEN,
                            Feedback.GRAY,
                            Feedback.YELLOW,
                        ),
                    history =
                        listOf(
                            GuessRow(
                                guess = "raise",
                                colors =
                                    listOf(
                                        Feedback.GRAY,
                                        Feedback.YELLOW,
                                        Feedback.GRAY,
                                        Feedback.GRAY,
                                        Feedback.GREEN,
                                    ),
                            ),
                        ),
                    remainingCount = 42,
                    status = WordleStatus.PLAYING,
                    offTree = false,
                    hardMode = true,
                ),
            onSetColor = { _, _ -> },
            onSubmit = {},
            onReset = {},
            onSetHardMode = {},
        )
    }
}

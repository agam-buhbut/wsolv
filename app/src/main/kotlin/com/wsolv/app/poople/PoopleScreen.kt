package com.wsolv.app.poople

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wsolv.app.persist.RejectStore
import com.wsolv.app.ui.theme.WsolvTheme
import com.wsolv.core.poople.PoopleSolver

/**
 * Poople solver screen.
 *
 * Constructs (or reuses) a [PoopleViewModel] bound to [solver] and renders its
 * state. No solving logic lives here.
 */
@Composable
fun PoopleScreen(
    solver: PoopleSolver,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: PoopleViewModel =
        viewModel { PoopleViewModel(solver, RejectStore(context, "poople")) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    PoopleContent(
        state = state,
        onInput = vm::onInput,
        onSolve = vm::solve,
        onReject = vm::rejectWord,
        onClearRejected = vm::clearRejected,
        modifier = modifier,
    )
}

@Composable
private fun PoopleContent(
    state: PoopleUiState,
    onInput: (String) -> Unit,
    onSolve: () -> Unit,
    onReject: (Int) -> Unit,
    onClearRejected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Word ladder to \"poop\"",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            label = { Text("Start word (4 letters)") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onSolve() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onSolve,
            enabled = !state.solving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Solve")
        }

        if (state.solving) {
            CircularProgressIndicator()
        }

        if (state.excluded.isNotEmpty()) {
            RejectedNotice(
                count = state.excluded.size,
                enabled = !state.solving,
                onClear = onClearRejected,
            )
        }

        if (state.message != null) {
            Message(state.message)
        }

        if (state.path.isNotEmpty()) {
            PathView(
                path = state.path,
                steps = state.steps,
                rerouting = state.solving,
                onReject = onReject,
            )
        }
    }
}

@Composable
private fun PathView(
    path: List<String>,
    steps: Int,
    rerouting: Boolean,
    onReject: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepBadge(steps = steps)
        path.forEachIndexed { index, word ->
            LadderStep(
                word = word,
                rejectable = index >= 1 && !rerouting,
                onReject = { onReject(index) },
            )
            if (index < path.lastIndex) {
                Text(text = "↓", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun LadderStep(
    word: String,
    rejectable: Boolean,
    onReject: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = word.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (rejectable) {
            RejectButton(onReject = onReject)
        }
    }
}

@Composable
private fun RejectButton(onReject: () -> Unit) {
    Surface(
        onClick = onReject,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .size(36.dp)
                .semantics { contentDescription = "mark invalid" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StepBadge(steps: Int) {
    val label = if (steps == 1) "1 step" else "$steps steps"
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RejectedNotice(count: Int, enabled: Boolean, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Avoiding $count rejected word(s)",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onClear, enabled = enabled) {
            Text("Clear")
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PoopleContentPreview() {
    WsolvTheme {
        PoopleContent(
            state =
                PoopleUiState(
                    input = "pomp",
                    path = listOf("pomp", "pomo", "pomo", "poop"),
                    steps = 3,
                    solving = false,
                ),
            onInput = {},
            onSolve = {},
            onReject = {},
            onClearRejected = {},
        )
    }
}

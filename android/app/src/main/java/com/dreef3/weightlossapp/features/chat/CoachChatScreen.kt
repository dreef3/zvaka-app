package com.dreef3.weightlossapp.features.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.ChatRole
import kotlinx.coroutines.delay

@Composable
fun CoachChatScreenRoute(
    container: AppContainer,
) {
    val viewModel: CoachChatViewModel = viewModel(factory = CoachChatViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CoachChatScreen(
        state = state,
        onInputChanged = viewModel::updateInput,
        onSend = viewModel::send,
        onRequestOverview = viewModel::requestOverview,
        onSuggestCorrection = viewModel::insertCorrectionExample,
    )
}

@Composable
fun CoachChatScreen(
    state: CoachChatUiState,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRequestOverview: () -> Unit,
    onSuggestCorrection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Coach",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.messages) { message ->
                ChatBubble(
                    role = message.role,
                    text = message.text,
                )
            }
            if (state.isSending) {
                item {
                    TypingBubble()
                }
            }
        }
        if (state.showOverviewSuggestion) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SuggestionBubble(
                    text = "Give me overview for today",
                    onClick = onRequestOverview,
                )
                SuggestionBubble(
                    text = "Correct a meal entry",
                    onClick = onSuggestCorrection,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Ask about your diet") },
                enabled = !state.isSending,
            )
            Button(
                onClick = onSend,
                enabled = !state.isSending && state.input.isNotBlank(),
            ) {
                Text(if (state.isSending) "..." else "Send")
            }
        }
    }
}

@Composable
private fun SuggestionBubble(
    text: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
    )
}

@Composable
private fun ChatBubble(
    role: ChatRole,
    text: String,
) {
    val isUser = role == ChatRole.User
    val background = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = background),
            shape = RoundedCornerShape(24.dp),
        ) {
            Text(
                text = text.toAnnotatedMarkdown(),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun String.toAnnotatedMarkdown(): AnnotatedString {
    val source = this
    return buildAnnotatedString {
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("**", index) -> {
                    val end = source.indexOf("**", startIndex = index + 2)
                    if (end > index + 1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(source.substring(index + 2, end))
                        pop()
                        index = end + 2
                    } else {
                        append(source[index])
                        index += 1
                    }
                }
                source.startsWith("*", index) -> {
                    val end = source.indexOf("*", startIndex = index + 1)
                    if (end > index) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(source.substring(index + 1, end))
                        pop()
                        index = end + 1
                    } else {
                        append(source[index])
                        index += 1
                    }
                }
                else -> {
                    append(source[index])
                    index += 1
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    var activeDot by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(320)
            activeDot = (activeDot + 1) % 3
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = androidx.compose.ui.Alignment.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(if (index == activeDot) 1f else 0.35f)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

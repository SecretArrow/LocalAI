package com.localai.runtime.ui.screens.chat

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.localai.runtime.core.model.ModelInfo
import com.localai.runtime.runtime.AppContainer
import com.localai.runtime.ui.components.EmptyState

/**
 * Single-conversation chat screen. Requires a running model — when none is running
 * the screen shows an empty state pointing at the Models tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer) {
    val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(container) } })
    val messages by vm.messages.collectAsStateWithLifecycle()
    val runningModels by vm.runningModels.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val temperature by vm.temperature.collectAsStateWithLifecycle()
    val maxTokens by vm.maxTokens.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    var showParams by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<UiMessage?>(null) }
    val listState = rememberLazyListState()

    // Auto-scroll while streaming, but never yank the view if the user scrolled up.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= messages.size - 2) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModelSelector(
                runningModels = runningModels,
                selectedModelId = selectedModelId,
                onSelect = vm::selectModel,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showParams = !showParams }) {
                Icon(Icons.Filled.Tune, contentDescription = "Parameters")
            }
            IconButton(onClick = { vm.clearConversation() }) {
                Icon(Icons.Filled.Close, contentDescription = "New chat")
            }
        }

        if (showParams) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = vm::setSystemPrompt,
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                )
                Text(
                    "Temperature: ${"%.2f".format(temperature)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = temperature,
                    onValueChange = vm::setTemperature,
                    valueRange = 0f..2f,
                )
                Text(
                    "Max tokens: $maxTokens",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { vm.setMaxTokens(it.toInt()) },
                    valueRange = 64f..4096f,
                )
            }
        }

        if (generating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val maxBubbleWidth = maxWidth * 0.8f
            when {
                runningModels.isEmpty() && messages.isEmpty() -> EmptyState(
                    title = "No model running",
                    subtitle = "Start a model from the Models tab first",
                    modifier = Modifier.align(Alignment.Center),
                )
                messages.isEmpty() -> EmptyState(
                    title = "Say something",
                    subtitle = "Your conversation appears here",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages) { _, message ->
                        MessageBubble(
                            message = message,
                            maxBubbleWidth = maxBubbleWidth,
                            onLongPress = { actionMessage = message },
                        )
                    }
                }
            }

            val banner = when {
                error != null -> error
                runningModels.isEmpty() -> "The model stopped. Start it again from the Models tab."
                else -> null
            }
            if (banner != null) {
                ErrorBanner(
                    message = banner,
                    onDismiss = vm::dismissError,
                    dismissible = error != null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
            )
            if (generating) {
                FilledIconButton(onClick = vm::stopGeneration) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop generation")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val text = input
                        input = ""
                        vm.send(text)
                    },
                    enabled = input.isNotBlank() && selectedModelId != null,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text("Message") },
            text = { Text(message.content) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        actionMessage = null
                    },
                ) { Text("Copy") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message.content)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share message"))
                        actionMessage = null
                    },
                ) { Text("Share") }
            },
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    dismissible: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (dismissible) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    runningModels: List<ModelInfo>,
    selectedModelId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = runningModels.firstOrNull { it.id == selectedModelId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (runningModels.isNotEmpty()) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.name ?: "No model running",
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            runningModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.name) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: UiMessage,
    maxBubbleWidth: Dp,
    onLongPress: () -> Unit,
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val segments = remember(message.content) { parseSegments(message.content) }
                if (segments.isEmpty() && message.streaming) {
                    Text("▍", style = MaterialTheme.typography.bodyMedium)
                }
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is MdSegment.Code -> CodeBlock(segment.text)
                        is MdSegment.Text -> Text(
                            text = buildMessageAnnotated(
                                text = segment.text,
                                inlineCodeBackground = Color(0x55606060),
                                showCaret = message.streaming && index == segments.lastIndex,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = Color(0xFF1B1F24),
        contentColor = Color(0xFFD8DEE4),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/** Markdown-ish segments of a message: fenced code blocks and plain text. */
private sealed interface MdSegment {
    data class Text(val text: String) : MdSegment
    data class Code(val text: String) : MdSegment
}

private val codeFenceRegex = Regex("```[a-zA-Z0-9_+.-]*\\n?([\\s\\S]*?)```")

private fun parseSegments(content: String): List<MdSegment> {
    if (content.isEmpty()) return emptyList()
    val segments = mutableListOf<MdSegment>()
    var cursor = 0
    for (match in codeFenceRegex.findAll(content)) {
        if (match.range.first > cursor) {
            segments += MdSegment.Text(content.substring(cursor, match.range.first))
        }
        segments += MdSegment.Code(match.groupValues[1].trimEnd('\n'))
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        segments += MdSegment.Text(content.substring(cursor))
    }
    return segments
}

private val inlineMarkdownRegex = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`([^`]+)`")

/** Renders **bold**, *italic* and `inline code` plus an optional streaming caret. */
private fun buildMessageAnnotated(
    text: String,
    inlineCodeBackground: Color,
    showCaret: Boolean,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlineMarkdownRegex.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(groups[1]) }
            groups[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(groups[2]) }
            else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBackground)) {
                append(groups[3])
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
    if (showCaret) append(" ▍")
}

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationSummary
import me.rerere.rikkahub.data.model.CompressionUndoMode

@Composable
fun ConversationSummaryDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onSave: suspend (List<ConversationSummary>, Boolean, CompressionUndoMode?) -> Unit,
    onMerge: suspend (List<ConversationSummary>, Boolean, String, Int) -> List<ConversationSummary>,
) {
    var summaries by remember(conversation.id) { mutableStateOf(conversation.compressionSummaries) }
    var enabled by remember(conversation.id) { mutableStateOf(conversation.sendCompressedSummary) }
    var savingChanges by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var showMergeOptions by remember { mutableStateOf(false) }
    val saving = savingChanges || merging
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUndo by remember { mutableStateOf<CompressionUndoMode?>(null) }
    val scope = rememberCoroutineScope()
    fun save(undo: CompressionUndoMode? = null) {
        savingChanges = true
        error = null
        scope.launch {
            try {
                onSave(summaries, enabled, undo)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                savingChanges = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.compression_summary_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.compression_send_summary), Modifier.weight(1f))
                    val label = stringResource(R.string.compression_send_summary)
                    Switch(enabled, { enabled = it }, enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = label })
                }
                Text(stringResource(R.string.compression_send_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.compression_selected_count,
                    if (enabled) summaries.count { it.sendToModel } else 0, summaries.size))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { summaries = summaries.map { it.copy(sendToModel = true) } },
                        enabled = !saving, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(stringResource(R.string.compression_select_all), maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { summaries = summaries.map { it.copy(sendToModel = false) } },
                        enabled = !saving, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(stringResource(R.string.compression_select_none), maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = { showMergeOptions = true },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        enabled = !saving && enabled && summaries.any { it.sendToModel } && summaries.all { it.text.isNotBlank() },
                    ) {
                        Text(stringResource(R.string.compression_merge_existing), maxLines = 1, softWrap = false)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(summaries, key = { _, part -> part.id }) { index, part ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val label = stringResource(R.string.compression_send_part, index + 1)
                                Checkbox(part.sendToModel, { selected ->
                                    summaries = summaries.map { if (it.id == part.id) it.copy(sendToModel = selected) else it }
                                }, enabled = !saving, modifier = Modifier.semantics { contentDescription = label })
                                Text(label)
                            }
                            OutlinedTextField(
                                value = part.text,
                                onValueChange = { text -> summaries = summaries.map { if (it.id == part.id) it.copy(text = text) else it } },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                                label = { Text(stringResource(R.string.compression_part, index + 1)) },
                                minLines = 2,
                                maxLines = 8,
                                isError = part.text.isBlank(),
                            )
                            TextButton(onClick = {
                                summaries = summaries.flatMap { if (it.id == part.id) it.splitLines() else listOf(it) }
                            }, enabled = !saving && part.text.lines().count { it.isNotBlank() } > 1) {
                                Text(stringResource(R.string.compression_split_lines))
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (conversation.hasCompressionState) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { confirmUndo = CompressionUndoMode.LATEST },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            enabled = !saving && conversation.latestCompressionSnapshot != null) {
                            Text(stringResource(R.string.compression_undo_latest), maxLines = 1, softWrap = false)
                        }
                        TextButton(onClick = { confirmUndo = CompressionUndoMode.ALL },
                            contentPadding = PaddingValues(horizontal = 6.dp), enabled = !saving) {
                            Text(stringResource(R.string.compression_undo_all), color = MaterialTheme.colorScheme.error,
                                maxLines = 1, softWrap = false)
                        }
                    }
                    if (conversation.latestCompressionSnapshot == null) {
                        Text(stringResource(R.string.compression_undo_no_history), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving && summaries.isNotEmpty() && summaries.all { it.text.isNotBlank() }) {
                Text(stringResource(R.string.chat_page_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
    if (showMergeOptions) {
        MergeSummariesDialog(
            onDismiss = { showMergeOptions = false },
            onConfirm = { prompt, tokens ->
                merging = true
                try {
                    summaries = onMerge(summaries, enabled, prompt, tokens)
                } finally {
                    merging = false
                }
            },
        )
    }
    confirmUndo?.let { mode ->
        val label = stringResource(if (mode == CompressionUndoMode.LATEST) R.string.compression_undo_latest else R.string.compression_undo_all)
        AlertDialog(
            onDismissRequest = { confirmUndo = null },
            title = { Text(label) },
            text = { Text(if (mode == CompressionUndoMode.LATEST)
                stringResource(R.string.compression_undo_latest_hint, conversation.latestCompressionUndoRestoredCount)
            else stringResource(R.string.compression_undo_all_hint, conversation.activeCompressedMessageNodeIds.size)) },
            confirmButton = { TextButton(onClick = { confirmUndo = null; save(mode) }) { Text(label) } },
            dismissButton = { TextButton(onClick = { confirmUndo = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

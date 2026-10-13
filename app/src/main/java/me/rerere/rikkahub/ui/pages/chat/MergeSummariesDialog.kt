package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

@Composable
internal fun MergeSummariesDialog(
    onDismiss: () -> Unit,
    onConfirm: suspend (String, Int) -> Unit,
) {
    var targetTokens by remember { mutableIntStateOf(2000) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val options = listOf(500, 1000, 2000, 4000)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.compression_merge_existing)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        RabbitLoadingIndicator(Modifier.size(32.dp))
                        Text(stringResource(R.string.compression_merging), textAlign = TextAlign.Center)
                    }
                } else {
                    Text(stringResource(R.string.compression_merge_existing_hint))
                    Text(stringResource(R.string.chat_page_compress_target_tokens))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = targetTokens == tokens,
                                onClick = { targetTokens = tokens },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            ) { Text("$tokens") }
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (!busy) {
                TextButton(onClick = {
                    busy = true
                    error = null
                    job = scope.launch {
                        try {
                            onConfirm(prompt, targetTokens)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (busy) job?.cancel() else onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

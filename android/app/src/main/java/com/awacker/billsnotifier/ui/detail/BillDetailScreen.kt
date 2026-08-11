package com.awacker.billsnotifier.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.domain.model.Occurrence
import com.awacker.billsnotifier.domain.model.OccurrenceStatus
import com.awacker.billsnotifier.domain.sync.describe
import com.awacker.billsnotifier.ui.BillsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailScreen(
    billId: String,
    viewModel: BillsViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val plan by viewModel.observePlan(billId).collectAsStateWithLifecycle(initialValue = null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val today = viewModel.today()
    val current = plan

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.bill?.name ?: "Plan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Read-only on a mirror: its copy is replaced by the next download, so
                    // an edit made here would be silently discarded.
                    if (!settings.isMirrorDevice) {
                        IconButton(onClick = { onEdit(billId) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = {
                            viewModel.archivePlan(billId)
                            onBack()
                        }) {
                            Icon(Icons.Default.Archive, contentDescription = "Archive")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            Text("Loading…", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${current.progress.paidCount} of ${current.progress.totalCount} paid",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (current.progress.totalCount == 0) 0f
                                else current.progress.paidCount.toFloat() / current.progress.totalCount
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StatRow("Schedule", current.bill.recurrence.describe())
                        StatRow("Each payment", Money.format(current.bill.installmentAmountCents))
                        StatRow("Remaining", Money.format(current.progress.remainingAmountCents))
                        StatRow("Paid so far", Money.format(current.progress.paidAmountCents))
                        current.progress.nextDueDate?.let {
                            StatRow("Next due", it.format(DATE_FORMAT))
                        }
                        current.progress.projectedPayoffDate?.let {
                            StatRow("Projected payoff", it.format(DATE_FORMAT))
                        }
                        current.bill.payee?.takeIf { it.isNotBlank() }?.let {
                            StatRow("Payee", it)
                        }
                        current.bill.notes?.takeIf { it.isNotBlank() }?.let {
                            StatRow("Notes", it)
                        }
                    }
                }
            }

            item {
                Text(
                    "Payments",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            items(current.occurrences, key = { it.id }) { occurrence ->
                OccurrenceRow(
                    occurrence = occurrence,
                    today = today,
                    canEdit = !settings.isMirrorDevice,
                    onTogglePaid = { viewModel.setPaid(occurrence, it) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OccurrenceRow(
    occurrence: Occurrence,
    today: LocalDate,
    canEdit: Boolean,
    onTogglePaid: (Boolean) -> Unit,
) {
    val status = occurrence.statusOn(today)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = occurrence.isPaid,
            onCheckedChange = onTogglePaid.takeIf { canEdit },
        )
        Column(Modifier.weight(1f)) {
            Text(
                "#${occurrence.sequence} · ${occurrence.dueDate.format(DATE_FORMAT)}",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (occurrence.isPaid) TextDecoration.LineThrough else null,
            )
            if (status == OccurrenceStatus.OVERDUE) {
                Text(
                    "Overdue",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (status == OccurrenceStatus.DUE_TODAY) {
                Text(
                    "Due today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(Money.format(occurrence.amountCents), style = MaterialTheme.typography.bodyMedium)
    }
}

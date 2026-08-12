package com.awacker.billsnotifier.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awacker.billsnotifier.data.PlanWithProgress
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.domain.schedule.PlanMath
import com.awacker.billsnotifier.ui.BillsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: BillsViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenPlan: (String) -> Unit,
    onAddPlan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val today = viewModel.today()

    val dueToday = plans.flatMap { plan ->
        PlanMath.dueOn(plan.occurrences, today).map { plan to it }
    }
    val overdue = plans.flatMap { plan ->
        PlanMath.overdueAsOf(plan.occurrences, today).map { plan to it }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Payment plans") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            // A mirror cannot create plans, so offering the button would only ever produce
            // a refusal.
            if (!settings.isMirrorDevice) {
                FloatingActionButton(onClick = onAddPlan) {
                    Icon(Icons.Default.Add, contentDescription = "Add a plan")
                }
            }
        },
    ) { padding ->
        if (plans.isEmpty()) {
            EmptyState(isMirror = settings.isMirrorDevice, modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (overdue.isNotEmpty()) {
                item {
                    HighlightCard(
                        title = if (overdue.size == 1) "1 payment overdue" else "${overdue.size} payments overdue",
                        total = overdue.sumOf { it.second.amountCents },
                        rows = overdue.map { (plan, occurrence) ->
                            // An overdue payment is nearly always one you have just settled
                            // and forgotten to tick off, so clearing it is worth a tap here
                            // rather than a trip into the plan. A mirror gets no button: its
                            // copy is replaced on the next download.
                            val payAction: (() -> Unit)? = if (settings.isMirrorDevice) {
                                null
                            } else {
                                { viewModel.payOccurrence(occurrence) }
                            }
                            CardRow(
                                text = "${plan.bill.name} — ${Money.format(occurrence.amountCents)} " +
                                    "(due ${occurrence.dueDate.format(DAY_FORMAT)})",
                                onPay = payAction,
                            )
                        },
                        container = MaterialTheme.colorScheme.errorContainer,
                        onContainer = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            item {
                if (dueToday.isEmpty()) {
                    Text(
                        "Nothing due today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    HighlightCard(
                        title = "Due today",
                        total = dueToday.sumOf { it.second.amountCents },
                        rows = dueToday.map { (plan, occurrence) ->
                            CardRow("${plan.bill.name} — ${Money.format(occurrence.amountCents)}")
                        },
                        container = MaterialTheme.colorScheme.primaryContainer,
                        onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            item {
                Text(
                    "Plans",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(plans, key = { it.bill.id }) { plan ->
                PlanCard(plan = plan, today = today, onClick = { onOpenPlan(plan.bill.id) })
            }
        }
    }
}

/** One line of a highlight card, optionally clearable in place. */
private data class CardRow(val text: String, val onPay: (() -> Unit)? = null)

@Composable
private fun HighlightCard(
    title: String,
    total: Long,
    rows: List<CardRow>,
    container: Color,
    onContainer: Color,
) {
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(Money.format(total), style = MaterialTheme.typography.titleMedium)
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    row.onPay?.let { pay ->
                        // The card sits on an error container, where a button's default
                        // primary colour is close to unreadable.
                        TextButton(
                            onClick = pay,
                            colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
                        ) {
                            Text("Mark paid")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: PlanWithProgress, today: LocalDate, onClick: () -> Unit) {
    val progress = plan.progress
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(plan.bill.name, style = MaterialTheme.typography.titleMedium)
                    plan.bill.payee?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    Money.format(plan.bill.installmentAmountCents),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            LinearProgressIndicator(
                progress = {
                    if (progress.totalCount == 0) 0f
                    else progress.paidCount.toFloat() / progress.totalCount
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            )

            Text(
                buildString {
                    append("${progress.paidCount} of ${progress.totalCount} paid")
                    if (!progress.isPaidOff) {
                        append(" · ${Money.format(progress.remainingAmountCents)} left")
                        progress.nextDueDate?.let { append(" · next ${it.format(DAY_FORMAT)}") }
                    } else {
                        append(" · paid off")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(isMirror: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No payment plans yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(4.dp))
        Text(
            if (isMirror) {
                "Nothing downloaded yet. Check the connection in Settings, then tap " +
                    "Download now."
            } else {
                "Add a plan and you'll get a notification each morning something is due."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

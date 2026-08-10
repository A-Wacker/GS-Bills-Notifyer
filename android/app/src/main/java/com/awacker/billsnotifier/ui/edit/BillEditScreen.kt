package com.awacker.billsnotifier.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.awacker.billsnotifier.domain.model.Bill
import com.awacker.billsnotifier.domain.model.Money
import com.awacker.billsnotifier.domain.model.PlanEnd
import com.awacker.billsnotifier.domain.model.Recurrence
import com.awacker.billsnotifier.domain.schedule.ScheduleGenerator
import com.awacker.billsnotifier.ui.BillsViewModel
import com.awacker.billsnotifier.ui.Routes
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PREVIEW_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

private enum class Cadence(val label: String) {
    WEEKLY("Weekly"),
    BIWEEKLY("Every 2 weeks"),
    MONTHLY("Monthly"),
    SEMI_MONTHLY("1st & 15th"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly"),
    ;

    fun toRecurrence(): Recurrence = when (this) {
        WEEKLY -> Recurrence.Weekly(1)
        BIWEEKLY -> Recurrence.Weekly(2)
        MONTHLY -> Recurrence.Monthly(1)
        SEMI_MONTHLY -> Recurrence.SemiMonthly(1, 15)
        QUARTERLY -> Recurrence.Monthly(3)
        YEARLY -> Recurrence.Yearly(1)
    }

    companion object {
        fun from(recurrence: Recurrence): Cadence = when (recurrence) {
            is Recurrence.Weekly -> if (recurrence.everyNWeeks == 2) BIWEEKLY else WEEKLY
            is Recurrence.Monthly -> if (recurrence.everyNMonths == 3) QUARTERLY else MONTHLY
            is Recurrence.SemiMonthly -> SEMI_MONTHLY
            is Recurrence.Yearly -> YEARLY
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillEditScreen(
    billId: String,
    viewModel: BillsViewModel,
    onBack: () -> Unit,
) {
    val isNew = billId == Routes.NEW_PLAN

    var name by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf(Cadence.MONTHLY) }
    var firstDueText by remember { mutableStateOf(LocalDate.now().toString()) }
    var countText by remember { mutableStateOf("12") }
    var autopay by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var resolvedId by remember { mutableStateOf(if (isNew) viewModel.newPlanId() else billId) }

    // Prefill when editing. Keyed on billId so it runs once per plan opened.
    LaunchedEffect(billId) {
        if (isNew) return@LaunchedEffect
        // first(), not collect(): this seeds the form once. Staying subscribed would
        // overwrite whatever the user had typed the next time the database emitted.
        viewModel.observePlan(billId).first()?.let { existing ->
            name = existing.bill.name
            payee = existing.bill.payee.orEmpty()
            amountText = Money.toDecimalString(existing.bill.installmentAmountCents)
            cadence = Cadence.from(existing.bill.recurrence)
            firstDueText = existing.bill.firstDueDate.toString()
            countText = (existing.bill.end as? PlanEnd.AfterPayments)?.count?.toString()
                ?: existing.occurrences.size.toString()
            autopay = existing.bill.autopay
            notes = existing.bill.notes.orEmpty()
            resolvedId = existing.bill.id
        }
    }

    val amountCents = Money.parseToCents(amountText)
    val firstDue = runCatching { LocalDate.parse(firstDueText) }.getOrNull()
    val count = countText.toIntOrNull()

    val draft: Bill? = if (name.isNotBlank() && amountCents != null && amountCents > 0 &&
        firstDue != null && count != null && count >= 1
    ) {
        Bill(
            id = resolvedId,
            name = name.trim(),
            payee = payee.trim().ifBlank { null },
            installmentAmountCents = amountCents,
            recurrence = cadence.toRecurrence(),
            firstDueDate = firstDue,
            end = PlanEnd.AfterPayments(count),
            autopay = autopay,
            notes = notes.trim().ifBlank { null },
        )
    } else {
        null
    }

    // Generating the real schedule for the preview means what you see here is exactly what
    // gets saved — including the awkward month-end behaviour, which is the whole point of
    // showing it before you commit.
    val preview = draft?.let { runCatching { ScheduleGenerator.generate(it) }.getOrNull() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New plan" else "Edit plan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text("Payee (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount per payment") },
                singleLine = true,
                isError = amountText.isNotBlank() && (amountCents == null || amountCents <= 0),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("How often", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Cadence.entries.forEach { option ->
                    FilterChip(
                        selected = cadence == option,
                        onClick = { cadence = option },
                        label = { Text(option.label) },
                    )
                }
            }

            OutlinedTextField(
                value = firstDueText,
                onValueChange = { firstDueText = it },
                label = { Text("First due date (yyyy-MM-dd)") },
                singleLine = true,
                isError = firstDue == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter(Char::isDigit) },
                label = { Text("Number of payments") },
                singleLine = true,
                isError = count == null || count < 1,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Paid automatically")
                Switch(checked = autopay, onCheckedChange = { autopay = it })
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (preview != null && preview.isNotEmpty()) {
                SchedulePreview(preview.map { it.dueDate }, preview.size, preview.sumOf { it.amountCents })
            }

            Button(
                onClick = { draft?.let { viewModel.savePlan(it) { onBack() } } },
                enabled = draft != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isNew) "Create plan" else "Save changes")
            }
        }
    }
}

@Composable
private fun SchedulePreview(dates: List<LocalDate>, total: Int, totalCents: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Schedule preview", fontWeight = FontWeight.SemiBold)
            Text(
                "$total payments, ${Money.format(totalCents)} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dates.take(5).forEach {
                Text("• ${it.format(PREVIEW_FORMAT)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (dates.size > 5) {
                Text(
                    "…ending ${dates.last().format(PREVIEW_FORMAT)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

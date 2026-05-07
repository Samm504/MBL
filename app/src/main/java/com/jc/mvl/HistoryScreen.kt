package com.jc.mvl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HistoryScreen(
    entries: List<WorkDayEntry>,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var selectedEntry by remember { mutableStateOf<WorkDayEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (selectedEntry != null) {
        HistoryDetailScreen(
            entry = selectedEntry!!,
            onBack = { selectedEntry = null },
            onDelete = {
                onDelete(selectedEntry!!.id)
                selectedEntry = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (entries.isNotEmpty()) {
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear All",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No history yet.\nComplete a work day and it will appear here.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { entry ->
                    HistoryListItem(
                        entry = entry,
                        onClick = { selectedEntry = entry }
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All History") },
            text = { Text("This will permanently delete all saved work day records. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearConfirm = false
                }) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HistoryListItem(entry: WorkDayEntry, onClick: () -> Unit) {
    val overtimeMin = entry.overtimeMinutes()
    val underMin = entry.underMinutes()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = entry.date,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${entry.signIn.format(entry.formatter)} → ${entry.signOut.format(entry.formatter)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Work: ${entry.formatDuration(entry.totalWorkMinutes)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (overtimeMin > 0) {
                    Text(
                        text = "+${entry.formatDuration(overtimeMin)} OT",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (underMin > 0) {
                    Text(
                        text = "-${entry.formatDuration(underMin)} short",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(
    entry: WorkDayEntry,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val overtimeMin = entry.overtimeMinutes()
    val underMin = entry.underMinutes()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = entry.date,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailRow("Signed In", entry.signIn.format(entry.formatter))
                DetailRow("Signed Out", entry.signOut.format(entry.formatter))
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DetailRow("Total Work Time", entry.formatDuration(entry.totalWorkMinutes))
                DetailRow("Total Break Time", entry.formatDuration(entry.totalBreakMinutes))
                if (overtimeMin > 0) {
                    DetailRow(
                        label = "Overtime",
                        value = "+${entry.formatDuration(overtimeMin)}",
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                } else if (underMin > 0) {
                    DetailRow(
                        label = "Undertime",
                        value = "-${entry.formatDuration(underMin)}",
                        valueColor = MaterialTheme.colorScheme.error
                    )
                }
                if (entry.officeMode) {
                    Text(
                        text = "Office Mode was ON",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Breaks card
        if (entry.breaks.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Breaks",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entry.breaks.forEachIndexed { index, b ->
                        val duration = java.time.Duration.between(b.stepOut, b.stepIn).toMinutes()
                        Text(
                            text = "Break ${index + 1}: ${b.stepOut.format(entry.formatter)} → ${b.stepIn.format(entry.formatter)}  (${entry.formatDuration(duration)})",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry") },
            text = { Text("Delete this work day record? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
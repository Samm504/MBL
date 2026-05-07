package com.jc.mvl

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jc.mvl.ui.theme.MVLTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ComponentActivity.dataStore by preferencesDataStore(name = "mvl_cache")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 0)
        }

        setContent {
            var isDarkMode by remember { mutableStateOf(false) }

            MVLTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MvlApp(
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { isDarkMode = !isDarkMode }
                    )
                }
            }
        }
    }
}

fun parseSpokenTime(input: String): LocalTime? {
    val text = input.lowercase(Locale.getDefault()).trim()

    val isPM = text.contains("pm") || text.contains("p.m")
    val isAM = text.contains("am") || text.contains("a.m")

    val wordNumbers = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "o'clock" to 0, "oclock" to 0
    )

    var normalized = text
    wordNumbers.forEach { (word, num) -> normalized = normalized.replace(word, num.toString()) }
    normalized = normalized.replace(Regex("[^0-9 :]"), " ").trim()
    val parts = normalized.split(Regex("\\s+|:")).filter { it.isNotEmpty() }

    var hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

    if (isPM && hour != 12) hour += 12
    if (isAM && hour == 12) hour = 0

    return try {
        LocalTime.of(hour, minute)
    } catch (e: Exception) {
        null
    }
}

// Sealed class representing every reversible action
sealed class AppAction {
    object SignIn : AppAction()
    object StepOut : AppAction()
    object StepBack : AppAction()
    object SignOut : AppAction()
}

@Composable
fun MvlApp(
    isDarkMode: Boolean = false,
    onToggleDarkMode: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val dataStore = activity.dataStore
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")
    val clockFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a")
    val historyRepo = remember { HistoryRepository(context) }

    // Tab state: 0 = Home, 1 = History
    var selectedTab by remember { mutableIntStateOf(0) }

    var manualMode by remember { mutableStateOf(false) }
    var officeMode by remember { mutableStateOf(false) }

    var signInTime by remember { mutableStateOf<LocalDateTime?>(null) }
    var signOutTime by remember { mutableStateOf<LocalDateTime?>(null) }
    val breaks = remember { mutableStateListOf<Pair<LocalDateTime, LocalDateTime>>() }
    var lastStepOut by remember { mutableStateOf<LocalDateTime?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    var officeStepOutEstimate by remember { mutableStateOf<String?>(null) }
    var signOutEstimate by remember { mutableStateOf<String?>(null) }
    var selectedManualTime by remember { mutableStateOf<LocalTime?>(null) }

    // Undo stack
    val actionHistory = remember { mutableStateListOf<AppAction>() }

    // History entries
    var historyEntries by remember { mutableStateOf<List<WorkDayEntry>>(emptyList()) }

    // Live clock
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000L)
        }
    }

    val keySignIn = stringPreferencesKey("sign_in")
    val keySignOut = stringPreferencesKey("sign_out")
    val keyBreaks = stringPreferencesKey("breaks")
    val keyLastStepOut = stringPreferencesKey("last_step_out")
    val keyResult = stringPreferencesKey("result")
    val keyOfficeMode = stringPreferencesKey("office_mode")

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spokenText = matches?.firstOrNull()
        if (spokenText != null) {
            val parsed = parseSpokenTime(spokenText)
            if (parsed != null) {
                selectedManualTime = parsed
                Toast.makeText(context, "Time set to ${parsed.format(formatter)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Couldn't parse \"$spokenText\". Try \"8 30 AM\".", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "No result. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val prefs = dataStore.data.first()
        prefs[keySignIn]?.takeIf { it.isNotEmpty() }?.let { signInTime = LocalDateTime.parse(it) }
        prefs[keySignOut]?.takeIf { it.isNotEmpty() }?.let { signOutTime = LocalDateTime.parse(it) }
        prefs[keyBreaks]?.takeIf { it.isNotEmpty() }?.split(";")?.forEach {
            val parts = it.split(",")
            if (parts.size == 2) breaks.add(LocalDateTime.parse(parts[0]) to LocalDateTime.parse(parts[1]))
        }
        prefs[keyLastStepOut]?.takeIf { it.isNotEmpty() }?.let { lastStepOut = LocalDateTime.parse(it) }
        prefs[keyResult]?.takeIf { it.isNotEmpty() }?.let { result = it }
        officeMode = prefs[keyOfficeMode]?.toBoolean() ?: false

        historyEntries = historyRepo.loadEntries()
    }

    fun saveCache() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[keySignIn] = signInTime?.toString() ?: ""
                prefs[keySignOut] = signOutTime?.toString() ?: ""
                prefs[keyBreaks] = breaks.joinToString(";") { "${it.first},${it.second}" }
                prefs[keyLastStepOut] = lastStepOut?.toString() ?: ""
                prefs[keyResult] = result ?: ""
                prefs[keyOfficeMode] = officeMode.toString()
            }
        }
    }

    fun clearCache() {
        scope.launch { dataStore.edit { it.clear() } }
    }

    fun calculateTotalBreakMinutes(): Long {
        var total = 0L
        breaks.forEach { total += Duration.between(it.first, it.second).toMinutes() }
        return total
    }

    fun calculateSignOutTime(): String {
        if (signInTime == null) return "Please sign in first"
        return signInTime!!.plusMinutes(9 * 60L + calculateTotalBreakMinutes()).format(formatter)
    }

    fun calculateOfficeStepOutTime(): String {
        if (signInTime == null) return ""
        return signInTime!!.plusHours(4).format(formatter)
    }

    fun calculateSignOutEstimate(): String {
        if (signInTime == null) return ""
        return signInTime!!.plusMinutes(9 * 60L + calculateTotalBreakMinutes()).format(formatter)
    }

    // Overtime calculation: actual worked minutes vs required 9h
    fun calculateOvertimeMinutes(): Long {
        if (signInTime == null || signOutTime == null) return 0L
        val actualWorked = Duration.between(signInTime, signOutTime).toMinutes() - calculateTotalBreakMinutes()
        return maxOf(0L, actualWorked - 9 * 60L)
    }

    fun formatDuration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // Save completed day to history
    fun saveToHistory() {
        val sIn = signInTime ?: return
        val sOut = signOutTime ?: return
        val totalBreak = calculateTotalBreakMinutes()
        val totalWork = Duration.between(sIn, sOut).toMinutes() - totalBreak
        val dateStr = sIn.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))

        val entry = WorkDayEntry(
            id = sIn.toString(),
            date = dateStr,
            signIn = sIn,
            signOut = sOut,
            breaks = breaks.map { BreakEntry(it.first, it.second) },
            totalBreakMinutes = totalBreak,
            totalWorkMinutes = totalWork,
            officeMode = officeMode
        )
        scope.launch {
            historyRepo.saveEntry(entry)
            historyEntries = historyRepo.loadEntries()
        }
    }

    // Share summary
    fun shareSummary() {
        val sIn = signInTime ?: return
        val sOut = signOutTime ?: return
        val totalBreak = calculateTotalBreakMinutes()
        val totalWork = Duration.between(sIn, sOut).toMinutes() - totalBreak
        val overtime = calculateOvertimeMinutes()
        val dateStr = sIn.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))

        val sb = StringBuilder()
        sb.appendLine("📋 Work Summary — $dateStr")
        sb.appendLine()
        sb.appendLine("🟢 Sign In:   ${sIn.format(formatter)}")
        sb.appendLine("🔴 Sign Out:  ${sOut.format(formatter)}")
        sb.appendLine()
        if (breaks.isNotEmpty()) {
            sb.appendLine("☕ Breaks:")
            breaks.forEachIndexed { i, b ->
                val dur = Duration.between(b.first, b.second).toMinutes()
                sb.appendLine("  Break ${i + 1}: ${b.first.format(formatter)} → ${b.second.format(formatter)} (${formatDuration(dur)})")
            }
            sb.appendLine()
        }
        sb.appendLine("⏱ Total Break:  ${formatDuration(totalBreak)}")
        sb.appendLine("💼 Total Work:   ${formatDuration(totalWork)}")
        if (overtime > 0) {
            sb.appendLine("⚡ Overtime:     +${formatDuration(overtime)}")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Work Summary"))
    }

    // Undo last action
    fun undoLastAction() {
        if (actionHistory.isEmpty()) return
        when (actionHistory.last()) {
            is AppAction.SignIn -> {
                signInTime = null
                officeStepOutEstimate = null
                signOutEstimate = null
            }
            is AppAction.StepOut -> {
                lastStepOut = null
            }
            is AppAction.StepBack -> {
                if (breaks.isNotEmpty()) {
                    val last = breaks.last()
                    breaks.removeAt(breaks.lastIndex)
                    lastStepOut = last.first
                    signOutEstimate = calculateSignOutEstimate()
                }
            }
            is AppAction.SignOut -> {
                signOutTime = null
                result = null
            }
        }
        actionHistory.removeAt(actionHistory.lastIndex)
        saveCache()
    }

    fun getChosenTime(): LocalDateTime? {
        return if (manualMode) {
            selectedManualTime?.let {
                LocalDateTime.now().withHour(it.hour).withMinute(it.minute)
            } ?: run {
                Toast.makeText(context, "Please pick a time first", Toast.LENGTH_SHORT).show()
                null
            }
        } else {
            LocalDateTime.now()
        }
    }

    fun showTimePicker() {
        val now = LocalTime.now()
        TimePickerDialog(
            context,
            { _, hour, minute -> selectedManualTime = LocalTime.of(hour, minute) },
            now.hour, now.minute, false
        ).show()
    }

    fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Microphone permission required.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a time, e.g. \"8 30 AM\"")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Voice input not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ROOT UI with bottom nav ---
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.History, contentDescription = "History") },
                    label = { Text("History") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeScreen(
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = onToggleDarkMode,
                    currentTime = currentTime,
                    clockFormatter = clockFormatter,
                    formatter = formatter,
                    manualMode = manualMode,
                    onManualModeChange = { manualMode = it },
                    officeMode = officeMode,
                    onOfficeModeChange = { newVal ->
                        officeMode = newVal
                        if (signInTime != null) {
                            officeStepOutEstimate = calculateOfficeStepOutTime()
                            signOutEstimate = calculateSignOutEstimate()
                        }
                        saveCache()
                    },
                    selectedManualTime = selectedManualTime,
                    onShowTimePicker = { showTimePicker() },
                    onStartVoiceInput = { startVoiceInput() },
                    signInTime = signInTime,
                    signOutTime = signOutTime,
                    lastStepOut = lastStepOut,
                    breaks = breaks,
                    result = result,
                    officeStepOutEstimate = officeStepOutEstimate,
                    signOutEstimate = signOutEstimate,
                    actionHistory = actionHistory,
                    onSignIn = {
                        val time = getChosenTime()
                        time?.let {
                            signInTime = it
                            officeStepOutEstimate = if (officeMode) calculateOfficeStepOutTime() else null
                            signOutEstimate = calculateSignOutEstimate()
                            actionHistory.add(AppAction.SignIn)
                            saveCache()
                        }
                    },
                    onStepOut = {
                        val time = getChosenTime()
                        val canStepOut = manualMode ||
                                (officeMode && Duration.between(signInTime, time).toHours() >= 4) ||
                                !officeMode
                        if (canStepOut) {
                            lastStepOut = time
                            signOutEstimate = calculateSignOutEstimate()
                            actionHistory.add(AppAction.StepOut)
                            saveCache()
                        } else {
                            Toast.makeText(context, "You can only step out after 4 hours in office.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStepBack = {
                        val time = getChosenTime()
                        time?.let {
                            breaks.add(Pair(lastStepOut!!, it))
                            lastStepOut = null
                            signOutEstimate = calculateSignOutEstimate()
                            actionHistory.add(AppAction.StepBack)
                            saveCache()
                        }
                    },
                    onSignOut = {
                        val time = getChosenTime()
                        time?.let {
                            signOutTime = it
                            result = calculateSignOutTime()
                            actionHistory.add(AppAction.SignOut)
                            saveToHistory()
                            saveCache()
                        }
                    },
                    onUndo = { undoLastAction() },
                    onShare = { shareSummary() },
                    onReset = {
                        signInTime = null
                        signOutTime = null
                        breaks.clear()
                        lastStepOut = null
                        result = null
                        officeStepOutEstimate = null
                        signOutEstimate = null
                        actionHistory.clear()
                        clearCache()
                    },
                    overtimeMinutes = calculateOvertimeMinutes(),
                    formatDuration = { formatDuration(it) }
                )
                1 -> HistoryScreen(
                    entries = historyEntries,
                    onDelete = { id ->
                        scope.launch {
                            historyRepo.deleteEntry(id)
                            historyEntries = historyRepo.loadEntries()
                        }
                    },
                    onClearAll = {
                        scope.launch {
                            historyRepo.clearAll()
                            historyEntries = emptyList()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    currentTime: LocalTime,
    clockFormatter: DateTimeFormatter,
    formatter: DateTimeFormatter,
    manualMode: Boolean,
    onManualModeChange: (Boolean) -> Unit,
    officeMode: Boolean,
    onOfficeModeChange: (Boolean) -> Unit,
    selectedManualTime: LocalTime?,
    onShowTimePicker: () -> Unit,
    onStartVoiceInput: () -> Unit,
    signInTime: LocalDateTime?,
    signOutTime: LocalDateTime?,
    lastStepOut: LocalDateTime?,
    breaks: List<Pair<LocalDateTime, LocalDateTime>>,
    result: String?,
    officeStepOutEstimate: String?,
    signOutEstimate: String?,
    actionHistory: List<AppAction>,
    onSignIn: () -> Unit,
    onStepOut: () -> Unit,
    onStepBack: () -> Unit,
    onSignOut: () -> Unit,
    onUndo: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    overtimeMinutes: Long,
    formatDuration: (Long) -> String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title row with dark mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Made by Lab",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Undo button
                    if (actionHistory.isNotEmpty() && signOutTime == null) {
                        IconButton(onClick = onUndo) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "Undo",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onToggleDarkMode) {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Live clock
            Text(
                text = currentTime.format(clockFormatter),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Toggles section
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Manual Time Entry", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = manualMode, onCheckedChange = onManualModeChange)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Office Mode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = officeMode, onCheckedChange = onOfficeModeChange)
                    }
                    if (manualMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onShowTimePicker,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Pick Time") }
                            Button(
                                onClick = onStartVoiceInput,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("🎙️ Voice") }
                        }
                        selectedManualTime?.let {
                            Text(
                                "Selected Time: ${it.format(formatter)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Actions section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (signInTime == null) {
                        Button(onClick = onSignIn) { Text("Sign In") }
                    } else if (signOutTime == null) {
                        if (lastStepOut == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onStepOut) { Text("Step Out") }
                                Button(onClick = onSignOut) { Text("Sign Out") }
                            }
                        } else {
                            Button(onClick = onStepBack) { Text("Back") }
                        }
                    }
                }
            }

            // Info Section
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
                    signInTime?.let {
                        Text("Signed In: ${it.format(formatter)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    officeStepOutEstimate?.let {
                        Text("You may step out after: $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    lastStepOut?.let {
                        Text("Currently Stepped Out: ${it.format(formatter)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (breaks.isNotEmpty()) {
                        Text("Breaks:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        breaks.forEachIndexed { index, pair ->
                            Text(
                                "  Break ${index + 1}: ${pair.first.format(formatter)} - ${pair.second.format(formatter)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    signOutTime?.let {
                        Text("Signed Out: ${it.format(formatter)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result?.let {
                        Text("Final Sign Out: $it", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Overtime indicator
                    if (overtimeMinutes > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "⚡ Overtime: +${formatDuration(overtimeMinutes)}",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Reset + Share buttons (only after sign out)
            if (signOutTime != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) { Text("Share Summary") }

                    Button(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Reset Day") }
                }
            }
        }

        // Bottom estimated sign out card
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Estimated Sign Out Time",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = signOutEstimate ?: "--:--",
                    fontSize = 34.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
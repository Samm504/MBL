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
            // Dark mode state lives here so it wraps MVLTheme
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
        "forty" to 40, "fifty" to 50, "o'clock" to 0, "o'clock" to 0
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

    fun calculateSignOutTime(): String {
        if (signInTime == null) return "Please sign in first"
        var totalBreakMinutes = 0L
        breaks.forEach { totalBreakMinutes += Duration.between(it.first, it.second).toMinutes() }
        return signInTime!!.plusMinutes(9 * 60L + totalBreakMinutes).format(formatter)
    }

    fun calculateOfficeStepOutTime(): String {
        if (signInTime == null) return ""
        return signInTime!!.plusHours(4).format(formatter)
    }

    fun calculateSignOutEstimate(): String {
        if (signInTime == null) return ""
        var totalBreakMinutes = 0L
        breaks.forEach { totalBreakMinutes += Duration.between(it.first, it.second).toMinutes() }
        return signInTime!!.plusMinutes(9 * 60L + totalBreakMinutes).format(formatter)
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

    // --- UI ---
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
                IconButton(onClick = onToggleDarkMode) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = if (isDarkMode) "Switch to Light Mode" else "Switch to Dark Mode",
                        tint = MaterialTheme.colorScheme.primary
                    )
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
                        Text(
                            "Manual Time Entry",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(checked = manualMode, onCheckedChange = { manualMode = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Office Mode",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(checked = officeMode, onCheckedChange = {
                            officeMode = it
                            if (signInTime != null) {
                                officeStepOutEstimate = calculateOfficeStepOutTime()
                                signOutEstimate = calculateSignOutEstimate()
                            }
                            saveCache()
                        })
                    }

                    if (manualMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showTimePicker() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Pick Time") }

                            Button(
                                onClick = { startVoiceInput() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🎙️ Voice")
                            }
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
                        Button(onClick = {
                            val time = getChosenTime()
                            time?.let {
                                signInTime = it
                                officeStepOutEstimate = if (officeMode) calculateOfficeStepOutTime() else null
                                signOutEstimate = calculateSignOutEstimate()
                                saveCache()
                            }
                        }) { Text("Sign In") }
                    } else if (signOutTime == null) {
                        if (lastStepOut == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    val time = getChosenTime()
                                    val canStepOut = manualMode ||
                                            (officeMode && Duration.between(signInTime, time).toHours() >= 4) ||
                                            !officeMode
                                    if (canStepOut) {
                                        lastStepOut = time
                                        signOutEstimate = calculateSignOutEstimate()
                                        saveCache()
                                    } else {
                                        Toast.makeText(context, "You can only step out after 4 hours in office.", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("Step Out") }

                                Button(onClick = {
                                    val time = getChosenTime()
                                    time?.let {
                                        signOutTime = it
                                        result = calculateSignOutTime()
                                        saveCache()
                                    }
                                }) { Text("Sign Out") }
                            }
                        } else {
                            Button(onClick = {
                                val time = getChosenTime()
                                time?.let {
                                    breaks.add(Pair(lastStepOut!!, it))
                                    lastStepOut = null
                                    signOutEstimate = calculateSignOutEstimate()
                                    saveCache()
                                }
                            }) { Text("Back") }
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
                        Text(
                            "Signed In: ${it.format(formatter)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    officeStepOutEstimate?.let {
                        Text(
                            "You may step out after: $it",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    lastStepOut?.let {
                        Text(
                            "Currently Stepped Out: ${it.format(formatter)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (breaks.isNotEmpty()) {
                        Text(
                            "Breaks:",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        breaks.forEachIndexed { index, pair ->
                            Text(
                                "  Break ${index + 1}: ${pair.first.format(formatter)} - ${pair.second.format(formatter)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    signOutTime?.let {
                        Text(
                            "Signed Out: ${it.format(formatter)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    result?.let {
                        Text(
                            "Final Sign Out: $it",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (signOutTime != null) {
                Button(
                    onClick = {
                        signInTime = null
                        signOutTime = null
                        breaks.clear()
                        lastStepOut = null
                        result = null
                        officeStepOutEstimate = null
                        signOutEstimate = null
                        clearCache()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Reset Day") }
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
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "   Estimated Sign Out Time",
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
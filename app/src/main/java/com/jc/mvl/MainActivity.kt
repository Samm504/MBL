package com.jc.mvl

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jc.mvl.ui.theme.MVLTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val ComponentActivity.dataStore by preferencesDataStore(name = "mvl_cache")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MVLTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MvlApp(this)
                }
            }
        }
    }
}

@Composable
fun MvlApp(activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = activity.dataStore
    val formatter = DateTimeFormatter.ofPattern("hh:mm a")

    var manualMode by remember { mutableStateOf(false) }

    var signInTime by remember { mutableStateOf<LocalDateTime?>(null) }
    var signOutTime by remember { mutableStateOf<LocalDateTime?>(null) }
    val breaks = remember { mutableStateListOf<Pair<LocalDateTime, LocalDateTime>>() }
    var lastStepOut by remember { mutableStateOf<LocalDateTime?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    // Manual selected time
    var selectedManualTime by remember { mutableStateOf<LocalTime?>(null) }

    // DataStore keys
    val keySignIn = stringPreferencesKey("sign_in")
    val keySignOut = stringPreferencesKey("sign_out")
    val keyBreaks = stringPreferencesKey("breaks")
    val keyLastStepOut = stringPreferencesKey("last_step_out")
    val keyResult = stringPreferencesKey("result")

    // Load cached data
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
    }

    // Save cache
    fun saveCache() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[keySignIn] = signInTime?.toString() ?: ""
                prefs[keySignOut] = signOutTime?.toString() ?: ""
                prefs[keyBreaks] = breaks.joinToString(";") { "${it.first},${it.second}" }
                prefs[keyLastStepOut] = lastStepOut?.toString() ?: ""
                prefs[keyResult] = result ?: ""
            }
        }
    }

    // Clear cache
    fun clearCache() {
        scope.launch { dataStore.edit { it.clear() } }
    }

    fun calculateSignOutTime(): String {
        if (signInTime == null) return "Please sign in first"
        var totalBreakMinutes = 0L
        breaks.forEach {
            totalBreakMinutes += Duration.between(it.first, it.second).toMinutes()
        }
        val totalWorkMinutes = 9 * 60L
        val adjustedSignOut = signInTime!!.plusMinutes(totalWorkMinutes + totalBreakMinutes)
        return adjustedSignOut.format(formatter)
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
            { _, hour, minute ->
                selectedManualTime = LocalTime.of(hour, minute)
            },
            now.hour,
            now.minute,
            false
        ).show()
    }

    // --- UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Made by Lab",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Manual Time Entry")
            Switch(checked = manualMode, onCheckedChange = { manualMode = it })
        }

        if (manualMode) {
            Button(onClick = { showTimePicker() }) {
                Text("Pick Time")
            }
            selectedManualTime?.let {
                Text("Selected Time: ${it.format(formatter)}")
            }
        }

        if (signInTime == null) {
            Button(onClick = {
                val time = getChosenTime()
                time?.let {
                    signInTime = it
                    saveCache()
                }
            }) { Text("Sign In") }
        } else if (signOutTime == null) {
            if (lastStepOut == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val time = getChosenTime()
                        time?.let {
                            lastStepOut = it
                            saveCache()
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val time = getChosenTime()
                        time?.let {
                            breaks.add(Pair(lastStepOut!!, it))
                            lastStepOut = null
                            saveCache()
                        }
                    }) { Text("Back") }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        signInTime?.let {
            Text("Signed In: ${it.format(formatter)}")
        }

        if (lastStepOut != null) {
            Text("Currently Stepped Out: ${lastStepOut!!.format(formatter)}")
        }

        if (breaks.isNotEmpty()) {
            Text("Breaks:")
            breaks.forEachIndexed { index, pair ->
                Text("Break ${index + 1}: ${pair.first.format(formatter)} - ${pair.second.format(formatter)}")
            }
        }

        signOutTime?.let {
            Text("Signed Out: ${it.format(formatter)}")
        }

        result?.let {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("You can sign out at:", fontWeight = FontWeight.Medium)
            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        if (signOutTime != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                signInTime = null
                signOutTime = null
                breaks.clear()
                lastStepOut = null
                result = null
                clearCache()
            }) {
                Text("Reset Day")
            }
        }
    }
}
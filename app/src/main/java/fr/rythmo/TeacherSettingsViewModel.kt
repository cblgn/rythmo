package fr.rythmo

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

class TeacherSettingsViewModel(loadLock: () -> LocalTeacherLock) : ViewModel() {
    private var lock: LocalTeacherLock? = null
    private var generation = 0
    private var navigationRestored = false
    var ready by mutableStateOf(false); private set
    var configured by mutableStateOf(false); private set
    var setupComplete by mutableStateOf(false); private set
    var unlocked by mutableStateOf(false); private set
    var requested by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var recoveryCode by mutableStateOf<String?>(null); private set
    var stopServerRequested by mutableStateOf(false); private set
    fun requestServerStop() { stopServerRequested = true; requestSettings() }
    fun cancelServerStop() { stopServerRequested = false }
    var individual by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            try {
                lock = withContext(Dispatchers.IO) { loadLock() }
                configured = requireNotNull(lock).configured
                setupComplete = requireNotNull(lock).recoveryAcknowledged
                ready = true
            } catch (e: Exception) { error = e.message ?: "Protection locale inaccessible." }
        }
    }

    fun requestSettings() { requested = true; error = null }
    fun leaveSettings() { stopServerRequested = false; generation++; unlocked = false; requested = false; error = null }
    fun background() { leaveSettings() }
    fun requireUnlocked() { check(configured && setupComplete && unlocked && recoveryCode == null) { "Déverrouillez les réglages professeur." } }
    fun openIndividual() { requireUnlocked(); individual = true; leaveSettings() }
    fun returnToGroup() { individual = false; leaveSettings() }
    fun restoreNavigation(resumeIndividual: Boolean) {
        if (!navigationRestored) { individual = resumeIndividual; navigationRestored = true }
    }
    fun acknowledgeRecovery() {
        if (!unlocked || busy || recoveryCode == null) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(lock).acknowledgeRecovery() }
                setupComplete = true; recoveryCode = null; leaveSettings()
            } catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }

    fun submit(pin: String, confirmation: String = "", recovery: String? = null) {
        if (busy || !ready) return
        val attemptGeneration = generation
        busy = true; error = null
        viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    val protection = requireNotNull(lock)
                    when {
                        !protection.configured -> protection.initialize(pin, confirmation)
                        recovery != null -> protection.recover(recovery, pin, confirmation)
                        else -> {
                            check(protection.unlock(pin)) { "PIN incorrect." }
                            if (!protection.recoveryAcknowledged) protection.renewUnconfirmedRecovery() else null
                        }
                    }
                }
                configured = true
                setupComplete = requireNotNull(lock).recoveryAcknowledged
                recoveryCode = code
                if (attemptGeneration == generation) { unlocked = true; requested = true }
            } catch (e: Exception) { error = e.message ?: "Vérification impossible." }
            finally { busy = false }
        }
    }
}

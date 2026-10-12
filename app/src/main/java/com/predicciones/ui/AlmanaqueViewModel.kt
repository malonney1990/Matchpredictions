package com.predicciones.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.predicciones.app.PredictionApp
import com.predicciones.data.Fixture
import com.predicciones.data.MatchesSource
import com.predicciones.engine.MatchPrediction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

sealed interface DetailState {
    val fixture: Fixture

    data class Loading(override val fixture: Fixture) : DetailState
    data class Ready(
        override val fixture: Fixture,
        val prediction: MatchPrediction,
        val notes: List<String>,
    ) : DetailState
    data class Error(override val fixture: Fixture, val message: String) : DetailState
}

class AlmanaqueViewModel(app: Application) : AndroidViewModel(app) {
    private val pApp = getApplication<PredictionApp>()
    private val repo = pApp.repository
    private val source: MatchesSource = pApp.matchesSource

    private val _date = MutableStateFlow(LocalDate.now(ZoneOffset.UTC))
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    private val _fixtures = MutableStateFlow<List<Fixture>>(emptyList())
    val fixtures: StateFlow<List<Fixture>> = _fixtures.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadFixtures()
    }

    fun shiftDays(days: Long) {
        _date.value = _date.value.plusDays(days)
        loadFixtures()
    }

    fun today() {
        _date.value = LocalDate.now(ZoneOffset.UTC)
        loadFixtures()
    }

    private fun loadFixtures() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _fixtures.value = source.fixtures(_date.value.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _fixtures.value = emptyList()
                _error.value = e.message ?: "No se pudieron cargar los partidos."
            } finally {
                _loading.value = false
            }
        }
    }

    fun open(f: Fixture) {
        _detail.value = DetailState.Loading(f)
        viewModelScope.launch {
            _detail.value = try {
                val request = source.predictionRequest(f)
                DetailState.Ready(f, repo.predictAndSave(request), request.notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetailState.Error(f, e.message ?: "error desconocido")
            }
        }
    }

    fun close() {
        _detail.value = null
    }

    fun openSettings() {
        _showSettings.value = true
    }

    fun closeSettings() {
        _showSettings.value = false
    }

    fun footballDataKey(): String = pApp.keys.footballDataKey

    fun oddsKey(): String = pApp.keys.oddsApiKey

    fun credits(): Int = pApp.keys.oddsCreditsRemaining

    fun saveKeys(footballData: String, odds: String) {
        pApp.keys.footballDataKey = footballData
        pApp.keys.oddsApiKey = odds
        _showSettings.value = false
        loadFixtures()
    }
}

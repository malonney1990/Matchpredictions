package com.predicciones.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.predicciones.app.PredictionApp
import com.predicciones.data.Fixture
import com.predicciones.data.MatchesSource
import com.predicciones.data.SampleMatchesSource
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
    data class Ready(override val fixture: Fixture, val prediction: MatchPrediction) : DetailState
    data class Error(override val fixture: Fixture, val message: String) : DetailState
}

class AlmanaqueViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<PredictionApp>().repository

    // Cambiar por la implementación con APIs reales cuando esté lista.
    private val source: MatchesSource = SampleMatchesSource()

    private val _date = MutableStateFlow(LocalDate.now(ZoneOffset.UTC))
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    private val _fixtures = MutableStateFlow<List<Fixture>>(emptyList())
    val fixtures: StateFlow<List<Fixture>> = _fixtures.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

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
            _fixtures.value = source.fixtures(_date.value.toString())
        }
    }

    fun open(f: Fixture) {
        _detail.value = DetailState.Loading(f)
        viewModelScope.launch {
            _detail.value = try {
                val request = source.predictionRequest(f)
                DetailState.Ready(f, repo.predictAndSave(request))
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
}

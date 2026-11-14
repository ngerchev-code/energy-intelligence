package com.ngerchev.energy

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ngerchev.energy.data.EnergyRepository
import com.ngerchev.energy.data.MarketData
import com.ngerchev.energy.logic.BessInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = true,
    val data: MarketData? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = EnergyRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _bess = MutableStateFlow(loadBess())
    val bess: StateFlow<BessInput> = _bess.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val d = repo.load()
                _state.value = UiState(loading = false, data = d)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Грешка при зареждане") }
            }
        }
    }

    fun updateBess(input: BessInput) {
        _bess.value = input
        prefs.edit()
            .putFloat("p", input.powerMw.toFloat())
            .putFloat("c", input.capacityMwh.toFloat())
            .putFloat("eff", input.roundTripEff.toFloat())
            .putFloat("dod", input.depthOfDischarge.toFloat())
            .putInt("cyc", input.cyclesPerDay)
            .putFloat("capex", input.capexEurPerKwh.toFloat())
            .putFloat("opex", input.opexEurPerMwYear.toFloat())
            .apply()
    }

    private fun loadBess(): BessInput {
        val d = BessInput()
        return BessInput(
            powerMw = prefs.getFloat("p", d.powerMw.toFloat()).toDouble(),
            capacityMwh = prefs.getFloat("c", d.capacityMwh.toFloat()).toDouble(),
            roundTripEff = prefs.getFloat("eff", d.roundTripEff.toFloat()).toDouble(),
            depthOfDischarge = prefs.getFloat("dod", d.depthOfDischarge.toFloat()).toDouble(),
            cyclesPerDay = prefs.getInt("cyc", d.cyclesPerDay),
            capexEurPerKwh = prefs.getFloat("capex", d.capexEurPerKwh.toFloat()).toDouble(),
            opexEurPerMwYear = prefs.getFloat("opex", d.opexEurPerMwYear.toFloat()).toDouble(),
        )
    }
}

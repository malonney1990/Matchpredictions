package com.predicciones.data

import android.content.Context

/** Guarda las claves de API solo en el teléfono (el código del repositorio es público: nunca van ahí). */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)

    var footballDataKey: String
        get() = prefs.getString("football_data", "") ?: ""
        set(value) {
            prefs.edit().putString("football_data", value.trim()).apply()
        }

    var oddsApiKey: String
        get() = prefs.getString("odds_api", "") ?: ""
        set(value) {
            prefs.edit().putString("odds_api", value.trim()).apply()
        }

    /** Créditos restantes de The Odds API según la última respuesta (-1 = desconocido). */
    var oddsCreditsRemaining: Int
        get() = prefs.getInt("odds_credits", -1)
        set(value) {
            prefs.edit().putInt("odds_credits", value).apply()
        }
}

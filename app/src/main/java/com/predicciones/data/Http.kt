package com.predicciones.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Error HTTP de una API. El mensaje ya está en español para mostrarlo en pantalla. */
class ApiException(val code: Int, message: String) : IOException(message)

class MissingKeyException(message: String) : IOException(message)

class HttpResponse(val body: String, val headers: Map<String, String>)

/** Permite como máximo `maxCalls` llamadas por ventana (football-data.org gratis: 10 por minuto). */
class RateLimiter(private val maxCalls: Int, private val windowMs: Long = 60_000L) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()

    suspend fun acquire() {
        mutex.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
                if (stamps.size < maxCalls) {
                    stamps.addLast(now)
                    return
                }
                delay(windowMs - (now - stamps.first()) + 50)
            }
        }
    }
}

object Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw ApiException(code, friendly(code))
                val received = HashMap<String, String>()
                for ((k, v) in conn.headerFields) {
                    if (k != null && v.isNotEmpty()) received[k.lowercase()] = v[0]
                }
                HttpResponse(text, received)
            } finally {
                conn.disconnect()
            }
        }

    private fun friendly(code: Int): String = when (code) {
        400 -> "Petición no válida (400)."
        401, 403 -> "Clave inválida o sin permiso para este dato ($code)."
        404 -> "No encontrado (404)."
        422 -> "Petición rechazada por la API (422)."
        429 -> "Límite de consultas alcanzado (429). Espera un minuto o revisa tu cuota."
        else -> "Error de la API ($code)."
    }
}

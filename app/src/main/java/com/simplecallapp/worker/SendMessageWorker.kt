package com.simplecallapp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplecallapp.data.repository.ChatRepository

class SendMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FROM_NUMBER = "from_number"
        const val KEY_TO_NUMBER = "to_number"
        const val KEY_TEXT = "text"
    }

    private val chatRepository = ChatRepository()

    override suspend fun doWork(): Result {
        var fromNumber = inputData.getString(KEY_FROM_NUMBER) ?: ""
        val toNumber = inputData.getString(KEY_TO_NUMBER) ?: ""
        val text = inputData.getString(KEY_TEXT) ?: ""

        // Bug 9 Fix: Si fromNumber viene vacío en Input Data, lo extraemos del SharedPreferences local
        if (fromNumber.isEmpty()) {
            val prefs = applicationContext.getSharedPreferences("SimpleCallAppPrefs", Context.MODE_PRIVATE)
            fromNumber = prefs.getString("user_number", "") ?: ""
        }

        if (fromNumber.isEmpty() || toNumber.isEmpty() || text.isEmpty()) {
            // No podemos continuar si falta información esencial, marcamos como fallo no reintentable
            return Result.failure()
        }

        return try {
            chatRepository.sendMessage(fromNumber, toNumber, text)
            Result.success()
        } catch (e: Exception) {
            // En caso de error de conexión, reintentar más tarde
            Result.retry()
        }
    }
}

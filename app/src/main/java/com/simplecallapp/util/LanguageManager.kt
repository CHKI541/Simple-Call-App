package com.simplecallapp.util

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.simplecallapp.R

object LanguageManager {

    fun showLanguageDialog(context: Context) {
        val languages = arrayOf(
            context.getString(R.string.lang_system_default),
            context.getString(R.string.lang_spanish),
            context.getString(R.string.lang_english)
        )

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val checkedItem = when {
            currentLocales.isEmpty -> 0
            currentLocales.get(0)?.language == "es" -> 1
            currentLocales.get(0)?.language == "en" -> 2
            else -> 0
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.choose_language)
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val localeList = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("es")
                    2 -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(localeList)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

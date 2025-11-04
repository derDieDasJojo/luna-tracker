package it.danieleverducci.lunatracker.repository

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.edit

class LocalSettingsRepository(val context: Context) {
    companion object {
        const val SHARED_PREFS_FILE_NAME = "lunasettings"
        const val SHARED_PREFS_BB_CONTENT = "bbcontent"
        const val SHARED_PREFS_DATA_REPO = "data_repo"
        const val SHARED_PREFS_DAV_URL = "webdav_url"
        const val SHARED_PREFS_DAV_USER = "webdav_user"
        const val SHARED_PREFS_DAV_PASS = "webdav_password"
        const val SHARED_PREFS_NO_BREASTFEEDING = "no_breastfeeding"
        const val SHARED_PREFS_SIGNATURE = "signature"
    }
    enum class DATA_REPO {LOCAL_FILE, WEBDAV}
    val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = context.getSharedPreferences(SHARED_PREFS_FILE_NAME, MODE_PRIVATE)
    }

    fun saveSignature(content: String) {
        sharedPreferences.edit { putString(SHARED_PREFS_SIGNATURE, content) }
    }

    fun loadSignature(): String {
        return sharedPreferences.getString(SHARED_PREFS_SIGNATURE, "") ?: ""
    }

    fun saveNoBreastfeeding(content: Boolean) {
        sharedPreferences.edit { putBoolean(SHARED_PREFS_NO_BREASTFEEDING, content) }
    }

    fun loadNoBreastfeeding(): Boolean {
        return sharedPreferences.getBoolean(SHARED_PREFS_NO_BREASTFEEDING, false)
    }

    fun saveDataRepository(repo: DATA_REPO) {
        sharedPreferences.edit(commit = true) {
            putString(
                SHARED_PREFS_DATA_REPO,
                when (repo) {
                    DATA_REPO.WEBDAV -> "webdav"
                    DATA_REPO.LOCAL_FILE -> "localfile"
                }
            )
        }
    }

    fun loadDataRepository(): DATA_REPO {
        val repo = sharedPreferences.getString(SHARED_PREFS_DATA_REPO, null)
        return when (repo) {
            "webdav" -> DATA_REPO.WEBDAV
            "localfile" -> DATA_REPO.LOCAL_FILE
            else -> DATA_REPO.LOCAL_FILE
        }
    }

    fun saveWebdavCredentials(url: String, username: String, password: String) {
        sharedPreferences.edit(commit = true) {
            putString(SHARED_PREFS_DAV_URL, url)
            putString(SHARED_PREFS_DAV_USER, username)
            putString(SHARED_PREFS_DAV_PASS, password)
        }
    }

    fun loadWebdavCredentials(): Array<String>? {
        val url = sharedPreferences.getString(SHARED_PREFS_DAV_URL, null)
        val user = sharedPreferences.getString(SHARED_PREFS_DAV_USER, null)
        val pass = sharedPreferences.getString(SHARED_PREFS_DAV_PASS, null)
        if (url == null || user == null || pass == null)
            return null
        return arrayOf(url, user, pass)
    }
}
package it.danieleverducci.lunatracker.repository

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.edit

class LocalSettingsRepository(val context: Context) {
    companion object {
        val SHARED_PREFS_FILE_NAME = "lunasettings"
        val SHARED_PREFS_BB_CONTENT = "bbcontent"
        val SHARED_PREFS_DATA_REPO = "data_repo"
        val SHARED_PREFS_DAV_URL = "webdav_url"
        val SHARED_PREFS_DAV_USER = "webdav_user"
        val SHARED_PREFS_DAV_PASS = "webdav_password"
        val SHARED_PREFS_NO_BREASTFEEDING = "no_breastfeeding"
    }
    enum class DATA_REPO {LOCAL_FILE, WEBDAV}
    val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = context.getSharedPreferences(SHARED_PREFS_FILE_NAME, MODE_PRIVATE)
    }

    fun saveBabyBottleContent(content: Int) {
        sharedPreferences.edit().putInt(SHARED_PREFS_BB_CONTENT, content).apply()
    }

    fun loadBabyBottleContent(): Int {
        return sharedPreferences.getInt(SHARED_PREFS_BB_CONTENT, 1)
    }

    fun saveNoBreastfeeding(content: Boolean) {
        sharedPreferences.edit().putBoolean(SHARED_PREFS_NO_BREASTFEEDING, content).apply()
    }

    fun loadNoBreastfeeding(): Boolean {
        return sharedPreferences.getBoolean(SHARED_PREFS_NO_BREASTFEEDING, false)
    }

    fun saveDataRepository(repo: DATA_REPO) {
        val spe = sharedPreferences.edit()
        spe.putString(
            SHARED_PREFS_DATA_REPO,
            when (repo) {
                DATA_REPO.WEBDAV -> "webdav"
                DATA_REPO.LOCAL_FILE -> "localfile"
            }
        )
        spe.commit()
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
        val spe = sharedPreferences.edit()
        spe.putString(SHARED_PREFS_DAV_URL, url)
        spe.putString(SHARED_PREFS_DAV_USER, username)
        spe.putString(SHARED_PREFS_DAV_PASS, password)
        spe.commit()
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
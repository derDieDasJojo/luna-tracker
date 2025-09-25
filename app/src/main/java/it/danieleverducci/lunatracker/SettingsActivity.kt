package it.danieleverducci.lunatracker

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import it.danieleverducci.lunatracker.repository.FileLogbookRepository
import it.danieleverducci.lunatracker.repository.LocalSettingsRepository
import it.danieleverducci.lunatracker.repository.LogbookListObtainedListener
import it.danieleverducci.lunatracker.repository.WebDAVLogbookRepository
import okio.IOException

open class SettingsActivity : AppCompatActivity() {
    protected lateinit var settingsRepository: LocalSettingsRepository
    protected lateinit var radioDataLocal: RadioButton
    protected lateinit var radioDataWebDAV: RadioButton
    protected lateinit var textViewWebDAVUrl: TextView
    protected lateinit var textViewWebDAVUser: TextView
    protected lateinit var textViewWebDAVPass: TextView
    protected lateinit var progressIndicator: LinearProgressIndicator
    protected lateinit var switchNoBreastfeeding: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)
        radioDataLocal = findViewById(R.id.settings_data_local)
        radioDataWebDAV = findViewById(R.id.settings_data_webdav)
        textViewWebDAVUrl = findViewById(R.id.settings_data_webdav_url)
        textViewWebDAVUser = findViewById(R.id.settings_data_webdav_user)
        textViewWebDAVPass = findViewById(R.id.settings_data_webdav_pass)
        progressIndicator = findViewById(R.id.progress_indicator)
        switchNoBreastfeeding = findViewById(R.id.switch_no_breastfeeding)

        findViewById<View>(R.id.settings_save).setOnClickListener({
            validateAndSave()
        })
        findViewById<View>(R.id.settings_cancel).setOnClickListener({
            finish()
        })

        settingsRepository = LocalSettingsRepository(this)
        loadSettings()
    }

    fun loadSettings() {
        val dataRepo = settingsRepository.loadDataRepository()
        val webDavCredentials = settingsRepository.loadWebdavCredentials()
        val noBreastfeeding = settingsRepository.loadNoBreastfeeding()

        when (dataRepo) {
            LocalSettingsRepository.DATA_REPO.LOCAL_FILE -> radioDataLocal.isChecked = true
            LocalSettingsRepository.DATA_REPO.WEBDAV -> radioDataWebDAV.isChecked = true
        }

        switchNoBreastfeeding.isChecked = noBreastfeeding

        if (webDavCredentials != null) {
            textViewWebDAVUrl.text = webDavCredentials[0]
            textViewWebDAVUser.text = webDavCredentials[1]
            textViewWebDAVPass.text = webDavCredentials[2]
        }
    }

    fun validateAndSave() {
        if (radioDataLocal.isChecked) {
            // No validation required, just save
            saveSettings()
            return
        }

        // Try to connect to WebDAV and check if the save file already exists
        val webDAVLogbookRepo = WebDAVLogbookRepository(
            textViewWebDAVUrl.text.toString(),
            textViewWebDAVUser.text.toString(),
            textViewWebDAVPass.text.toString()
        )
        progressIndicator.visibility = View.VISIBLE

        webDAVLogbookRepo.listLogbooks(this, object: LogbookListObtainedListener{

            override fun onLogbookListObtained(logbooksNames: ArrayList<String>) {
                if (logbooksNames.isEmpty()) {
                    // TODO: Ask the user if he wants to upload the local ones or to create a new one
                    copyLocalLogbooksToWebdav(webDAVLogbookRepo, object: OnCopyLocalLogbooksToWebdavFinishedListener {

                        override fun onCopyLocalLogbooksToWebdavFinished(errors: String?) {
                            runOnUiThread({
                                progressIndicator.visibility = View.INVISIBLE
                                if (errors == null) {
                                    saveSettings()
                                    Toast.makeText(this@SettingsActivity, R.string.settings_webdav_creation_ok, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@SettingsActivity, errors, Toast.LENGTH_SHORT).show()
                                }
                            })
                        }

                    })
                } else {
                    runOnUiThread({
                        progressIndicator.visibility = View.INVISIBLE
                        saveSettings()
                        Toast.makeText(this@SettingsActivity, R.string.settings_webdav_creation_ok, Toast.LENGTH_SHORT).show()
                    })
                }
            }

            override fun onIOError(error: IOException) {
                runOnUiThread({
                    progressIndicator.visibility = View.INVISIBLE
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_network_error) + error.toString(), Toast.LENGTH_SHORT).show()
                })
            }

            override fun onWebDAVError(error: SardineException) {
                runOnUiThread({
                    progressIndicator.visibility = View.INVISIBLE
                    if(error.toString().contains("401")) {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_webdav_error_denied), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.settings_webdav_error_generic) + error.toString(), Toast.LENGTH_SHORT).show()
                    }
                })
            }

            override fun onError(error: Exception) {
                runOnUiThread({
                    progressIndicator.visibility = View.INVISIBLE
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_generic_error) + error.toString(), Toast.LENGTH_SHORT).show()
                })
            }
        })

        /*webDAVLogbookRepo.createLogbook(this, LogbookRepository.DEFAULT_LOGBOOK_NAME, object: WebDAVLogbookRepository.LogbookCreatedListener{

            override fun onJSONError(error: JSONException) {
                runOnUiThread({
                    progressIndicator.visibility = View.INVISIBLE
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_json_error) + error.toString(), Toast.LENGTH_SHORT).show()
                })
            }


        })*/
    }

    fun saveSettings() {
        settingsRepository.saveDataRepository(
            if (radioDataWebDAV.isChecked) LocalSettingsRepository.DATA_REPO.WEBDAV
            else LocalSettingsRepository.DATA_REPO.LOCAL_FILE
        )
        settingsRepository.saveNoBreastfeeding(switchNoBreastfeeding.isChecked)
        settingsRepository.saveWebdavCredentials(
            textViewWebDAVUrl.text.toString(),
            textViewWebDAVUser.text.toString(),
            textViewWebDAVPass.text.toString()
        )
        finish()
    }

    /**
     * Copies the local logbooks to webdav.
     * @return success
     */
    private fun copyLocalLogbooksToWebdav(webDAVLogbookRepository: WebDAVLogbookRepository, listener: OnCopyLocalLogbooksToWebdavFinishedListener) {
        Thread(Runnable {
            val errors = StringBuilder()
            val fileLogbookRepo = FileLogbookRepository()
            val logbooks = fileLogbookRepo.getAllLogbooks(this)
            for (logbook in logbooks) {
                // Copy only if does not already exist
                val error = webDAVLogbookRepository.uploadLogbookIfNotExists(this, logbook.name)
                if (error != null) {
                    if (errors.isNotEmpty())
                        errors.append("\n")
                    errors.append(String.format(getString(R.string.settings_webdav_upload_error), logbook.name, error))
                }
            }
            listener.onCopyLocalLogbooksToWebdavFinished(
                if (errors.isEmpty()) null else errors.toString()
            )
        }).start()
    }

    private interface OnCopyLocalLogbooksToWebdavFinishedListener {
        fun onCopyLocalLogbooksToWebdavFinished(errors: String?)
    }

}
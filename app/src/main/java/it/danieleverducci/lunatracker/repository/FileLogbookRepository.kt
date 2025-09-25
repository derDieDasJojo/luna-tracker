package it.danieleverducci.lunatracker.repository

import android.content.Context
import it.danieleverducci.lunatracker.entities.Logbook
import android.util.Log
import it.danieleverducci.lunatracker.entities.LunaEvent
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilenameFilter

class FileLogbookRepository: LogbookRepository {
    companion object {
        const val TAG = "FileLogbookRepository"
        const val FILE_NAME_START = "data"
        const val FILE_NAME_END = ".json"
    }

    override fun loadLogbook(context: Context, name: String, listener: LogbookLoadedListener) {
        try {
            listener.onLogbookLoaded(loadLogbook(context, name))
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "No logbook file found, create one")
            val newLogbook = Logbook(name)
            saveLogbook(context, newLogbook)
            listener.onLogbookLoaded(newLogbook)
        }
    }

    fun loadLogbook(context: Context, name: String): Logbook {
        val logbook = Logbook(name)
        val fileName = getFileName(name)
        val file = File(context.filesDir, fileName)
        val json = FileInputStream(file).bufferedReader().use { it.readText() }
        val ja = JSONArray(json)
        for (i in 0 until ja.length()) {
            try {
                val evt: LunaEvent = LunaEvent(ja.getJSONObject(i))
                logbook.logs.add(evt)
            } catch (e: IllegalArgumentException) {
                // Mangled JSON?
                throw JSONException(e.toString())
            }
        }
        return logbook
    }

    override fun saveLogbook(
        context: Context,
        logbook: Logbook,
        listener: LogbookSavedListener
    ) {
        saveLogbook(context, logbook)
        listener.onLogbookSaved()
    }

    fun saveLogbook(context: Context, logbook: Logbook) {
        val fileName = getFileName(logbook.name)
        val file = File(context.filesDir, fileName)
        val ja = JSONArray()
        for (l in logbook.logs) {
            ja.put(l.toJson())
        }
        file.writeText(ja.toString())
    }

    override fun listLogbooks(
        context: Context,
        listener: LogbookListObtainedListener
    ) {
        listener.onLogbookListObtained(listLogbooks(context))
    }

    fun getAllLogbooks(context: Context): List<Logbook> {
        val logbooks = arrayListOf<Logbook>()
        for (logbookName in listLogbooks(context)) {
            logbooks.add(loadLogbook(context, logbookName))
        }
        return logbooks
    }

    private fun listLogbooks(context: Context): ArrayList<String> {
        val logbooksFileNames = context.filesDir.list(object: FilenameFilter {
            override fun accept(dir: File?, name: String?): Boolean {
                if (name == null)
                    return false
                if (name.startsWith(FILE_NAME_START) && name.endsWith(FILE_NAME_END))
                    return true
                return false
            }
        })

        if (logbooksFileNames == null || logbooksFileNames.isEmpty()) {
            return arrayListOf()
        }

        val logbooksNames = arrayListOf<String>()
        logbooksFileNames.forEach { it ->
            logbooksNames.add(
                it.replace("${FILE_NAME_START}_", "").replace(FILE_NAME_START, "").replace(FILE_NAME_END, "")
            )
        }
        return logbooksNames
    }

    private fun getFileName(name: String): String {
        return "$FILE_NAME_START${if (name.isNotEmpty()) "_" else ""}${name}$FILE_NAME_END"
    }
}
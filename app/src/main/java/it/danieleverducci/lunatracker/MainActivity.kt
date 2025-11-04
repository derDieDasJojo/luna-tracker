package it.danieleverducci.lunatracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.PopupWindow
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import it.danieleverducci.lunatracker.adapters.LunaEventRecyclerAdapter
import it.danieleverducci.lunatracker.entities.Logbook
import it.danieleverducci.lunatracker.entities.LunaEvent
import it.danieleverducci.lunatracker.repository.FileLogbookRepository
import it.danieleverducci.lunatracker.repository.LocalSettingsRepository
import it.danieleverducci.lunatracker.repository.LogbookListObtainedListener
import it.danieleverducci.lunatracker.repository.LogbookLoadedListener
import it.danieleverducci.lunatracker.repository.LogbookRepository
import it.danieleverducci.lunatracker.repository.LogbookSavedListener
import it.danieleverducci.lunatracker.repository.WebDAVLogbookRepository
import kotlinx.coroutines.Runnable
import okio.IOException
import org.json.JSONException
import utils.DateUtils
import utils.NumericUtils
import java.util.Calendar
import java.util.Date

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "MainActivity"
        const val UPDATE_EVERY_SECS: Long = 30
        const val DEBUG_CHECK_LOGBOOK_CONSISTENCY = false
    }

    var logbook: Logbook? = null
    var pauseLogbookUpdate = false
    lateinit var progressIndicator: LinearProgressIndicator
    lateinit var buttonsContainer: ViewGroup
    lateinit var recyclerView: RecyclerView
    lateinit var handler: Handler
    var signature = ""
    var savingEvent = false
    val updateListRunnable: Runnable = Runnable {
        if (logbook != null && !pauseLogbookUpdate)
            loadLogbook(logbook!!.name)
        handler.postDelayed(updateListRunnable, 1000*60)
    }
    var logbookRepo: LogbookRepository? = null
    var showingOverflowPopupWindow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handler = Handler(mainLooper)

        // Show view
        setContentView(R.layout.activity_main)

        progressIndicator = findViewById(R.id.progress_indicator)
        buttonsContainer = findViewById(R.id.buttons_container)
        recyclerView = findViewById(R.id.list_events)
        recyclerView.setLayoutManager(LinearLayoutManager(applicationContext))

        // Set listeners
        findViewById<View>(R.id.logbooks_add_button).setOnClickListener { showAddLogbookDialog(true) }
        findViewById<View>(R.id.button_bottle).setOnClickListener { askBabyBottleContent() }
        findViewById<View>(R.id.button_food).setOnClickListener { askNotes(LunaEvent(LunaEvent.TYPE_FOOD)) }
        findViewById<View>(R.id.button_nipple_left).setOnClickListener { logEvent(
            LunaEvent(
                LunaEvent.TYPE_BREASTFEEDING_LEFT_NIPPLE
            )
        ) }
        findViewById<View>(R.id.button_nipple_both).setOnClickListener { logEvent(
            LunaEvent(
                LunaEvent.TYPE_BREASTFEEDING_BOTH_NIPPLE
            )
        ) }
        findViewById<View>(R.id.button_nipple_right).setOnClickListener { logEvent(
            LunaEvent(
                LunaEvent.TYPE_BREASTFEEDING_RIGHT_NIPPLE
            )
        ) }
        findViewById<View>(R.id.button_change_poo).setOnClickListener { logEvent(
            LunaEvent(
                LunaEvent.TYPE_DIAPERCHANGE_POO
            )
        ) }
        findViewById<View>(R.id.button_change_pee).setOnClickListener { logEvent(
            LunaEvent(
                LunaEvent.TYPE_DIAPERCHANGE_PEE
            )
        ) }
        val moreButton = findViewById<View>(R.id.button_more)
        moreButton.setOnClickListener {
            showOverflowPopupWindow(moreButton)
        }
        findViewById<View>(R.id.button_no_connection_settings).setOnClickListener {
            showSettings()
        }
        findViewById<View>(R.id.button_settings).setOnClickListener {
            showSettings()
        }
        findViewById<View>(R.id.button_no_connection_retry).setOnClickListener {
            // This may happen at start, when logbook is still null: better ask the logbook list
            loadLogbookList()
        }
        findViewById<View>(R.id.button_sync).setOnClickListener {
            loadLogbookList()
        }
    }

    private fun getAllEvents(): ArrayList<LunaEvent> {
        return logbook?.logs ?: arrayListOf()
    }

    private fun setListAdapter(items: ArrayList<LunaEvent>) {
        val adapter = LunaEventRecyclerAdapter(this, items)
        adapter.onItemClickListener = object: LunaEventRecyclerAdapter.OnItemClickListener {
            override fun onItemClick(event: LunaEvent) {
                showEventDetailDialog(event, items)
            }
        }
        recyclerView.adapter = adapter
    }

    fun showSettings() {
        val i = Intent(this, SettingsActivity::class.java)
        startActivity(i)
    }

    fun showLogbook() {
        // Show logbook
        if (logbook == null)
            Log.w(TAG, "showLogbook(): logbook is null!")

        setListAdapter(logbook?.logs ?: arrayListOf())
    }

    override fun onStart() {
        super.onStart()

        val settingsRepository = LocalSettingsRepository(this)
        if (settingsRepository.loadDataRepository() == LocalSettingsRepository.DATA_REPO.WEBDAV) {
            val webDavCredentials = settingsRepository.loadWebdavCredentials()
            if (webDavCredentials == null) {
                throw IllegalStateException("Corrupted local settings: repo is webdav, but no webdav login data saved")
            }
            logbookRepo = WebDAVLogbookRepository(
                webDavCredentials[0],
                webDavCredentials[1],
                webDavCredentials[2]
            )
        } else {
            logbookRepo = FileLogbookRepository()
        }

        signature = settingsRepository.loadSignature()

        val noBreastfeeding = settingsRepository.loadNoBreastfeeding()
        findViewById<View>(R.id.layout_nipples).visibility = when (noBreastfeeding) {
            true -> View.GONE
            false -> View.VISIBLE
        }

        // Update list dates
        recyclerView.adapter?.notifyDataSetChanged()

        if (logbook != null) {
            // Already running: reload data for currently selected logbook
            loadLogbook(logbook!!.name)
        } else {
            // First start: load logbook list
            loadLogbookList()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(updateListRunnable)

        super.onStop()
    }

    fun askBabyBottleContent() {
        // Show number picker dialog
        val localSettings = LocalSettingsRepository(this)
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.number_picker_dialog, null)
        d.setTitle(R.string.log_bottle_dialog_title)
        d.setMessage(R.string.log_bottle_dialog_description)
        d.setView(dialogView)
        val numberPicker = dialogView.findViewById<NumberPicker>(R.id.dialog_number_picker)
        numberPicker.minValue = 1 // "10"
        numberPicker.maxValue = 25 // "250
        numberPicker.displayedValues = ((10..250 step 10).map { it.toString() }.toTypedArray())
        numberPicker.wrapSelectorWheel = false
        numberPicker.value = localSettings.loadBabyBottleContent()
        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            logEvent(LunaEvent(LunaEvent.TYPE_BABY_BOTTLE, numberPicker.value * 10))
            localSettings.saveBabyBottleContent(numberPicker.value)
        }
        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun askWeightValue() {
        // Show number picker dialog
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.number_edit_dialog, null)
        d.setTitle(R.string.log_weight_dialog_title)
        d.setMessage(R.string.log_weight_dialog_description)
        d.setView(dialogView)
        val weightET = dialogView.findViewById<EditText>(R.id.dialog_number_edittext)
        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val weight = weightET.text.toString().toIntOrNull()
            if (weight != null)
                logEvent(LunaEvent(LunaEvent.TYPE_WEIGHT, weight))
            else
                Toast.makeText(this, R.string.toast_integer_error, Toast.LENGTH_SHORT).show()
        }
        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun askTemperatureValue() {
        // Show number picker dialog
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.temperature_dialog, null)
        d.setTitle(R.string.log_temperature_dialog_title)
        d.setMessage(R.string.log_temperature_dialog_description)
        d.setView(dialogView)
        val tempSlider = dialogView.findViewById<Slider>(R.id.dialog_temperature_value)
        val range = NumericUtils(this).getValidEventQuantityRange(LunaEvent.TYPE_TEMPERATURE)!!
        tempSlider.valueFrom = range.first.toFloat()
        tempSlider.valueTo = range.second.toFloat()
        tempSlider.value = range.third.toFloat()
        val tempTextView = dialogView.findViewById<TextView>(R.id.dialog_temperature_display)
        tempTextView.text = range.third.toString()
        tempSlider.addOnChangeListener({s, v, b -> tempTextView.text = v.toString()})
        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val temperature = (tempSlider.value * 10).toInt()   // In tenth of a grade
            logEvent(LunaEvent(LunaEvent.TYPE_TEMPERATURE, temperature))
        }
        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun askPukeValue() {
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.puke_dialog, null)
        d.setTitle(R.string.log_puke_dialog_title)
        d.setMessage(R.string.log_puke_dialog_description)
        d.setView(dialogView)

        val spinner = dialogView.findViewById<Spinner>(R.id.dialog_puke_value)
        spinner.adapter = ArrayAdapter.createFromResource(this, R.array.AmountLabels, android.R.layout.simple_spinner_dropdown_item)
        spinner.setSelection(1)

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val pos = spinner.selectedItemPosition
            logEvent(LunaEvent(LunaEvent.TYPE_PUKE, pos))
        }
        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun askNotes(lunaEvent: LunaEvent) {
        val allEvents = getAllEvents()
        val useQuantity = (lunaEvent.type != LunaEvent.TYPE_NOTE && lunaEvent.type != LunaEvent.TYPE_CUSTOM)

        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_notes, null)
        d.setTitle(lunaEvent.getTypeDescription(this))
        d.setMessage(lunaEvent.getDialogMessage(this))
        d.setView(dialogView)
        val notesET = dialogView.findViewById<EditText>(R.id.notes_edittext)
        val qtyET = dialogView.findViewById<EditText>(R.id.notes_qty_edittext)

        val nextTextView = dialogView.findViewById<TextView>(R.id.notes_template_next)
        val prevTextView = dialogView.findViewById<TextView>(R.id.notes_template_prev)

        fun updateContent(current: LunaEvent) {
            val prevEvent = getPreviousSameEvent(current, allEvents)
            var nextEvent = getNextSameEvent(current, allEvents)

            notesET.setText(current.notes)
            if (useQuantity) {
                qtyET.setText(current.quantity.toString())
            }

            if (nextEvent == null && current != lunaEvent) {
                nextEvent = lunaEvent
            }

            if (nextEvent != null) {
                nextTextView.setOnClickListener {
                    notesET.setText(nextEvent.notes)
                    if (useQuantity) {
                        qtyET.setText(nextEvent.quantity.toString())
                    }
                    updateContent(nextEvent)
                }
                nextTextView.alpha = 1.0f
            } else {
                nextTextView.setOnClickListener {}
                nextTextView.alpha = 0.5f
            }

            if (prevEvent != null) {
                prevTextView.setOnClickListener {
                    notesET.setText(prevEvent.notes)
                    if (useQuantity) {
                        qtyET.setText(prevEvent.quantity.toString())
                    }
                    updateContent(prevEvent)
                }
                prevTextView.alpha = 1.0f
            } else {
                prevTextView.setOnClickListener {}
                prevTextView.alpha = 0.5f
            }
        }

        if (!useQuantity) {
            qtyET.visibility = View.GONE
        }

        updateContent(lunaEvent)

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val qtyStr = qtyET.text.toString()
            if (qtyStr.isNotEmpty() && useQuantity) {
                val qty = qtyStr.toIntOrNull()
                if (qty == null) {
                    Toast.makeText(this, R.string.toast_integer_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lunaEvent.quantity = qty
            }
            val notes = notesET.text.toString()
            lunaEvent.notes = notes
            logEvent(lunaEvent)
        }
        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun askToTrimLogbook() {
        val d = AlertDialog.Builder(this)
        d.setTitle(R.string.trim_logbook_dialog_title)
        d.setMessage(
            when (LocalSettingsRepository(this).loadDataRepository()) {
                LocalSettingsRepository.DATA_REPO.WEBDAV -> R.string.trim_logbook_dialog_message_dav
                else -> R.string.trim_logbook_dialog_message_local
            }
        )
        d.setPositiveButton(R.string.trim_logbook_dialog_button_ok) { dialogInterface, i ->
            logbook?.trim()
            saveLogbook()
        }
        d.setNegativeButton(R.string.trim_logbook_dialog_button_cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun getPreviousSameEvent(event: LunaEvent, items: ArrayList<LunaEvent>): LunaEvent? {
        var previousEvent: LunaEvent? = null
        for (item in items) {
            if (item.type == event.type && item.time < event.time) {
                if (previousEvent == null) {
                    previousEvent = item
                } else if (previousEvent.time < item.time) {
                    previousEvent = item
                }
            }
        }
        return previousEvent
    }

    fun getNextSameEvent(event: LunaEvent, items: ArrayList<LunaEvent>): LunaEvent? {
        var nextEvent: LunaEvent? = null
        for (item in items) {
            if (item.type == event.type && item.time > event.time) {
                if (nextEvent == null) {
                    nextEvent = item
                } else if (nextEvent.time > item.time) {
                    nextEvent = item
                }
            }
        }
        return nextEvent
    }

    fun showEventDetailDialog(event: LunaEvent, items: ArrayList<LunaEvent>) {
        // Do not update list while the detail is shown, to avoid changing the object below while it is changed by the user
        pauseLogbookUpdate = true
        val d = AlertDialog.Builder(this)
        d.setTitle(R.string.dialog_event_detail_title)
        val dialogView = layoutInflater.inflate(R.layout.dialog_event_detail, null)
        dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_emoji).text = event.getTypeEmoji(this)
        dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_description).text = event.getTypeDescription(this)
        dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_quantity).text =
            NumericUtils(this).formatEventQuantity(event)
        dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_notes).text = event.notes

        val currentDateTime = Calendar.getInstance()
        currentDateTime.time = Date(event.time * 1000)

        val dateTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_date)
        dateTextView.text = String.format(getString(R.string.dialog_event_detail_datetime_icon), DateUtils.formatDateTime(event.time))
        dateTextView.setOnClickListener {
            // Show datetime picker
            val startYear = currentDateTime.get(Calendar.YEAR)
            val startMonth = currentDateTime.get(Calendar.MONTH)
            val startDay = currentDateTime.get(Calendar.DAY_OF_MONTH)
            val startHour = currentDateTime.get(Calendar.HOUR_OF_DAY)
            val startMinute = currentDateTime.get(Calendar.MINUTE)

            DatePickerDialog(this, { _, year, month, day ->
                TimePickerDialog(this, { _, hour, minute ->
                    val pickedDateTime = Calendar.getInstance()
                    pickedDateTime.set(year, month, day, hour, minute)
                    // Save event and move it to the right position in the logbook
                    event.time = pickedDateTime.time.time / 1000 // Seconds since epoch
                    dateTextView.text = String.format(getString(R.string.dialog_event_detail_datetime_icon), DateUtils.formatDateTime(event.time))
                    logbook?.sort()
                    recyclerView.adapter?.notifyDataSetChanged()
                    saveLogbook()
                }, startHour, startMinute, android.text.format.DateFormat.is24HourFormat(this@MainActivity)).show()
            }, startYear, startMonth, startDay).show()
        }

        d.setView(dialogView)
        d.setPositiveButton(R.string.dialog_event_detail_close_button) { dialogInterface, i -> dialogInterface.dismiss() }
        d.setNeutralButton(R.string.dialog_event_detail_delete_button) { dialogInterface, i -> deleteEvent(event) }
        val alertDialog = d.create()
        alertDialog.show()
        alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(ContextCompat.getColor(this, R.color.danger))
        alertDialog.setOnDismissListener({
            // Resume logbook update
            pauseLogbookUpdate = false
        })

        // show optional signature
        if (event.signature.isNotEmpty()) {
            val signatureTextEdit = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_signature)
            signatureTextEdit.text =  String.format(getString(R.string.dialog_event_detail_signature), event.signature)
            signatureTextEdit.visibility = View.VISIBLE
        }

        // create next/previous links to events of the same type

        val previousTextView = dialogView.findViewById<TextView>(R.id.dialog_event_previous)
        val nextTextView = dialogView.findViewById<TextView>(R.id.dialog_event_next)
        val nextEvent = getNextSameEvent(event, items)
        val previousEvent = getPreviousSameEvent(event, items)

        if (previousEvent != null) {
            val emoji = previousEvent.getTypeEmoji(applicationContext)
            val time = DateUtils.formatTimeDuration(applicationContext, event.time - previousEvent.time)
            previousTextView.text = String.format("⬅️ %s %s", emoji, time)
            previousTextView.setOnClickListener {
                alertDialog.cancel()
                showEventDetailDialog(previousEvent, items)
            }
        } else {
            previousTextView.visibility = View.GONE
        }

        if (nextEvent != null) {
            val emoji = nextEvent.getTypeEmoji(applicationContext)
            val time = DateUtils.formatTimeDuration(applicationContext, nextEvent.time - event.time)
            nextTextView.text = String.format("%s %s ➡️", time, emoji)
            nextTextView.setOnClickListener {
                alertDialog.cancel()
                showEventDetailDialog(nextEvent, items)
            }
        } else {
            nextTextView.visibility = View.GONE
        }
    }

    fun showAddLogbookDialog(requestedByUser: Boolean) {
        val d = AlertDialog.Builder(this)
        d.setTitle(R.string.dialog_add_logbook_title)
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_logbook, null)
        dialogView.findViewById<TextView>(R.id.dialog_add_logbook_message).text = getString(
            if (requestedByUser) R.string.dialog_add_logbook_message else R.string.dialog_add_logbook_message_intro
        )
        val logbookNameEditText = dialogView.findViewById<EditText>(R.id.dialog_add_logbook_logbookname)
        d.setView(dialogView)
        d.setPositiveButton(android.R.string.ok) { dialogInterface, i -> addLogbook(logbookNameEditText.text.toString()) }
        if (requestedByUser) {
            d.setCancelable(true)
            d.setNegativeButton(android.R.string.cancel) { dialogInterface, i -> dialogInterface.dismiss() }
        } else {
            d.setCancelable(false)
        }
        val alertDialog = d.create()
        alertDialog.show()
    }

    fun loadLogbookList() {
        setLoading(true)
        logbookRepo?.listLogbooks(this, object: LogbookListObtainedListener {
            override fun onLogbookListObtained(logbooksNames: ArrayList<String>) {
                runOnUiThread({
                    if (logbooksNames.isEmpty()) {
                        // First run, no logbook: create one
                        showAddLogbookDialog(false)
                        return@runOnUiThread
                    }
                    // Show logbooks dropdown
                    val spinner = findViewById<Spinner>(R.id.logbooks_spinner)
                    val sAdapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item)
                    sAdapter.setDropDownViewResource(R.layout.row_logbook_spinner)
                    for (ln in logbooksNames) {
                        sAdapter.add(
                            ln.ifEmpty { getString(R.string.default_logbook_name) }
                        )
                    }
                    spinner.adapter = sAdapter
                    spinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            // Changed logbook: empty list
                            setListAdapter(arrayListOf())
                            // Load logbook
                            loadLogbook(logbooksNames.get(position))
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                })
            }

            override fun onIOError(error: IOException) {
                Log.e(TAG, "Unable to load logbooks list (IOError): $error")
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_network_error) + error.toString())
                })
            }

            override fun onWebDAVError(error: SardineException) {
                Log.e(TAG, "Unable to load logbooks list (SardineException): $error")
                runOnUiThread({
                    setLoading(false)
                    onRepoError(
                        if(error.toString().contains("401")) {
                            getString(R.string.settings_webdav_error_denied)
                        } else if(error.toString().contains("503")) {
                            getString(R.string.settings_webdav_error_server_offline)
                        } else {
                            getString(R.string.settings_webdav_error_generic) + error.toString()
                        }
                    )
                })
            }

            override fun onError(error: Exception) {
                Log.e(TAG, "Unable to load logbooks list: $error")
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_generic_error) + error.toString())
                })
            }
        })
    }

    fun addLogbook(logbookName: String) {
        val newLogbook = Logbook(logbookName)
        setLoading(true)
        logbookRepo?.saveLogbook(this, newLogbook, object: LogbookSavedListener{
            override fun onLogbookSaved() {
                Log.d(TAG, "Logbook $logbookName created")
                runOnUiThread({
                    setLoading(false)
                    loadLogbookList()
                    Toast.makeText(this@MainActivity, getString(R.string.logbook_created) + logbookName, Toast.LENGTH_SHORT).show()
                })
            }

            override fun onIOError(error: IOException) {
                runOnUiThread({
                    onRepoError(getString(R.string.settings_network_error) + error.toString())
                })
            }

            override fun onWebDAVError(error: SardineException) {
                runOnUiThread({
                    onRepoError(
                        if(error.toString().contains("401")) {
                            getString(R.string.settings_webdav_error_denied)
                        } else if(error.toString().contains("503")) {
                            getString(R.string.settings_webdav_error_server_offline)
                        } else {
                            getString(R.string.settings_webdav_error_generic) + error.toString()
                        }
                    )
                })
            }

            override fun onJSONError(error: JSONException) {
                runOnUiThread({
                    onRepoError(getString(R.string.settings_json_error) + error.toString())
                })
            }

            override fun onError(error: Exception) {
                runOnUiThread({
                    onRepoError(getString(R.string.settings_generic_error) + error.toString())
                })
            }
        })
    }

    fun loadLogbook(name: String) {
        if (savingEvent)
            return

        // Reset time counter
        handler.removeCallbacks(updateListRunnable)
        handler.postDelayed(updateListRunnable, UPDATE_EVERY_SECS*1000)

        // Load data
        setLoading(true)
        logbookRepo?.loadLogbook(this, name, object: LogbookLoadedListener{
            override fun onLogbookLoaded(lb: Logbook) {
                runOnUiThread({
                    setLoading(false)
                    findViewById<View>(R.id.no_connection_screen).visibility = View.GONE
                    logbook = lb
                    showLogbook()

                    if (DEBUG_CHECK_LOGBOOK_CONSISTENCY) {
                        for (e in logbook?.logs ?: listOf()) {
                            val em = e.getTypeEmoji(this@MainActivity)
                            if (em == getString(R.string.event_unknown_type)) {
                                Log.e(TAG, "UNKNOWN: ${e.type}")
                            }
                        }
                    }
                })
            }

            override fun onIOError(error: IOException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_network_error) + error.toString())
                })
            }

            override fun onWebDAVError(error: SardineException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(
                        if(error.toString().contains("401")) {
                            getString(R.string.settings_webdav_error_denied)
                        } else if(error.toString().contains("503")) {
                            getString(R.string.settings_webdav_error_server_offline)
                        } else {
                            getString(R.string.settings_webdav_error_generic) + error.toString()
                        }
                    )
                })
            }

            override fun onJSONError(error: JSONException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_json_error) + error.toString())
                })
            }

            override fun onError(error: Exception) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_generic_error) + error.toString())
                })
            }
        })
    }

    fun onRepoError(message: String){
        runOnUiThread({
            setLoading(false)
            findViewById<View>(R.id.no_connection_screen).visibility = View.VISIBLE
            findViewById<TextView>(R.id.no_connection_screen_message).text = message
        })
    }

    fun logEvent(event: LunaEvent) {
        savingEvent(true)

        event.signature = signature

        setLoading(true)
        logbook?.logs?.add(0, event)
        recyclerView.adapter?.notifyItemInserted(0)
        recyclerView.smoothScrollToPosition(0)
        saveLogbook(event)

        // Check logbook size to avoid OOM errors
        if (logbook?.isTooBig() == true) {
            askToTrimLogbook()
        }
    }

    fun deleteEvent(event: LunaEvent) {
        // Update view
        savingEvent(true)

        // Update data
        setLoading(true)
        logbook?.logs?.remove(event)
        recyclerView.adapter?.notifyDataSetChanged()
        saveLogbook()
    }

    /**
     * Saves the logbook. If saving while adding an event, please specify the event so in case
     * of error can be removed from the list.
     */
    fun saveLogbook(lastEventAdded: LunaEvent? = null) {
        if (logbook == null) {
            Log.e(TAG, "Trying to save logbook, but logbook is null!")
            return
        }
        logbookRepo?.saveLogbook(this, logbook!!, object: LogbookSavedListener{
            override fun onLogbookSaved() {
                Log.d(TAG, "Logbook saved")
                runOnUiThread({
                    setLoading(false)

                    Toast.makeText(
                        this@MainActivity,
                        if (lastEventAdded != null)
                            R.string.toast_event_added
                        else
                            R.string.toast_logbook_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                    savingEvent(false)
                })
            }

            override fun onIOError(error: IOException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_network_error) + error.toString())
                    if (lastEventAdded != null)
                        onAddError(lastEventAdded, error.toString())
                })
            }

            override fun onWebDAVError(error: SardineException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(
                        if(error.toString().contains("401")) {
                            getString(R.string.settings_webdav_error_denied)
                        } else if(error.toString().contains("503")) {
                            getString(R.string.settings_webdav_error_server_offline)
                        } else {
                            getString(R.string.settings_webdav_error_generic) + error.toString()
                        }
                    )
                    if (lastEventAdded != null)
                        onAddError(lastEventAdded, error.toString())
                })
            }

            override fun onJSONError(error: JSONException) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_json_error) + error.toString())
                    if (lastEventAdded != null)
                        onAddError(lastEventAdded, error.toString())
                })
            }

            override fun onError(error: Exception) {
                runOnUiThread({
                    setLoading(false)
                    onRepoError(getString(R.string.settings_generic_error) + error.toString())
                    if (lastEventAdded != null)
                        onAddError(lastEventAdded, error.toString())
                })
            }
        })
    }

    private fun onAddError(event: LunaEvent, error: String) {
        Log.e(TAG, "Logbook was NOT saved! $error")
        runOnUiThread({
            setLoading(false)

            Toast.makeText(this@MainActivity, R.string.toast_event_add_error, Toast.LENGTH_SHORT).show()
            recyclerView.adapter?.notifyDataSetChanged()
            savingEvent(false)
        })
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            progressIndicator.visibility = View.VISIBLE
        } else {
            progressIndicator.visibility = View.INVISIBLE
        }
    }

    private fun savingEvent(saving: Boolean) {
        if (saving) {
            savingEvent = true
            buttonsContainer.alpha = 0.2f
        } else {
            savingEvent = false
            buttonsContainer.alpha = 1f
        }
    }

    private fun showOverflowPopupWindow(anchor: View) {
        if (showingOverflowPopupWindow)
            return

        PopupWindow(anchor.context).apply {
            isOutsideTouchable = true
            val inflater = LayoutInflater.from(anchor.context)
            contentView = inflater.inflate(R.layout.more_events_popup, null)
            contentView.findViewById<View>(R.id.button_medicine).setOnClickListener {
                askNotes(LunaEvent(LunaEvent.TYPE_MEDICINE))
                dismiss()
            }
            contentView.findViewById<View>(R.id.button_enema).setOnClickListener({
                logEvent(LunaEvent(LunaEvent.TYPE_ENEMA))
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_note).setOnClickListener({
                askNotes(LunaEvent(LunaEvent.TYPE_NOTE))
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_temperature).setOnClickListener({
                askTemperatureValue()
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_puke).setOnClickListener({
                askPukeValue()
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_colic).setOnClickListener({
                logEvent(
                    LunaEvent(LunaEvent.TYPE_COLIC)
                )
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_scale).setOnClickListener({
                askWeightValue()
                dismiss()
            })
            contentView.findViewById<View>(R.id.button_bath).setOnClickListener({
                logEvent(
                    LunaEvent(LunaEvent.TYPE_BATH)
                )
                dismiss()
            })
        }.also { popupWindow ->
            popupWindow.setOnDismissListener({
                Handler(mainLooper).postDelayed({
                    showingOverflowPopupWindow = false
                }, 500)
            })
            popupWindow.showAsDropDown(anchor)
            showingOverflowPopupWindow = true
        }
    }
}

package it.danieleverducci.lunatracker

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
        // list of all events
        var allEvents = arrayListOf<LunaEvent>()
        var currentPopupItems = listOf<LunaEvent.Type>()
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
        handler.postDelayed(updateListRunnable, 1000 * 60)
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

        populateHeaderMenu()

        // Set listeners
        findViewById<View>(R.id.logbooks_add_button).setOnClickListener {
            showAddLogbookDialog(true)
        }

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
            if (logbook != null) {
                loadLogbook(logbook!!.name)
            } else {
                loadLogbookList()
            }
        }
    }

    private fun setListAdapter(items: ArrayList<LunaEvent>) {
        val adapter = LunaEventRecyclerAdapter(this, items)
        adapter.onItemClickListener = object : LunaEventRecyclerAdapter.OnItemClickListener {
            override fun onItemClick(event: LunaEvent) {
                showEventDetailDialog(event)
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

        allEvents = logbook?.logs ?: arrayListOf()
        setListAdapter(allEvents)

        populateHeaderMenu()
    }

    private fun populateHeaderMenu() {
        val settingsRepository = LocalSettingsRepository(this)
        val dynamicMenu = settingsRepository.loadDynamicMenu()
        val eventTypeStats = mutableMapOf<LunaEvent.Type, Int>()

        if (dynamicMenu) {
            val sampleSize = 100
            // populate frequency map from first 100 events
            allEvents.take(sampleSize.coerceAtMost(allEvents.size)).forEach {
                eventTypeStats[it.type] = 1 + (eventTypeStats[it.type] ?: 0)
            }
        }

        // sort all event types by frequency and ordinal
        val eventTypesSorted = LunaEvent.Type.entries.toList().sortedWith(
            compareBy({ -1 * (eventTypeStats[it] ?: 0) }, { it.ordinal })
        ).filter { it != LunaEvent.Type.UNKNOWN }

        fun setupMenu(maxButtonCount: Int, sortedEventTypes: List<LunaEvent.Type>): Int {
            val row1 = findViewById<View>(R.id.linear_layout_row1)
            val row1Button1 = findViewById<TextView>(R.id.button1_row1)
            val row1Button2 = findViewById<TextView>(R.id.button2_row1)

            val row2 = findViewById<View>(R.id.linear_layout_row2)
            val row2Button1 = findViewById<TextView>(R.id.button1_row2)
            val row2Button2 = findViewById<TextView>(R.id.button2_row2)
            val row2Button3 = findViewById<TextView>(R.id.button3_row2)

            val row3 = findViewById<View>(R.id.linear_layout_row3)
            val row3Button1 = findViewById<TextView>(R.id.button1_row3)
            val row3Button2 = findViewById<TextView>(R.id.button2_row3)

            // hide all rows/buttons (except row 3)
            for (view in listOf(row1, row1Button1, row1Button2,
                                row2, row2Button1, row2Button2, row2Button3,
                                row3, row3Button1, row3Button2)) {
                view.visibility = View.GONE
            }
            row3.visibility = View.VISIBLE

            var showCounter = 0

            fun show(vararg tvs: TextView) {
                for (tv in tvs) {
                    val type = sortedEventTypes[showCounter]
                    tv.text = LunaEvent.getHeaderEmoji(applicationContext, type)
                    tv.setOnClickListener { showCreateDialog(type) }
                    tv.visibility = View.VISIBLE
                    // show parent row
                    (tv.parent as View).visibility = View.VISIBLE
                    showCounter += 1
                }
            }

            when (maxButtonCount) {
                0 -> { } // ignore - show empty row3
                1 -> show(row3Button1)
                2 -> show(row3Button1, row3Button2)
                3 -> show(row1Button1, row3Button1)
                4, 5, 6 -> show(row1Button1, row1Button2, row3Button1, row3Button2)
                else -> show(row1Button1, row1Button2, row2Button1, row2Button2, row2Button3, row3Button1, row3Button2)
            }

            return showCounter
        }

        val usedEventCount = eventTypeStats.count { it.value > 0 }
        val maxButtonCount = if (dynamicMenu) { usedEventCount } else { 7 }
        val eventsShown = setupMenu(maxButtonCount, eventTypesSorted)

        // store left over events for popup menu
        currentPopupItems = eventTypesSorted.subList(eventsShown, eventTypesSorted.size)
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

    fun addBabyBottleEvent(event: LunaEvent) {
        setToPreviousQuantity(event)
        askBabyBottleContent(event, true) {
            saveEvent(event)
        }
    }

    fun askBabyBottleContent(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_bottle, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val numberPicker = dialogView.findViewById<NumberPicker>(R.id.dialog_number_picker)
        numberPicker.minValue = 1 // "10"
        numberPicker.maxValue = 34 // "340
        numberPicker.displayedValues = ((10..340 step 10).map { it.toString() }.toTypedArray())
        numberPicker.wrapSelectorWheel = false
        numberPicker.value = event.quantity / 10

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedTime = datePickerHelper(event.time, dateTV)

        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            event.time = pickedTime.time.time / 1000
            event.quantity = numberPicker.value * 10
            onPositive()
            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun addWeightEvent(event: LunaEvent) {
        setToPreviousQuantity(event)
        askWeightValue(event, true) { saveEvent(event) }
    }

    fun askWeightValue(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        // Show number picker dialog
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_weight, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val weightET = dialogView.findViewById<EditText>(R.id.dialog_number_edittext)
        weightET.setText(event.quantity.toString())

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedTime = datePickerHelper(event.time, dateTV)

        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val weight = weightET.text.toString().toIntOrNull()
            if (weight != null) {
                event.time = pickedTime.time.time / 1000
                event.quantity = weight
                onPositive()
            } else {
                Toast.makeText(this, R.string.toast_integer_error, Toast.LENGTH_SHORT).show()
            }

            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun addTemperatureEvent(event: LunaEvent) {
        setToPreviousQuantity(event)
        askTemperatureValue(event, true) { saveEvent(event) }
    }

    fun askTemperatureValue(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        // Show number picker dialog
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_temperature, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val tempSlider = dialogView.findViewById<Slider>(R.id.dialog_temperature_value)
        val range = NumericUtils(this).getValidEventQuantityRange(LunaEvent.Type.TEMPERATURE)!!
        tempSlider.valueFrom = range.first.toFloat()
        tempSlider.valueTo = range.second.toFloat()
        tempSlider.value = if (event.quantity == 0) {
            range.third.toFloat() // default
        } else {
            event.quantity.toFloat() / 10
        }

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedTime = datePickerHelper(event.time, dateTV)
        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        val tempTextView = dialogView.findViewById<TextView>(R.id.dialog_temperature_display)
        tempTextView.text = tempSlider.value.toString()
        tempSlider.addOnChangeListener({ s, v, b -> tempTextView.text = v.toString() })

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            event.time = pickedTime.time.time / 1000
            event.quantity = (tempSlider.value * 10).toInt()   // temperature in tenth of a grade
            onPositive()
            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun datePickerHelper(time: Long, dateTextView: TextView, onChange: (Long) -> Unit = {}): Calendar {
        dateTextView.text = DateUtils.formatDateTime(time)

        val dateTime = Calendar.getInstance()
        dateTime.time = Date(time * 1000)
        dateTextView.setOnClickListener {
            // Show datetime picker
            val startYear = dateTime.get(Calendar.YEAR)
            val startMonth = dateTime.get(Calendar.MONTH)
            val startDay = dateTime.get(Calendar.DAY_OF_MONTH)
            val startHour = dateTime.get(Calendar.HOUR_OF_DAY)
            val startMinute = dateTime.get(Calendar.MINUTE)

            DatePickerDialog(this, { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        dateTime.set(year, month, day, hour, minute)
                        dateTextView.text = DateUtils.formatDateTime(dateTime.time.time / 1000)
                        onChange.invoke(dateTime.time.time / 1000)
                    },
                    startHour,
                    startMinute,
                    android.text.format.DateFormat.is24HourFormat(this@MainActivity)
                ).show()
            }, startYear, startMonth, startDay).show()
        }

        return dateTime
    }

    fun addSleepEvent(event: LunaEvent) {
        askSleepValue(event, true) { saveEvent(event) }
    }

    fun askSleepValue(event: LunaEvent, hideDurationButtons: Boolean, onPositive: () -> Unit) {
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_duration, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val durationTextView = dialogView.findViewById<TextView>(R.id.dialog_date_duration)
        val datePicker = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val durationButtons = dialogView.findViewById<LinearLayout>(R.id.duration_buttons)
        val durationNowButton = dialogView.findViewById<Button>(R.id.dialog_date_duration_now)
        val durationMinus5Button = dialogView.findViewById<Button>(R.id.dialog_date_duration_minus5)
        val durationPlus5Button = dialogView.findViewById<Button>(R.id.dialog_date_duration_plus5)

        val currentDurationTextColor = durationTextView.currentTextColor
        val invalidDurationTextColor = ContextCompat.getColor(this, R.color.danger)

        var duration = event.quantity

        fun isValidTime(timeSeconds: Long, durationSeconds: Int): Boolean {
            val now = System.currentTimeMillis() / 1000
            return (timeSeconds + durationSeconds) <= now && durationSeconds < (24 * 60 * 60)
        }

        val onDateChange = { time: Long ->
            durationTextView.setTextColor(currentDurationTextColor)

            if (duration == 0) {
                // baby is sleeping
                durationTextView.text = "💤"
            } else {
                durationTextView.text = DateUtils.formatTimeDuration(applicationContext, duration.toLong())
                if (!isValidTime(time, duration)) {
                    durationTextView.setTextColor(invalidDurationTextColor)
                }
            }
        }

        val pickedDateTime = datePickerHelper(event.time, datePicker, onDateChange)

        onDateChange(pickedDateTime.time.time / 1000)

        if (hideDurationButtons) {
            durationButtons.visibility = View.GONE
            d.setMessage(getString(R.string.log_sleep_dialog_description_start))
        } else {
            durationButtons.visibility = View.VISIBLE
            d.setMessage(event.getDialogMessage(this))

            fun adjust(minutes: Int) {
                duration += minutes * 60
                if (duration < 0) {
                    duration = 0
                }
                onDateChange(pickedDateTime.time.time / 1000)
            }

            durationMinus5Button.setOnClickListener { adjust(-5) }
            durationPlus5Button.setOnClickListener { adjust(5) }

            durationNowButton.setOnClickListener {
                val now = System.currentTimeMillis() / 1000
                val start = pickedDateTime.time.time / 1000
                if (now > start) {
                    duration = (now - start).toInt()
                    duration -= duration % 60 // prevent printing of seconds
                    onDateChange(pickedDateTime.time.time / 1000)
                }
            }
        }

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val time = pickedDateTime.time.time / 1000
            if (isValidTime(time, duration)) {
                event.time = time
                event.quantity = duration
                onPositive()
            } else {
                Toast.makeText(this, R.string.toast_date_error, Toast.LENGTH_SHORT).show()
            }
            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun addAmountEvent(event: LunaEvent) {
        setToPreviousQuantity(event)
        askAmountValue(event, true) { saveEvent(event) }
    }

    fun askAmountValue(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_amount, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val spinner = dialogView.findViewById<Spinner>(R.id.dialog_amount_value)
        spinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.AmountLabels,
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.setSelection(event.quantity.coerceIn(0, spinner.count - 1))

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedTime = datePickerHelper(event.time, dateTV)
        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            event.time = pickedTime.time.time / 1000
            event.quantity = spinner.selectedItemPosition
            onPositive()
            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun addPlainEvent(event: LunaEvent) {
        askDateValue(event, true) { saveEvent(event) }
    }

    // Ask to edit events to be edited (only affects date)
    fun askDateValue(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_plain, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedDateTime = datePickerHelper(event.time, dateTV)
        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            event.time = pickedDateTime.time.time / 1000
            onPositive()
            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()
    }

    fun addNoteEvent(event: LunaEvent) {
        askNotes(event, true) { saveEvent(event) }
    }

    fun askNotes(event: LunaEvent, showTime: Boolean, onPositive: () -> Unit) {
        val useQuantity = (event.type != LunaEvent.Type.NOTE)

        val d = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_notes, null)
        d.setTitle(event.getDialogTitle(this))
        d.setMessage(event.getDialogMessage(this))
        d.setView(dialogView)
        val notesET = dialogView.findViewById<EditText>(R.id.notes_edittext)
        val qtyET = dialogView.findViewById<EditText>(R.id.notes_qty_edittext)

        val dateTV = dialogView.findViewById<TextView>(R.id.dialog_date_picker)
        val pickedTime = datePickerHelper(event.time, dateTV)

        if (!showTime) {
            dateTV.visibility = View.GONE
        }

        val nextTextView = dialogView.findViewById<TextView>(R.id.notes_template_next)
        val prevTextView = dialogView.findViewById<TextView>(R.id.notes_template_prev)
        val templates = allEvents.filter { it.type == event.type }.distinctBy { it.notes }.sortedBy { it.time }

        fun updateContent(current: LunaEvent) {
            val prevEvent = getPreviousSameEvent(current, templates)
            var nextEvent = getNextSameEvent(current, templates)

            notesET.setText(current.notes)
            if (useQuantity) {
                qtyET.setText(current.quantity.toString())
            }

            if (nextEvent == null && current != event) {
                nextEvent = event
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

        notesET.setText(event.notes)

        if (useQuantity) {
            qtyET.setText(event.quantity.toString())
        } else {
            qtyET.visibility = View.GONE
        }

        updateContent(event)

        d.setPositiveButton(android.R.string.ok) { dialogInterface, i ->
            val notes = notesET.text.toString().trim()

            if (useQuantity) {
                val quantity = qtyET.text.toString().toIntOrNull()
                if (quantity != null) {
                    event.time = pickedTime.time.time / 1000
                    event.notes = notes
                    event.quantity = quantity
                    onPositive()
                } else {
                    Toast.makeText(this, R.string.toast_integer_error, Toast.LENGTH_SHORT).show()
                }

            } else {
                event.time = pickedTime.time.time / 1000
                event.notes = notes
                onPositive()
            }

            dialogInterface.dismiss()
        }

        d.setNegativeButton(android.R.string.cancel) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

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

    fun setToPreviousQuantity(event: LunaEvent) {
        val prev = getPreviousSameEvent(event, allEvents)
        if (prev != null) {
            event.quantity = prev.quantity
        }
    }

    fun getPreviousSameEvent(event: LunaEvent, items: List<LunaEvent>): LunaEvent? {
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

    fun getNextSameEvent(event: LunaEvent, items: List<LunaEvent>): LunaEvent? {
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

    fun showEventDetailDialog(originalEvent: LunaEvent) {
        val event = LunaEvent(originalEvent)

        fun eventValuesChanged(): Boolean {
            return (event.time != originalEvent.time
                || event.quantity != originalEvent.quantity
                || event.notes != originalEvent.notes)
        }

        // Do not update list while the detail is shown, to avoid changing the object below while it is changed by the user
        pauseLogbookUpdate = true

        val d = AlertDialog.Builder(this)
        d.setTitle(R.string.dialog_event_detail_title)

        val dialogView = layoutInflater.inflate(R.layout.dialog_event_details, null)
        val emojiTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_emoji)
        val descriptionTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_description)
        val dateTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_date)
        val dateEndTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_date_end)
        val quantityTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_quantity)
        val notesTextView = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_notes)

        emojiTextView.text = event.getHeaderEmoji(this)
        descriptionTextView.text = event.getDialogTitle(this)

        d.setView(dialogView)

        d.setNeutralButton(R.string.dialog_event_detail_delete_button) { dialogInterface, i ->
            deleteEvent(originalEvent)
            dialogInterface.dismiss()
        }

        d.setNegativeButton(R.string.dialog_event_detail_save_button) { dialogInterface, i ->
            if (eventValuesChanged()) {
                originalEvent.time = event.time
                originalEvent.quantity = event.quantity
                originalEvent.notes = event.notes
                saveEvent(originalEvent)
            }

            dialogInterface.dismiss()
        }

        d.setPositiveButton(R.string.dialog_event_detail_close_button) { dialogInterface, i ->
            dialogInterface.dismiss()
        }

        val alertDialog = d.create()
        alertDialog.show()

        alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(
            ContextCompat.getColor(this, R.color.danger)
        )

        alertDialog.setOnDismissListener {
            // Resume logbook update
            pauseLogbookUpdate = false
        }

        val updateValues = {
            quantityTextView.text = NumericUtils(this).formatEventQuantity(event)
            notesTextView.text = event.notes
            if (event.type == LunaEvent.Type.SLEEP && event.quantity > 0) {
                dateEndTextView.text = DateUtils.formatDateTime(event.time + event.quantity)
                dateEndTextView.visibility = View.VISIBLE
            } else {
                dateEndTextView.visibility = View.GONE
            }

            alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility = if (eventValuesChanged()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        updateValues()

        datePickerHelper(event.time, dateTextView, { newTime: Long ->
            event.time = newTime
            updateValues()
        })

        quantityTextView.setOnClickListener {
            when (event.type) {
                LunaEvent.Type.BABY_BOTTLE -> askBabyBottleContent(event, false, updateValues)
                LunaEvent.Type.WEIGHT -> askWeightValue(event, false, updateValues)
                LunaEvent.Type.DIAPERCHANGE_POO,
                LunaEvent.Type.DIAPERCHANGE_PEE,
                LunaEvent.Type.PUKE -> askAmountValue(event, false, updateValues)
                LunaEvent.Type.TEMPERATURE -> askTemperatureValue(event, false, updateValues)
                LunaEvent.Type.NOTE -> askNotes(event, false, updateValues)
                LunaEvent.Type.SLEEP -> askSleepValue(event, false, updateValues)
                else -> {
                    Log.w(TAG, "Unexpected type: ${event.type}")
                }
            }
        }

        notesTextView.setOnClickListener {
            when (event.type) {
                LunaEvent.Type.FOOD,
                LunaEvent.Type.MEDICINE,
                LunaEvent.Type.NOTE -> askNotes(event, false, updateValues)
                else -> {
                    Log.w(TAG, "Unexpected type: ${event.type}")
                }
            }
        }

        // show optional signature
        if (event.signature.isNotEmpty()) {
            val signatureTextEdit = dialogView.findViewById<TextView>(R.id.dialog_event_detail_type_signature)
            signatureTextEdit.text =  String.format(getString(R.string.dialog_event_detail_signature), event.signature)
            signatureTextEdit.visibility = View.VISIBLE
        }

        // create link to prevent event of the same type
        val previousTextView = dialogView.findViewById<TextView>(R.id.dialog_event_previous)
        val previousEvent = getPreviousSameEvent(event, allEvents)
        if (previousEvent != null) {
            val emoji = previousEvent.getHeaderEmoji(applicationContext)
            val time = DateUtils.formatTimeDuration(applicationContext, event.time - previousEvent.time)
            previousTextView.text = String.format("⬅️ %s %s", emoji, time)
            previousTextView.setOnClickListener {
                alertDialog.cancel()
                showEventDetailDialog(previousEvent)
            }
        } else {
            previousTextView.visibility = View.GONE
        }

        // create link to next event of the same type
        val nextTextView = dialogView.findViewById<TextView>(R.id.dialog_event_next)
        val nextEvent = getNextSameEvent(event, allEvents)
        if (nextEvent != null) {
            val emoji = nextEvent.getHeaderEmoji(applicationContext)
            val time = DateUtils.formatTimeDuration(applicationContext, nextEvent.time - event.time)
            nextTextView.text = String.format("%s %s ➡️", time, emoji)
            nextTextView.setOnClickListener {
                alertDialog.cancel()
                showEventDetailDialog(nextEvent)
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
                            val em = e.getHeaderEmoji(this@MainActivity)
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

    fun deleteEvent(event: LunaEvent) {
        // Update view
        savingEvent(true)

        // Update data
        setLoading(true)
        logbook?.logs?.remove(event)
        recyclerView.adapter?.notifyDataSetChanged()
        saveLogbook()
    }

    fun saveEvent(event: LunaEvent) {
        if (allEvents.contains(event)) {
            // event was modified
            logbook?.sort()
            recyclerView.adapter?.notifyDataSetChanged()
            saveLogbook()
        } else {
            // add new event
            savingEvent(true)
            setLoading(true)
            if (signature.isNotEmpty()) {
                event.signature = signature
            }
            logbook?.logs?.add(0, event)
            logbook?.sort()
            recyclerView.adapter?.notifyDataSetChanged()
            recyclerView.smoothScrollToPosition(0)
            saveLogbook(event)

            // Check logbook size to avoid OOM errors
            if (logbook?.isTooBig() == true) {
                askToTrimLogbook()
            }
        }
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

    private fun showCreateDialog(type: LunaEvent.Type) {
        val event = LunaEvent(type)
        when (type) {
            LunaEvent.Type.BABY_BOTTLE -> addBabyBottleEvent(event)
            LunaEvent.Type.WEIGHT -> addWeightEvent(event)
            LunaEvent.Type.BREASTFEEDING_LEFT_NIPPLE -> addPlainEvent(event)
            LunaEvent.Type.BREASTFEEDING_BOTH_NIPPLE -> addPlainEvent(event)
            LunaEvent.Type.BREASTFEEDING_RIGHT_NIPPLE -> addPlainEvent(event)
            LunaEvent.Type.DIAPERCHANGE_POO -> addAmountEvent(event)
            LunaEvent.Type.DIAPERCHANGE_PEE -> addAmountEvent(event)
            LunaEvent.Type.MEDICINE -> addNoteEvent(event)
            LunaEvent.Type.ENEMA -> addNoteEvent(event)
            LunaEvent.Type.NOTE -> addNoteEvent(event)
            LunaEvent.Type.COLIC -> addPlainEvent(event)
            LunaEvent.Type.TEMPERATURE -> addTemperatureEvent(event)
            LunaEvent.Type.FOOD -> addNoteEvent(event)
            LunaEvent.Type.PUKE -> addAmountEvent(event)
            LunaEvent.Type.BATH -> addPlainEvent(event)
            LunaEvent.Type.SLEEP -> addSleepEvent(event)
            LunaEvent.Type.UNKNOWN -> {} // ignore
        }
    }

    private fun showOverflowPopupWindow(anchor: View) {
        if (showingOverflowPopupWindow)
            return

        PopupWindow(anchor.context).apply {
            isOutsideTouchable = true
            val inflater = LayoutInflater.from(anchor.context)
            contentView = inflater.inflate(R.layout.more_events_popup, null)

            // Add statistics (hard coded)
            contentView.findViewById<View>(R.id.button_statistics).setOnClickListener {
                if (logbook != null && !pauseLogbookUpdate) {
                    val i = Intent(applicationContext, StatisticsActivity::class.java)
                    i.putExtra("LOOGBOOK_NAME", logbook!!.name)
                    startActivity(i)
                } else {
                    Toast.makeText(applicationContext, "No logbook selected!", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }

            val linearLayout = contentView.findViewById<LinearLayout>(R.id.layout_list)

            // Add buttons to create other events
            for (type in currentPopupItems) {
                val view = layoutInflater.inflate(R.layout.more_events_popup_item, linearLayout, false)
                val textView = view.findViewById<TextView>(R.id.tv)
                textView.text = LunaEvent.getPopupItemTitle(applicationContext, type)
                textView.setOnClickListener {
                    showCreateDialog(type)
                    dismiss()
                }
                linearLayout.addView(textView)
            }
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

package it.danieleverducci.lunatracker

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet
import it.danieleverducci.lunatracker.entities.LunaEvent
import utils.DateUtils.Companion.formatTimeDuration
import utils.NumericUtils
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class StatisticsActivity :  AppCompatActivity() {
    lateinit var barChart: BarChart
    lateinit var noDataTextView: TextView
    lateinit var eventTypeSelection: Spinner
    lateinit var dataTypeSelection: Spinner
    lateinit var timeRangeSelection: Spinner

    // default selection
    var eventTypeSelectionValue = "BOTTLE"
    var dataTypeSelectionValue = "AMOUNT"
    var timeRangeSelectionValue = "DAY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_statistics)

        val logbookName = intent.getStringExtra("LOOGBOOK_NAME")
        if (logbookName == null) {
            finish()
            return
        }

        noDataTextView = findViewById(R.id.no_data)

        barChart = findViewById(R.id.bar_chart)
        barChart.setBackgroundColor(Color.WHITE)
        barChart.description.text = "$logbookName"
        barChart.setDrawValueAboveBar(false)

        barChart.axisLeft.setAxisMinimum(0F)
        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisLeft.setDrawLabels(false)

        barChart.axisRight.setDrawGridLines(false)
        barChart.axisRight.setDrawLabels(false)

        barChart.xAxis.setDrawGridLines(true)
        barChart.xAxis.setDrawLabels(true)
        barChart.xAxis.setDrawAxisLine(false)

        eventTypeSelection = findViewById(R.id.type_selection)
        dataTypeSelection = findViewById(R.id.data_selection)
        timeRangeSelection = findViewById(R.id.time_selection)

        setupSpinner(eventTypeSelectionValue,
            R.id.type_selection,
            R.array.StatisticsTypeLabels,
            R.array.StatisticsTypeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        eventTypeSelectionValue = newValue
                        //Log.d("event", "new value: $newValue")
                        updateGraph()
                    }
                }
            }
        )

        setupSpinner(dataTypeSelectionValue,
            R.id.data_selection,
            R.array.StatisticsDataLabels,
            R.array.StatisticsDataValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        dataTypeSelectionValue = newValue
                        //Log.d("event", "new value: $newValue")
                        updateGraph()
                    }
                }
            }
        )

        setupSpinner(timeRangeSelectionValue,
            R.id.time_selection,
            R.array.StatisticsTimeLabels,
            R.array.StatisticsTimeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    if (newValue != null) {
                        timeRangeSelectionValue = newValue
                        //Log.d("event", "new value: $newValue")
                        updateGraph()
                    }
                }
            }
        )

       updateGraph()
    }

    fun updateGraph() {
        val eventType = when (eventTypeSelectionValue) {
            "BOTTLE" -> LunaEvent.TYPE_BABY_BOTTLE
            "SLEEP" -> LunaEvent.TYPE_SLEEP
            else -> {
                Log.e(TAG, "unhandled eventTypeSelectionValue: $eventTypeSelectionValue")
                return
            }
        }
        val allEvents = MainActivity.allEvents.filter { it.type == eventType }.sortedBy { it.time }

        val values = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        val unixToSpan  = when (timeRangeSelectionValue) {
            "DAY" -> { unix: Long -> unixToDays(unix) }
            "WEEK" -> { unix: Long -> unixToWeeks(unix) }
            "MONTH" -> { unix: Long -> unixToMonths(unix) }
            else -> {
                Log.e(TAG, "Invalid timeRangeSelectionValue: $timeRangeSelectionValue")
                return
            }
        }

        val spanToUnix = when (timeRangeSelectionValue) {
            "DAY" -> { span: Int -> daysToUnix(span) }
            "WEEK" -> { span: Int -> weeksToUnix(span) }
            "MONTH" -> { span: Int ->  monthsToUnix(span) }
            else -> {
                Log.e(TAG, "Invalid timeRangeSelectionValue: $timeRangeSelectionValue")
                return
            }
        }

        fun spanToLabel(span: Int): String {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(1000 * spanToUnix(span))
            val year = dateTime.get(Calendar.YEAR)
            val month = dateTime.get(Calendar.MONTH) + 1 // month starts at 0
            val week = dateTime.get(Calendar.WEEK_OF_YEAR)
            val day = dateTime.get(Calendar.DAY_OF_MONTH)
            return when (timeRangeSelectionValue) {
                "DAY" -> "$day/$month/$year"
                "WEEK" -> "$week/$year"
                "MONTH" -> "$month/$year"
                else -> {
                    Log.e(TAG, "Invalid timeRangeSelectionValue: $timeRangeSelectionValue")
                    "?"
                }
            }
        }

        if (allEvents.isNotEmpty()) {
            barChart.visibility = View.VISIBLE
            noDataTextView.visibility = View.GONE

            // unix time span of all events
            val startUnix = allEvents.minOf { it.getStartTime() }
            val endUnix = allEvents.maxOf { it.getEndTime() }

            // convert to days, weeks or months
            val startSpan = unixToSpan(startUnix)
            val endSpan = unixToSpan(endUnix)

            //Log.d(TAG, "startUnix: $startUnix (${Date(1000 * startUnix)}), startSpan: $startSpan (${Date(1000 * spanToUnix(startSpan))}), endUnix: $endUnix (${Date(1000 * endUnix)}), endSpan: $endSpan (${Date(1000 * spanToUnix(endSpan))})")
            for (span in startSpan..endSpan) {
                values.add(BarEntry(values.size.toFloat(), 0F))
                labels.add(spanToLabel(span))
            }

            for (event in allEvents) {
                if (dataTypeSelectionValue == "AMOUNT") {
                    if (eventTypeSelectionValue == "SLEEP") {
                        // a sleep event can span to another day
                        // distribute sleep time over the days
                        val startUnix = event.getStartTime()
                        val endUnix = event.getEndTime()
                        val begIndex = unixToSpan(startUnix)
                        val endIndex = unixToSpan(endUnix)
                        var mid = startUnix

                        //Log.d(TAG, "startUnix: ${Date(startUnix * 1000)}, endUnix: ${Date(endUnix * 1000)}, begIndex: $begIndex, endIndex: $endIndex (index diff: ${endIndex - begIndex})")
                        for (i in begIndex..endIndex) {
                            val spanBegin = spanToUnix(i)
                            val spanEnd =  spanToUnix(i + 1)
                            //Log.d(TAG, "mid: ${Date(mid * 1000)}, spanBegin: ${Date(spanBegin * 1000)}, spanEnd: ${Date(spanEnd * 1000)}, endUnix: ${Date(endUnix * 1000)}")
                            val beg = max(mid, spanBegin)
                            val end = min(endUnix, spanEnd)
                            val index = i - startSpan
                            val duration = end - beg
                            //Log.d(TAG, "[$index] beg: ${Date(beg * 1000)}, end: ${Date(end * 1000)}, ${formatTimeDuration(this, duration)}")

                            values[index].y += duration
                            mid = end
                        }
                    } else {
                        val index = unixToSpan(event.time) - startSpan
                        //Log.d(TAG, "[${index}] ${event.quantity}")
                        values[index].y += event.quantity
                    }
                } else {
                    val index = unixToSpan(event.time) - startSpan
                    values[index].y += 1
                }
            }
        } else {
            barChart.visibility = View.GONE
            noDataTextView.visibility = View.VISIBLE
        }

        barChart.xAxis.setLabelCount(min(values.size, 24))

        val set1 = BarDataSet(values, "")

        set1.setDrawValues(true)
        set1.setDrawIcons(false)

        val dataSets = ArrayList<IBarDataSet?>()
        dataSets.add(set1)

        val data = BarData(dataSets)

        data.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                //Log.d(TAG, "getFormattedValue ${dataTypeSelectionValue} ${eventTypeSelectionValue}")
                return when (dataTypeSelectionValue) {
                    "EVENT" -> value.toInt().toString()
                    "AMOUNT" -> when (eventTypeSelectionValue) {
                        "BOTTLE" -> NumericUtils(applicationContext).formatEventQuantity(LunaEvent.TYPE_BABY_BOTTLE, value.toInt())
                        "SLEEP" -> NumericUtils(applicationContext).formatEventQuantity(LunaEvent.TYPE_SLEEP, value.toInt())
                        else -> {
                            Log.e(TAG, "unhandled eventTypeSelectionValue: $eventTypeSelectionValue")
                            value.toInt().toString()
                        }
                    } else -> {
                        Log.e(TAG, "unhandled dataTypeSelectionValue: $dataTypeSelectionValue")
                        value.toInt().toString()
                    }
                }
            }
        })

        barChart.xAxis.valueFormatter = object: ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return labels.getOrElse(value.toInt(), {"?"})
            }
        }

        data.setValueTextSize(12f)
        barChart.setData(data)
        barChart.invalidate()
    }

    private interface SpinnerItemSelected {
        fun call(newValue: String?)
    }

    private fun setupSpinner(
        currentValue: String,
        spinnerId: Int,
        arrayId: Int,
        arrayValuesId: Int,
        callback: SpinnerItemSelected
    ) {
        val arrayValues = resources.getStringArray(arrayValuesId)
        val spinner = findViewById<Spinner>(spinnerId)
        val spinnerAdapter =
            ArrayAdapter.createFromResource(this, arrayId, R.layout.statistics_spinner_item)

        spinner.adapter = spinnerAdapter
        spinner.setSelection(arrayValues.indexOf(currentValue))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var check = 0
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos >= arrayValues.size) {
                    Toast.makeText(
                        this@StatisticsActivity,
                        "pos out of bounds: $arrayValues", Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                if (check++ > 0) {
                    callback.call(arrayValues[pos])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // ignore
            }
        }
    }

    companion object {
        const val TAG = "StatisticsActivity"

        fun unixToMonths(seconds: Long): Int {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR)
            val months = dateTime.get(Calendar.MONTH)
            return 12 * years + months
        }

        fun monthsToUnix(months: Int): Long {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, months / 12)
            dateTime.set(Calendar.MONTH, months % 12)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }

        fun unixToWeeks(seconds: Long): Int {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR)
            val weeks = dateTime.get(Calendar.WEEK_OF_YEAR)
            return 52 * years + weeks
        }

        fun weeksToUnix(weeks: Int): Long {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, weeks / 52)
            dateTime.set(Calendar.WEEK_OF_YEAR, weeks % 52)
            dateTime.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }

        fun unixToDays(seconds: Long): Int {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR)
            val days = dateTime.get(Calendar.DAY_OF_YEAR)
            return 365 * years + days
        }

        // convert from days to Date
        fun daysToUnix(days: Int): Long {
            val dateTime = Calendar.getInstance()
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, days / 365)
            dateTime.set(Calendar.DAY_OF_YEAR, days % 365)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }
    }
}

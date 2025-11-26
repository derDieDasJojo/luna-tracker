package it.danieleverducci.lunatracker

import android.graphics.Canvas
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
import androidx.core.graphics.toColorInt
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.dataprovider.BarDataProvider
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.renderer.HorizontalBarChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler
import it.danieleverducci.lunatracker.entities.LunaEvent
import utils.DateUtils
import utils.NumericUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class StatisticsActivity : AppCompatActivity() {
    var lastToastShown = 0L

    lateinit var barChart: BarChart
    lateinit var noDataTextView: TextView
    lateinit var graphTypeSpinner: Spinner
    lateinit var timeRangeSpinner: Spinner

    lateinit var unixToSpan: (Long) -> Int
    lateinit var spanToUnix: (Int) -> Long

    enum class GraphType {
        BOTTLE_EVENTS,
        BOTTLE_SUM,
        SLEEP_SUM,
        SLEEP_EVENTS,
        SLEEP_PATTERN
    }

    enum class TimeRange {
        DAY,
        WEEK,
        MONTH
    }

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
        barChart.description.text = logbookName
        barChart.setDrawValueAboveBar(false)

        barChart.axisLeft.setAxisMinimum(0F)
        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisLeft.setDrawLabels(false)

        barChart.axisRight.setDrawGridLines(false)
        barChart.axisRight.setDrawLabels(false)

        barChart.xAxis.setDrawLabels(true)
        barChart.xAxis.setDrawAxisLine(false)

        barChart.isScaleXEnabled = false
        barChart.isScaleYEnabled = true

        graphTypeSpinner = findViewById(R.id.graph_type_selection)
        timeRangeSpinner = findViewById(R.id.time_range_selection)

        setupSpinner(graphTypeSelection.name,
            R.id.graph_type_selection,
            R.array.StatisticsTypeLabels,
            R.array.StatisticsTypeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    //Log.d("event", "new value: $newValue")
                    newValue ?: return
                    graphTypeSelection = GraphType.valueOf(newValue)
                    showGraph()
                }
            }
        )

        setupSpinner(timeRangeSelection.name,
            R.id.time_range_selection,
            R.array.StatisticsTimeLabels,
            R.array.StatisticsTimeValues,
            object : SpinnerItemSelected {
                override fun call(newValue: String?) {
                    //Log.d("event", "new value: $newValue")
                    newValue ?: return
                    timeRangeSelection = TimeRange.valueOf(newValue)
                    setSpans()
                    showGraph()
                }
            }
        )

        setSpans()
        showGraph()
    }

    fun setSpans() {
        unixToSpan  = when (timeRangeSelection) {
            TimeRange.DAY -> { unix: Long -> unixToDays(unix) }
            TimeRange.WEEK -> { unix: Long -> unixToWeeks(unix) }
            TimeRange.MONTH -> { unix: Long -> unixToMonths(unix) }
        }

        spanToUnix = when (timeRangeSelection) {
            TimeRange.DAY -> { span: Int -> daysToUnix(span) }
            TimeRange.WEEK -> { span: Int -> weeksToUnix(span) }
            TimeRange.MONTH -> { span: Int -> monthsToUnix(span) }
        }
    }

    data class SleepRange(val start: Long, var end: Long)

    fun toSleepRanges(events: List<LunaEvent>): ArrayList<SleepRange> {
        val ranges = arrayListOf<SleepRange>()
        val now = System.currentTimeMillis() / 1000

        // Transform events into time ranges.
        // Merge overlapping times and extend
        // ongoing sleep events until now.
        var warningShown = false
        for (event in events) {
            val startTime = event.time
            val endTime = if (event.quantity == 0) {
                now
            } else {
                event.time + event.quantity
            }

            // handle overlap
            val previousRange = ranges.lastOrNull()
            if (previousRange != null && previousRange.end > startTime) {
                // cap previous range to avoid overlap
                previousRange.end = startTime
                if (!warningShown) {
                    Toast.makeText(applicationContext, "Overlapping sleep event at ${DateUtils.formatDateTime(startTime)}", Toast.LENGTH_SHORT).show()
                    warningShown = true
                }
            }

            ranges.add(SleepRange(startTime, endTime))
        }

        return ranges
    }

    fun showSleepPatternBarGraphSlotted(state: GraphState) {
        val ranges = toSleepRanges(state.events)
        val values = ArrayList<BarEntry>()
        val stack = ArrayList(List(state.endSpan - state.startSpan + 1) { IntArray(24 * 60 * 60 / SLEEP_PATTERN_GRANULARITY) })

        Log.d(TAG, "stack.size: ${stack.size}, array.size: ${stack[0].size}, dayCounter.daysWithData.size: ${state.dayCounter.daysWithData.size}")

        fun stackValuePattern(index: Int, spanBegin: Long, spanEnd: Long, begin: Long, end: Long) {
            val beginDays = unixToDays(begin)
            val endDays = unixToDays(end)
            var mid = begin

            //Log.d(TAG, "stackValuePattern: ${beginDays}..${endDays}")
            for (i in beginDays..endDays) {
                // i is the days/weeks/months since unix epoch
                val dayBegin = daysToUnix(i)
                val dayEnd =  daysToUnix(i + 1)
                val sleepBegin = max(mid, dayBegin)
                val sleepEnd = min(end, dayEnd)

                if (sleepBegin != sleepEnd) {
                    assert(dayBegin <= dayEnd)
                    assert(sleepBegin <= sleepEnd)
                    val iBegin = (sleepBegin - dayBegin) / SLEEP_PATTERN_GRANULARITY
                    val iEnd = iBegin + (sleepEnd - sleepBegin) / SLEEP_PATTERN_GRANULARITY
                    //Log.d(TAG, "index: $index, iBegin: $iBegin, iEnd: $iEnd, dayBegin: ${Date(dayBegin * 1000)}, dayEnd: ${Date(dayEnd * 1000)}, sleepBegin: ${Date(sleepBegin * 1000)}, sleepEnd: ${Date(sleepEnd * 1000)}")
                    for (j in iBegin..<iEnd) {
                        stack[index][j.toInt()] += 1
                    }
                }
                mid = sleepEnd
            }
        }

        for (range in ranges) {
            // a sleep event can span to another day
            // distribute sleep time over the days
            val startUnix = range.start
            val endUnix = range.end
            val begIndex = unixToSpan(startUnix)
            val endIndex = unixToSpan(endUnix)
            var mid = startUnix

            //Log.d(TAG, "startUnix: ${Date(startUnix * 1000)}, endUnix: ${Date(endUnix * 1000)}, begIndex: $begIndex, endIndex: $endIndex (index diff: ${endIndex - begIndex})")
            for (i in begIndex..endIndex) {
                // i is the days/weeks/months since unix epoch
                val spanBegin = spanToUnix(i)
                val spanEnd =  spanToUnix(i + 1)
                //Log.d(TAG, "mid: ${Date(mid * 1000)}, spanBegin: ${Date(spanBegin * 1000)}, spanEnd: ${Date(spanEnd * 1000)}, beginUnix: ${Date(startUnix * 1000)} endUnix: ${Date(endUnix * 1000)}")
                val sleepBegin = max(mid, spanBegin)
                val sleepEnd = min(endUnix, spanEnd)
                val index = i - state.startSpan
                val duration = sleepEnd - sleepBegin
                //Log.d(TAG, "[$index] sleepBegin: ${Date(sleepBegin * 1000)}, sleepEnd: ${Date(sleepEnd * 1000)}, ${DateUtils.formatTimeDuration(this, duration)}")

                state.dayCounter.setDaysWithData(sleepBegin, sleepEnd)
                stackValuePattern(index, spanBegin, spanEnd, sleepBegin, sleepEnd)

                mid = sleepEnd
            }
        }

        fun mapColor(occurrences: Int, maxOccurrences: Int): Int {
            // occurrences: number of reported sleeps in a specific time slot
            // maxOccurrences: maximum number of days with data that can contribute to maxOccurrences
            assert(maxOccurrences > 0)
            assert(occurrences <= maxOccurrences)

            // map to color
            val q = occurrences.toFloat() / maxOccurrences.toFloat()
            val i = q * (SLEEP_PATTERN_COLORS.size - 1).toFloat()
            return SLEEP_PATTERN_COLORS[i.toInt()]
        }

        val allColors = ArrayList<Int>()

        // convert array of time slots that represent a day to value and color arrays used by chart library
        for ((index, dayArray) in stack.withIndex()) {
            val daysWithData = state.dayCounter.countDaysWithData(spanToUnix(state.startSpan + index), spanToUnix(state.startSpan + index + 1))

            //Log.d(TAG, "index: $index: daysWithData: $daysWithData, dayArray: ${dayArray.joinToString { it.toString() }}")
            val vals = ArrayList<Float>()

            var prevIndex = -1 // time slot index
            var prevValue = -1 // number of entries we have found for time slot
            for ((i, v) in dayArray.withIndex()) {
                if (i == 0) {
                    prevIndex = i
                    prevValue = v
                } else if (prevValue != v) {
                    vals.add((i - prevIndex).toFloat())
                    allColors.add(mapColor(prevValue.coerceAtMost(daysWithData), daysWithData))
                    prevIndex = i
                    prevValue = v
                }
            }

            if (prevIndex != -1) {
                vals.add((dayArray.size - prevIndex).toFloat())
                allColors.add(mapColor(prevValue, daysWithData))
            }

            //Log.d(TAG, "Range $index, vals: ${vals.joinToString { it.toInt().toString() }}") //, allColors: ${allColors.joinToString { it.toString() }}")

            //Log.d(TAG, "index: ${index.toFloat()}")
            values.add(BarEntry(index.toFloat(), vals.toFloatArray()))
        }

        //Log.d(TAG, "daysWithData: ${state.dayCounter.daysWithData.joinToString()}")

        barChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null || h == null) {
                    return
                }

                val index = e.x.toInt()
                if (index !in 0..values.size) {
                    return
                }

                val value = values[index]
                if (value.yVals == null || h.stackIndex !in 0..value.yVals.size) {
                    return
                }

                if ((lastToastShown + TOAST_FREQUENCY_MS) > System.currentTimeMillis()) {
                    // only show one Toast message after another
                    return
                }

                val dayStartUnix = daysToUnix(unixToDays(state.startUnix) + index)

                //Log.d(TAG, "startUnix: ${Date(startUnix * 1000)}, x: ${e.x.toInt()}, dayStartUnix: ${Date(dayStartUnix * 1000)}")
                val startSeconds =
                    SLEEP_PATTERN_GRANULARITY * value.yVals.sliceArray(0..<h.stackIndex)
                        .fold(0) { acc, y -> acc + y.toInt() }
                val durationSeconds =
                    SLEEP_PATTERN_GRANULARITY * value.yVals[h.stackIndex].toInt()
                val endSeconds = startSeconds + durationSeconds

                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startTimeString =
                    format.format((dayStartUnix + startSeconds) * 1000).toString()
                val endTimeString =
                    format.format((dayStartUnix + endSeconds) * 1000).toString()
                val durationString = DateUtils.formatTimeDuration(applicationContext, durationSeconds.toLong())

                val daysWithData =
                    stack[e.x.toInt()][startSeconds / SLEEP_PATTERN_GRANULARITY]
                val daysWithDataMax = state.dayCounter.countDaysWithData(
                    spanToUnix(state.startSpan + index),
                    spanToUnix(state.startSpan + index + 1)
                )

                // percentage of days in this span where baby is asleep in this time slot
                val pc = if (daysWithDataMax > 0) {
                    (100F * daysWithData.toFloat() / daysWithDataMax.toFloat()).toInt()
                } else {
                    // no data for this day
                    0
                }

                Toast.makeText(
                    applicationContext,
                    "$startTimeString - $endTimeString ($durationString) - ${pc}%",
                    Toast.LENGTH_LONG
                ).show()
                lastToastShown = System.currentTimeMillis()
            }

            override fun onNothingSelected() {}
        })

        val set1 = BarDataSet(values, "")
        val data = BarData(set1)
        set1.colors = allColors

        set1.setDrawValues(false) // usually too many values
        set1.isHighlightEnabled = true

        set1.setDrawIcons(false)

        //Log.d(TAG, "showSleepPatternBarGraphSlotted; barChart.xAxis.labelCount: ${barChart.xAxis.labelCount}, barChart.visibleXRange: ${barChart.visibleXRange}, barChart.xAxis.isCenterAxisLabelsEnabled: ${barChart.xAxis.isCenterAxisLabelsEnabled}, barChart.isAutoScaleMinMaxEnabled: ${barChart.isAutoScaleMinMaxEnabled}, barChart.isScaleXEnabled: ${barChart.isScaleXEnabled}, barChart.isScaleYEnabled: ${barChart.isScaleYEnabled}")

        data.setValueTextSize(12f)
        barChart.setData(data)

        // does not work quite right yet
        //Log.d(TAG, "xChartMax: ${barChart.xChartMax}")
        //barChart.centerViewTo(barChart.xChartMax, 0F, YAxis.AxisDependency.LEFT)

        barChart.legend.isEnabled = false
        val valueCount = min(values.size, 24)
        barChart.setVisibleXRangeMaximum(valueCount.toFloat())
        barChart.xAxis.setLabelCount(valueCount)
        barChart.xAxis.setCenterAxisLabels(false)
        barChart.invalidate()
    }

    // Sleep pattern bars that do not use time slots.
    // This is useful/nicer for bars that only represent data of a singular days.
    fun showSleepPatternBarGraphDaily(state: GraphState) {
        val ranges = toSleepRanges(state.events)
        val values = ArrayList(List(state.endSpan - state.startSpan + 1) { BarEntry(it.toFloat(), FloatArray(0)) })

        // stack awake/sleep durations
        fun stackValuePattern(index: Int, spanBegin: Long, spanEnd: Long, begin: Long, end: Long) {
            assert(begin in spanBegin..spanEnd)
            assert(end in spanBegin..spanEnd)
            assert(begin <= end)

            val x = values[index].x
            val yVals  = values[index].yVals // alternating sleep/awake durations
            val y = yVals.fold(0F) { acc, next -> acc + next }

            // y value is seconds when last awake
            val awakeDuration = max(begin - spanBegin - y.toLong(), 0L)
            val sleepDuration = end - begin

            if ((awakeDuration + sleepDuration) > (spanEnd - spanBegin)) {
                Log.e(TAG, "Invalid sleep duration, exceeds day/week or month bounds => ignore value")
                return
            }

            // update value
            val newYVals = appendToFloatArray(yVals, awakeDuration.toFloat(), sleepDuration.toFloat())
            values[index] = BarEntry(x, newYVals)
        }

        for (range in ranges) {
            // a sleep event can span to another day
            // distribute sleep time over the days
            val startUnix = range.start
            val endUnix = range.end
            val begIndex = unixToSpan(startUnix)
            val endIndex = unixToSpan(endUnix)
            var mid = startUnix

            //Log.d(TAG, "startUnix: ${Date(startUnix * 1000)}, endUnix: ${Date(endUnix * 1000)}, begIndex: $begIndex, endIndex: $endIndex (index diff: ${endIndex - begIndex})")
            for (i in begIndex..endIndex) {
                // i is the days/weeks/months since unix epoch
                val spanBegin = spanToUnix(i)
                val spanEnd =  spanToUnix(i + 1)
                //Log.d(TAG, "mid: ${Date(mid * 1000)}, spanBegin: ${Date(spanBegin * 1000)}, spanEnd: ${Date(spanEnd * 1000)}, beginUnix: ${Date(startUnix * 1000)} endUnix: ${Date(endUnix * 1000)}")
                val sleepBegin = max(mid, spanBegin)
                val sleepEnd = min(endUnix, spanEnd)
                val index = i - state.startSpan
                val duration = sleepEnd - sleepBegin
                //Log.d(TAG, "[$index] sleepBegin: ${Date(sleepBegin * 1000)}, sleepEnd: ${Date(sleepEnd * 1000)}, ${DateUtils.formatTimeDuration(this, duration)}")

                state.dayCounter.setDaysWithData(sleepBegin, sleepEnd)
                stackValuePattern(index, spanBegin, spanEnd, sleepBegin, sleepEnd)

                mid = sleepEnd
            }
        }

        val set1 = BarDataSet(values, "")
        val data = BarData(set1)

        // awake phase color is transparent
        set1.colors = arrayListOf("#00000000".toColorInt(), "#72d7f5".toColorInt())
        set1.setDrawValues(false) // usually too many values
        set1.isHighlightEnabled = true

        barChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null || h == null) {
                    return
                }

                val index = e.x.toInt()
                if (index !in 0..values.size) {
                    return
                }

                val value = values[index]
                if (value.yVals == null || h.stackIndex !in 0..value.yVals.size) {
                    return
                }

                if ((lastToastShown + TOAST_FREQUENCY_MS) > System.currentTimeMillis()) {
                    // only show one Toast message after another
                    return
                }

                val duration = value.yVals[h.stackIndex].toInt()
                val durationString = DateUtils.formatTimeDuration(applicationContext, duration.toLong())

                val offsetUnix = spanToUnix(state.startSpan + e.x.toInt()) // start of the time span (day/week/month)
                val startUnix = offsetUnix + value.yVals.sliceArray(0..<h.stackIndex).fold(0) { acc, y -> acc + y.toInt() }
                val endUnix = startUnix + duration

                val format = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startTimeString = format.format(startUnix * 1000).toString()
                val endTimeString = format.format(endUnix * 1000).toString()
                Toast.makeText(applicationContext, "$startTimeString - $endTimeString ($durationString)", Toast.LENGTH_LONG).show()
                lastToastShown = System.currentTimeMillis()
            }

            override fun onNothingSelected() {}
        })
        //Log.d(TAG, "showSleepPatternBarGraphDaily: values.size: ${values.size}, barChart.xAxis.labelCount: ${barChart.xAxis.labelCount}")
        set1.setDrawIcons(false)

        data.setValueTextSize(12f)
        barChart.setData(data)

        //Log.d(TAG, "showSleepPatternBarGraphDaily: new barChart.xAxis.labelCount: ${barChart.xAxis.labelCount}")

        barChart.legend.isEnabled = false
        val valueCount = min(values.size, 24)
        barChart.setVisibleXRangeMaximum(valueCount.toFloat())
        barChart.xAxis.setLabelCount(valueCount)
        barChart.xAxis.setCenterAxisLabels(false)
        barChart.invalidate()
    }

    // Make sure the value on the bar is not out of screen.
    class CustomHorizontalBarChartRenderer(chart: BarDataProvider, animator: ChartAnimator, viewPortHandler: ViewPortHandler): HorizontalBarChartRenderer(chart, animator, viewPortHandler) {
        override fun drawValue(
            c: Canvas,
            valueText: String,
            x: Float,
            y: Float,
            color: Int
        ) {
            mValuePaint.setColor(color)
            c.drawText(valueText, x.coerceAtLeast(60F), y, mValuePaint)
        }

    }

    fun showSleepBarGraph(state: GraphState) {
        val ranges = toSleepRanges(state.events)
        val values = ArrayList(List(state.endSpan - state.startSpan + 1) { BarEntry(it.toFloat(), 0F) })
        //Log.d(TAG, "startUnix: ${Date(state.startUnix * 1000)}, endUnix: ${Date(state.endUnix * 1000)}")

        for (range in ranges) {
            // a sleep event can span to another day
            // distribute sleep time over the days
            val startUnix = range.start
            val endUnix = range.end
            val begIndex = unixToSpan(startUnix)
            val endIndex = unixToSpan(endUnix)
            var mid = startUnix

            //Log.d(TAG, "beginIndex: $begIndex, endIndex: $endIndex, startUnix: ${Date(startUnix * 1000)} ($startUnix), endUnix: ${Date(endUnix * 1000)} ($endUnix)")
            //Log.d(TAG, "startUnix: ${Date(startUnix * 1000)}, endUnix: ${Date(endUnix * 1000)}, begIndex: $begIndex, endIndex: $endIndex (index diff: ${endIndex - begIndex})")
            for (i in begIndex..endIndex) {
                // i is the days/weeks/months since unix epoch
                val spanBegin = spanToUnix(i)
                val spanEnd =  spanToUnix(i + 1)
                //Log.d(TAG, "i: $i, mid: ${Date(mid * 1000)}, spanBegin: ${Date(spanBegin * 1000)}, spanEnd: ${Date(spanEnd * 1000)}, beginUnix: ${Date(startUnix * 1000)} endUnix: ${Date(endUnix * 1000)}")
                val sleepBegin = max(mid, spanBegin)
                val sleepEnd = min(endUnix, spanEnd)
                val index = i - state.startSpan
                val duration = sleepEnd - sleepBegin
                //Log.d(TAG, "[$index] sleepBegin: ${Date(sleepBegin * 1000)}, sleepEnd: ${Date(sleepEnd * 1000)}, ${DateUtils.formatTimeDuration(this, duration)}")

                state.dayCounter.setDaysWithData(sleepBegin, sleepEnd)

                if (graphTypeSelection == GraphType.SLEEP_SUM) {
                    values[index].y += duration.toFloat()
                } else if (graphTypeSelection == GraphType.SLEEP_EVENTS) {
                    values[index].y += 1F
                } else {
                    Log.e(TAG, "Unexpected graph type.")
                    return
                }

                mid = sleepEnd
            }
        }

        for (index in values.indices) {
            val daysWithData = state.dayCounter.countDaysWithData(spanToUnix(state.startSpan + index), spanToUnix(state.startSpan + index + 1))
            if (daysWithData == 0) {
                assert(values[index].y == 0F)
            } else {
                values[index].y /= daysWithData
            }
        }

        val set1 = BarDataSet(values, "")
        val data = BarData(set1)
        set1.setDrawValues(true)
        set1.isHighlightEnabled = false

        data.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val prefix = if (timeRangeSelection == TimeRange.DAY) { "" } else { "⌀ " }
                return when (graphTypeSelection) {
                    GraphType.SLEEP_EVENTS -> {
                        prefix + value.toInt().toString()
                    }
                    GraphType.SLEEP_SUM -> {
                        prefix + DateUtils.formatTimeDuration(applicationContext, value.toLong())
                    }
                    else -> {
                        Log.e(TAG, "unhandled graphTypeSelection $graphTypeSelection")
                        prefix + value.toInt().toString()
                    }
                }
            }
        })

        set1.setDrawIcons(false)

        data.setValueTextSize(12f)
        barChart.setData(data)

        barChart.setRenderer(CustomHorizontalBarChartRenderer(barChart, barChart.getAnimator(), barChart.getViewPortHandler()))

        barChart.legend.isEnabled = false
        val valueCount = min(values.size, 24)
        barChart.setVisibleXRangeMaximum(valueCount.toFloat())
        barChart.xAxis.setLabelCount(valueCount)
        barChart.xAxis.setCenterAxisLabels(false)
        barChart.invalidate()
    }

    fun showBottleBarGraph(state: GraphState) {
        val values = ArrayList(List(state.endSpan - state.startSpan + 1) { BarEntry(it.toFloat(), 0F) })

        for (event in state.events) {
            val index = unixToSpan(event.time) - state.startSpan

            state.dayCounter.setDaysWithData(event.time, event.time)

            if (graphTypeSelection == GraphType.BOTTLE_EVENTS) {
                values[index].y += 1F
            } else if (graphTypeSelection == GraphType.BOTTLE_SUM) {
                values[index].y += event.quantity.toFloat()
            } else {
                Log.e(TAG, "unhandled graphTypeSelection: $graphTypeSelection")
                return
            }
        }

        for (index in values.indices) {
            val daysWithData = state.dayCounter.countDaysWithData(spanToUnix(state.startSpan + index), spanToUnix(state.startSpan + index + 1))
            //Log.d(TAG, "values[$index].y: ${values[index].y}, daysWithData: $daysWithData")
            if (daysWithData == 0) {
                assert(values[index].y == 0F)
            } else {
                values[index].y /= daysWithData
            }
        }

        val set1 = BarDataSet(values, "")

        set1.setDrawValues(true)
        set1.isHighlightEnabled = false
        val data = BarData(set1)

        data.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val prefix = if (timeRangeSelection == TimeRange.DAY) { "" } else { "⌀ " }
                //Log.d(TAG, "getFormattedValue ${dataTypeSelectionValue} ${eventTypeSelectionValue}")
                return when (graphTypeSelection) {
                    GraphType.BOTTLE_EVENTS -> {
                        prefix + value.toInt().toString()
                    }
                    GraphType.BOTTLE_SUM -> {
                        prefix + NumericUtils(applicationContext).formatEventQuantity(LunaEvent.Type.BABY_BOTTLE, value.toInt())
                    }
                    else -> {
                        Log.e(TAG, "unhandled graphTypeSelection")
                        prefix + value.toInt()
                    }
                }
            }
        })

        data.setValueTextSize(12f)
        barChart.setData(data)

        barChart.setRenderer(CustomHorizontalBarChartRenderer(barChart, barChart.getAnimator(), barChart.getViewPortHandler()))

        val valueCount = min(values.size, 24)
        barChart.setVisibleXRangeMaximum(valueCount.toFloat())
        barChart.xAxis.setLabelCount(valueCount)
        barChart.xAxis.setCenterAxisLabels(false)
        barChart.invalidate()
    }

    class DayCounter(val startDays: Int, val stopDays: Int) {
        val daysWithData = BooleanArray(stopDays - startDays + 1)

        // count days in a span that have data
        // e.g. return 7 (days) for applied span of a week where there is data for every day 
        fun countDaysWithData(beginUnix: Long, endUnix: Long): Int {
            val beginDays = unixToDays(beginUnix)
            val endDays = unixToDays(endUnix)
            //Log.d(TAG, "countDaysWithData: beginDays: $beginDays, endDays: $endDays, ${Date(beginUnix * 1000)} - ${Date(endUnix * 1000)}")
            var count = 0
            for (i in (beginDays - startDays)..<(endDays - startDays)) {
                //Log.d(TAG, "countDaysWithData: i: $i, size: ${daysWithData.size}")
                count += if (daysWithData[i]) { 1 } else { 0 }
            }
            return count
        }

        fun setDaysWithData(beginUnix: Long, endUnix: Long) {
            val beginDays = unixToDays(beginUnix)
            val endDays = unixToDays(endUnix)
            assert(beginDays <= endDays)
            assert(startDays <= beginDays)
            for (i in (beginDays - startDays)..(endDays - startDays)) {
                daysWithData[i] = true
            }
        }
    }

    data class GraphState(val events: List<LunaEvent>, val dayCounter: DayCounter, val startUnix: Long, val endUnix: Long, val startSpan: Int, val endSpan: Int)

    fun showGraph() {
        //Log.d(TAG, "showGraph: graphTypeSelection: $graphTypeSelection, timeRangeSelection: $timeRangeSelection")
        barChart.fitScreen()
        barChart.data?.clearValues()
        barChart.xAxis.valueFormatter = null
        barChart.notifyDataSetChanged()
        barChart.clear()
        barChart.invalidate()
        //Log.d(TAG, "resetBarChart; barChart.xAxis.labelCount: ${barChart.xAxis.labelCount}, barChart.visibleXRange: ${barChart.visibleXRange}, barChart.xAxis.isCenterAxisLabelsEnabled: ${barChart.xAxis.isCenterAxisLabelsEnabled}, barChart.isAutoScaleMinMaxEnabled: ${barChart.isAutoScaleMinMaxEnabled}, barChart.isScaleXEnabled: ${barChart.isScaleXEnabled}, barChart.isScaleYEnabled: ${barChart.isScaleYEnabled}")

        val type = when (graphTypeSelection) {
            GraphType.BOTTLE_EVENTS,
            GraphType.BOTTLE_SUM -> LunaEvent.Type.BABY_BOTTLE
            GraphType.SLEEP_EVENTS,
            GraphType.SLEEP_SUM,
            GraphType.SLEEP_PATTERN -> LunaEvent.Type.SLEEP
        }


        val events = MainActivity.allEvents.filter { it.type == type }.sortedBy { it.time }

        if (events.isEmpty()) {
            barChart.visibility = View.GONE
            noDataTextView.visibility = View.VISIBLE
            return
        } else {
            barChart.visibility = View.VISIBLE
            noDataTextView.visibility = View.GONE
        }

        // unix time span of all events
        val startUnix = events.minOf { it.time }
        val endUnix = System.currentTimeMillis() / 1000

        // convert to days, weeks or months
        val startSpan = unixToSpan(startUnix)
        val endSpan = unixToSpan(endUnix)

        // days when the a day/week/month starts/ends
        val startDays = unixToDays(spanToUnix(startSpan))
        val endDays = unixToDays(spanToUnix(endSpan + 1)) // until end of next span
        val dayCounter = DayCounter(startDays, endDays)

        //Log.d(TAG, "startUnix: ${Date(1000 * startUnix)}, endUnix: ${Date(1000 * endUnix)}, startSpan: ${Date(1000 * spanToUnix(startSpan))}, endSpan: ${Date(1000 * spanToUnix(endSpan))}, startDaysUnix: ${Date(daysToUnix(startDays) * 1000)}. endDaysUnix: ${Date(daysToUnix(endDays) * 1000)}")

        // print dates
        barChart.xAxis.valueFormatter = object: ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                val unixSeconds = spanToUnix(startSpan + index)

                val dateTime = Calendar.getInstance()
                dateTime.time = Date(1000L * unixSeconds)
                val year = dateTime.get(Calendar.YEAR)
                val month = dateTime.get(Calendar.MONTH) + 1 // month starts at 0
                val week = dateTime.get(Calendar.WEEK_OF_YEAR)
                val day = dateTime.get(Calendar.DAY_OF_MONTH)

                //Log.d(TAG, "index: $index, unixSeconds: ${Date(1000 * unixSeconds)}, day: $day, week: $week, month: $month, year: $year")

                // Adjust years if the first week of a year starts in the previous year.
                val years = if (month == 12 && week == 1) {
                    year + 1
                } else {
                    year
                }

                val days = "%02d".format(day)
                val weeks = "%02d".format(week)
                val months = "%02d".format(month)

                return when (timeRangeSelection) {
                    TimeRange.DAY -> "$days/$months/$years"
                    TimeRange.WEEK -> "$weeks/$years"
                    TimeRange.MONTH -> "$months/$years"
                }
            }
        }

        val state = GraphState(events, dayCounter, startUnix, endUnix, startSpan, endSpan)

        when (graphTypeSelection) {
            GraphType.BOTTLE_EVENTS,
            GraphType.BOTTLE_SUM -> showBottleBarGraph(state)
            GraphType.SLEEP_EVENTS,
            GraphType.SLEEP_SUM -> showSleepBarGraph(state)
            GraphType.SLEEP_PATTERN -> if (timeRangeSelection == TimeRange.DAY) {
                // specialized pattern bar for daily view (optional)
                showSleepPatternBarGraphDaily(state)
            } else {
                showSleepPatternBarGraphSlotted(state)
            }
        }
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

        //Log.d(TAG, "spinner ${arrayValues.indexOf(currentValue)} (${arrayValues.joinToString { it }})")

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

        // 15 min steps
        const val SLEEP_PATTERN_GRANULARITY = 15 * 60

        // Time between toast messages (to prevent jams)
        const val TOAST_FREQUENCY_MS = 3500

        // color gradient
        val SLEEP_PATTERN_COLORS = arrayOf(
            "#FFFFFF".toColorInt(), "#EEF5F7".toColorInt(), "#DDEBEF".toColorInt(), "#CCE2E7".toColorInt(),
            "#BBD8DF".toColorInt(), "#AACED7".toColorInt(), "#99C4CF".toColorInt(), "#88BAC7".toColorInt(),
            "#77B1BF".toColorInt(), "#66A7B7".toColorInt(), "#559DAF".toColorInt(), "#4493A7".toColorInt(),
            "#33899F".toColorInt(), "#228097".toColorInt(), "#11768F".toColorInt(), "#006C87".toColorInt()
        )

        var graphTypeSelection = GraphType.SLEEP_SUM
        var timeRangeSelection = TimeRange.DAY

        private val dateTime = Calendar.getInstance() // scratch pad

        // convert month to seconds since epoch
        fun unixToMonths(seconds: Long): Int {
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR)
            val months = dateTime.get(Calendar.MONTH)
            return 12 * years + months
        }

        // convert month to seconds since epoch
        fun monthsToUnix(months: Int): Long {
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, months / 12)
            dateTime.set(Calendar.MONTH, months % 12)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }

        // convert seconds to weeks since epoch
        fun unixToWeeks(seconds: Long): Int {
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR) - 1970
            val weeks = dateTime.get(Calendar.WEEK_OF_YEAR)
            val month = dateTime.get(Calendar.MONTH) + 1 // month starts at 0

            if (month == 12 && weeks == 1) {
                // The first week if the year might start in the previous year.
                return 52 * (years + 1) + weeks
            }

            return 52 * years + weeks
        }

        // convert weeks to seconds since epoch
        fun weeksToUnix(weeks: Int): Long {
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, 1970 + weeks / 52)
            dateTime.set(Calendar.WEEK_OF_YEAR, weeks % 52)
            dateTime.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }

        // convert seconds to days since epoch
        fun unixToDays(seconds: Long): Int {
            dateTime.time = Date(seconds * 1000)
            val years = dateTime.get(Calendar.YEAR)
            val days = dateTime.get(Calendar.DAY_OF_YEAR)
            return 365 * years + days
        }

        // convert days to seconds since epoch
        fun daysToUnix(days: Int): Long {
            dateTime.time = Date(0)
            dateTime.set(Calendar.YEAR, days / 365)
            dateTime.set(Calendar.DAY_OF_YEAR, days % 365)
            dateTime.set(Calendar.HOUR, 0)
            dateTime.set(Calendar.MINUTE, 0)
            dateTime.set(Calendar.SECOND, 0)
            return dateTime.time.time / 1000
        }

        fun appendToFloatArray(array: FloatArray, vararg values: Float): FloatArray {
            // create new array
            val newArray = FloatArray(array.size + values.size)
            // copy old values
            for (i in array.indices) {
                newArray[i] = array[i]
            }
            // add new values
            for (i in values.indices) {
                newArray[array.size + i] = values[i]
            }
            return newArray
        }

        // for debugging
        fun debugBarValues(values: ArrayList<BarEntry>) {
            for (value in values) {
                val yVals = value.yVals
                if (yVals != null) {
                    val y = yVals.fold(0F) { acc, next -> acc + next }
                    val yVals = yVals.joinToString { it.toString() }
                    Log.d(TAG, "value: ${value.x} $y ($yVals)")
                } else {
                    Log.d(TAG, "value: ${value.x} ${value.y}")
                }
            }
        }
    }
}

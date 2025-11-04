package utils

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import it.danieleverducci.lunatracker.R
import it.danieleverducci.lunatracker.entities.LunaEvent
import java.text.NumberFormat

class NumericUtils (val context: Context) {
    val numberFormat: NumberFormat
    val measurement_unit_liquid_base: String
    val measurement_unit_weight_base: String
    val measurement_unit_weight_tiny: String
    val measurement_unit_temperature_base: String

    private fun isMetricSystem(): Boolean {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val measurementSystem = LocaleData.getMeasurementSystem(ULocale.getDefault())
            return (measurementSystem == LocaleData.MeasurementSystem.SI)
       } else {
            val locale = context.resources.configuration.locale
            return when (locale.country) {
                // https://en.wikipedia.org/wiki/United_States_customary_units
                // https://en.wikipedia.org/wiki/Imperial_units
                "US" -> false // US IMPERIAL
                // UK, Myanmar, Liberia,
                "GB", "MM", "LR" -> false // IMPERIAL
                else -> true // METRIC
            }
       }
    }

    init {
        this.numberFormat = NumberFormat.getInstance()
        this.measurement_unit_liquid_base = context.getString(
            if (isMetricSystem())
                R.string.measurement_unit_liquid_base_metric
            else
                R.string.measurement_unit_liquid_base_imperial
        )
        this.measurement_unit_weight_base = context.getString(
            if (isMetricSystem())
                R.string.measurement_unit_weight_base_metric
            else
                R.string.measurement_unit_weight_base_imperial
        )
        this.measurement_unit_weight_tiny = context.getString(
            if (isMetricSystem())
                R.string.measurement_unit_weight_tiny_metric
            else
                R.string.measurement_unit_weight_tiny_imperial
        )
        this.measurement_unit_temperature_base = context.getString(
            if (isMetricSystem())
                R.string.measurement_unit_temperature_base_metric
            else
                R.string.measurement_unit_temperature_base_imperial
        )
    }

    fun formatEventQuantity(event: LunaEvent): String {
        val formatted = StringBuilder()
        if (event.quantity > 0) {
            formatted.append(when (event.type) {
                LunaEvent.TYPE_TEMPERATURE ->
                    (event.quantity / 10.0f).toString()
                LunaEvent.TYPE_PUKE ->
                    context.resources.getStringArray(R.array.AmountLabels)[event.quantity - 1]
                else ->
                    event.quantity
            })

            formatted.append(" ")
            formatted.append(
                when (event.type) {
                    LunaEvent.TYPE_BABY_BOTTLE -> measurement_unit_liquid_base
                    LunaEvent.TYPE_WEIGHT -> measurement_unit_weight_base
                    LunaEvent.TYPE_MEDICINE -> measurement_unit_weight_tiny
                    LunaEvent.TYPE_TEMPERATURE -> measurement_unit_temperature_base
                    else -> ""
                }
            )
        }
        return formatted.toString()
    }

    /**
     * Returns a valid quantity range for the event type.
     * @return min, max, normal
     */
    fun getValidEventQuantityRange(lunaEventType: String): Triple<Int, Int, Int>? {
        return when (lunaEventType) {
            LunaEvent.TYPE_TEMPERATURE -> {
                if (isMetricSystem())
                    Triple(
                        context.resources.getInteger(R.integer.human_body_temp_min_metric),
                        context.resources.getInteger(R.integer.human_body_temp_max_metric),
                        context.resources.getInteger(R.integer.human_body_temp_normal_metric)
                    )
                else
                    Triple(
                        context.resources.getInteger(R.integer.human_body_temp_min_imperial),
                        context.resources.getInteger(R.integer.human_body_temp_max_imperial),
                        context.resources.getInteger(R.integer.human_body_temp_normal_imperial)
                    )
            }
            else -> null
        }
    }
}
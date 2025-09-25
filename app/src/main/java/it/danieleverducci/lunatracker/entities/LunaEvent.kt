package it.danieleverducci.lunatracker.entities

import android.content.Context
import it.danieleverducci.lunatracker.R
import org.json.JSONObject
import java.util.Date

/**
 * Represents a logged event.
 * It encloses, but doesn't parse entirely, a jsonObject. This was done to
 * allow expandability and backwards compatibility (if a field is added in a
 * release, it is simply ignored by previous ones).
 */
class LunaEvent: Comparable<LunaEvent> {

    companion object {
        const val TYPE_BABY_BOTTLE = "BABY_BOTTLE"
        const val TYPE_WEIGHT = "WEIGHT"
        const val TYPE_BREASTFEEDING_LEFT_NIPPLE = "BREASTFEEDING_LEFT_NIPPLE"
        const val TYPE_BREASTFEEDING_BOTH_NIPPLE = "BREASTFEEDING_BOTH_NIPPLE"
        const val TYPE_BREASTFEEDING_RIGHT_NIPPLE = "BREASTFEEDING_RIGHT_NIPPLE"
        const val TYPE_DIAPERCHANGE_POO = "DIAPERCHANGE_POO"
        const val TYPE_DIAPERCHANGE_PEE = "DIAPERCHANGE_PEE"
        const val TYPE_MEDICINE = "MEDICINE"
        const val TYPE_ENEMA = "ENEMA"
        const val TYPE_NOTE = "NOTE"
        const val TYPE_CUSTOM = "CUSTOM"
        const val TYPE_COLIC = "COLIC"
        const val TYPE_TEMPERATURE = "TEMPERATURE"
        const val TYPE_FOOD = "FOOD"
        const val TYPE_PUKE = "PUKE"
    }

    private val jo: JSONObject

    var time: Long  // In unix time (seconds since 1970)
        get() = jo.getLong("time")
        set(value) {
            jo.put("time", value)
        }
    var type: String
        get(): String = jo.getString("type")
        set(value) {
            jo.put("type", value)
        }
    var quantity: Int
        get() = jo.optInt("quantity")
        set(value) {
            if (value > 0)
                jo.put("quantity", value)
        }
    var notes: String
        get(): String = jo.optString("notes")
        set(value) {
            jo.put("notes", value)
        }

    constructor(jo: JSONObject) {
        this.jo = jo
        // A minimal LunaEvent should have at least time and type
        if (!jo.has("time") || !jo.has("type"))
            throw IllegalArgumentException("JSONObject is not a LunaEvent")
    }

    constructor(type: String) {
        this.jo = JSONObject()
        this.time = System.currentTimeMillis() / 1000
        this.type = type
    }

    constructor(type: String, quantity: Int) {
        this.jo = JSONObject()
        this.time = System.currentTimeMillis() / 1000
        this.type = type
        this.quantity = quantity
    }

    fun getTypeEmoji(context: Context): String {
        return context.getString(
            when (type) {
                TYPE_BABY_BOTTLE -> R.string.event_bottle_type
                TYPE_WEIGHT -> R.string.event_scale_type
                TYPE_BREASTFEEDING_LEFT_NIPPLE -> R.string.event_breastfeeding_left_type
                TYPE_BREASTFEEDING_BOTH_NIPPLE -> R.string.event_breastfeeding_both_type
                TYPE_BREASTFEEDING_RIGHT_NIPPLE -> R.string.event_breastfeeding_right_type
                TYPE_DIAPERCHANGE_POO -> R.string.event_diaperchange_poo_type
                TYPE_DIAPERCHANGE_PEE -> R.string.event_diaperchange_pee_type
                TYPE_MEDICINE -> R.string.event_medicine_type
                TYPE_ENEMA -> R.string.event_enema_type
                TYPE_NOTE -> R.string.event_note_type
                TYPE_TEMPERATURE -> R.string.event_temperature_type
                TYPE_COLIC -> R.string.event_colic_type
                TYPE_FOOD -> R.string.event_food_type
                TYPE_PUKE -> R.string.event_puke_type
                else -> R.string.event_unknown_type
            }
        )
    }

    fun getTypeDescription(context: Context): String {
        return context.getString(
            when (type) {
                TYPE_BABY_BOTTLE -> R.string.event_bottle_desc
                TYPE_WEIGHT -> R.string.event_scale_desc
                TYPE_BREASTFEEDING_LEFT_NIPPLE -> R.string.event_breastfeeding_left_desc
                TYPE_BREASTFEEDING_BOTH_NIPPLE -> R.string.event_breastfeeding_both_desc
                TYPE_BREASTFEEDING_RIGHT_NIPPLE -> R.string.event_breastfeeding_right_desc
                TYPE_DIAPERCHANGE_POO -> R.string.event_diaperchange_poo_desc
                TYPE_DIAPERCHANGE_PEE -> R.string.event_diaperchange_pee_desc
                TYPE_MEDICINE -> R.string.event_medicine_desc
                TYPE_ENEMA -> R.string.event_enema_desc
                TYPE_NOTE -> R.string.event_note_desc
                TYPE_TEMPERATURE -> R.string.event_temperature_desc
                TYPE_COLIC -> R.string.event_colic_desc
                TYPE_FOOD -> R.string.event_food_desc
                TYPE_PUKE -> R.string.event_puke_desc
                else -> R.string.event_unknown_desc
            }
        )
    }

    fun getDialogMessage(context: Context): String? {
        return when(type) {
            TYPE_MEDICINE -> context.getString(R.string.log_medicine_dialog_description)
            else -> null
        }
    }

    fun toJson(): JSONObject {
        return jo
    }

    override fun toString(): String {
        return "$type qty: $quantity time: ${Date(time * 1000)}"
    }

    override fun compareTo(other: LunaEvent): Int {
        return (this.time - other.time).toInt()
    }
}
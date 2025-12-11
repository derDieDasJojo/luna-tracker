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

    enum class Type {
        BABY_BOTTLE,
        FOOD,
        BREASTFEEDING_LEFT_NIPPLE,
        BREASTFEEDING_BOTH_NIPPLE,
        BREASTFEEDING_RIGHT_NIPPLE,
        DIAPERCHANGE_POO,
        DIAPERCHANGE_PEE,
        SLEEP,
        WEIGHT,
        MEDICINE,
        ENEMA,
        NOTE,
        COLIC,
        TEMPERATURE,
        PUKE,
        BATH,
        UNKNOWN
    }

    private val jo: JSONObject

    var time: Long  // In unix time (seconds since 1970)
        get() = jo.getLong("time")
        set(value) {
            jo.put("time", value)
        }
    var type: Type
        get(): Type {
            return try {
                Type.valueOf(jo.getString("type"))
            } catch (_: Exception) {
                Type.UNKNOWN
            }
        }
        set(value) {
            jo.put("type", value.name)
        }
    var quantity: Int
        get() = jo.optInt("quantity")
        set(value) {
            if (value > 0)
                jo.put("quantity", value)
            else
                jo.remove("quantity")
        }
    var notes: String
        get(): String = jo.optString("notes")
        set(value) {
            jo.put("notes", value)
        }
    var signature: String
        get(): String = jo.optString("signature")
        set(value) {
            if (value.isNotEmpty())
                jo.put("signature", value)
        }

    constructor(jo: JSONObject) {
        this.jo = jo
        // A minimal LunaEvent should have at least time and type
        if (!jo.has("time") || !jo.has("type"))
            throw IllegalArgumentException("JSONObject is not a LunaEvent")
    }

    constructor(event: LunaEvent) {
        this.jo = JSONObject()
        this.type = event.type
        this.time = event.time
        this.quantity = event.quantity
        this.notes = event.notes
        this.signature = event.signature
    }

    constructor(type: Type) {
        this.jo = JSONObject()
        this.time = System.currentTimeMillis() / 1000
        this.type = type
    }

    constructor(type: Type, quantity: Int) {
        this.jo = JSONObject()
        this.time = System.currentTimeMillis() / 1000
        this.type = type
        this.quantity = quantity
    }

    fun getStartTime(): Long {
        return time
    }

    fun getEndTime(): Long {
        return if (type == TYPE_SLEEP) {
            time + quantity
        } else {
            time
        }
    }

    fun getTypeEmoji(context: Context): String {
        return getTypeEmoji(context, type)
    }

    fun getTypeDescription(context: Context): String {
        return getTypeDescription(context, type)
    }

    fun getDialogMessage(context: Context): String {
        return getDialogMessage(context, type)
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

    companion object {
        fun getTypeEmoji(context: Context, type: Type): String {
            return context.getString(
                when (type) {
                    Type.BABY_BOTTLE -> R.string.event_bottle_type
                    Type.WEIGHT -> R.string.event_weight_type
                    Type.BREASTFEEDING_LEFT_NIPPLE -> R.string.event_breastfeeding_left_type
                    Type.BREASTFEEDING_BOTH_NIPPLE -> R.string.event_breastfeeding_both_type
                    Type.BREASTFEEDING_RIGHT_NIPPLE -> R.string.event_breastfeeding_right_type
                    Type.DIAPERCHANGE_POO -> R.string.event_diaperchange_poo_type
                    Type.DIAPERCHANGE_PEE -> R.string.event_diaperchange_pee_type
                    Type.MEDICINE -> R.string.event_medicine_type
                    Type.ENEMA -> R.string.event_enema_type
                    Type.NOTE -> R.string.event_note_type
                    Type.TEMPERATURE -> R.string.event_temperature_type
                    Type.COLIC -> R.string.event_colic_type
                    Type.FOOD -> R.string.event_food_type
                    Type.PUKE -> R.string.event_puke_type
                    Type.BATH -> R.string.event_bath_type
                    Type.SLEEP -> R.string.event_sleep_type
                    Type.UNKNOWN -> R.string.event_unknown_type
                }
            )
        }

        fun getDialogMessage(context: Context, type: Type): String {
            return context.getString(
                when (type) {
                    Type.BABY_BOTTLE -> R.string.log_bottle_dialog_description
                    Type.MEDICINE -> R.string.log_medicine_dialog_description
                    Type.TEMPERATURE -> R.string.log_temperature_dialog_description
                    Type.DIAPERCHANGE_POO,
                    Type.DIAPERCHANGE_PEE,
                    Type.PUKE -> R.string.log_amount_dialog_description
                    Type.WEIGHT -> R.string.log_weight_dialog_description
                    Type.SLEEP -> R.string.log_sleep_dialog_description
                    else -> R.string.log_unknown_dialog_description
                }
            )
        }

        fun getTypeDescription(context: Context, type: Type): String {
            return context.getString(
                when (type) {
                    Type.BABY_BOTTLE -> R.string.event_bottle_desc
                    Type.WEIGHT -> R.string.event_weight_desc
                    Type.BREASTFEEDING_LEFT_NIPPLE -> R.string.event_breastfeeding_left_desc
                    Type.BREASTFEEDING_BOTH_NIPPLE -> R.string.event_breastfeeding_both_desc
                    Type.BREASTFEEDING_RIGHT_NIPPLE -> R.string.event_breastfeeding_right_desc
                    Type.DIAPERCHANGE_POO -> R.string.event_diaperchange_poo_desc
                    Type.DIAPERCHANGE_PEE -> R.string.event_diaperchange_pee_desc
                    Type.MEDICINE -> R.string.event_medicine_desc
                    Type.ENEMA -> R.string.event_enema_desc
                    Type.NOTE -> R.string.event_note_desc
                    Type.TEMPERATURE -> R.string.event_temperature_desc
                    Type.COLIC -> R.string.event_colic_desc
                    Type.FOOD -> R.string.event_food_desc
                    Type.PUKE -> R.string.event_puke_desc
                    Type.BATH -> R.string.event_bath_desc
                    Type.SLEEP -> R.string.event_sleep_desc
                    Type.UNKNOWN -> R.string.event_unknown_desc
                }
            )
        }

        // Entries for for popup list
        fun getPopupItemTitle(context: Context, type: Type): String {
            return context.getString(
                when (type) {
                    Type.BABY_BOTTLE -> R.string.event_type_item_bottle
                    Type.WEIGHT -> R.string.event_type_item_weight
                    Type.BREASTFEEDING_LEFT_NIPPLE -> R.string.event_type_item_breastfeeding_left
                    Type.BREASTFEEDING_BOTH_NIPPLE -> R.string.event_type_item_breastfeeding_both
                    Type.BREASTFEEDING_RIGHT_NIPPLE -> R.string.event_type_item_breastfeeding_right
                    Type.DIAPERCHANGE_POO -> R.string.event_type_item_diaperchange_poo
                    Type.DIAPERCHANGE_PEE -> R.string.event_type_item_diaperchange_pee
                    Type.MEDICINE -> R.string.event_type_item_medicine
                    Type.ENEMA -> R.string.event_type_item_enema
                    Type.NOTE -> R.string.event_type_item_note
                    Type.TEMPERATURE -> R.string.event_type_item_temperature
                    Type.COLIC -> R.string.event_type_item_colic
                    Type.FOOD -> R.string.event_type_item_food
                    Type.PUKE -> R.string.event_type_item_puke
                    Type.BATH -> R.string.event_type_item_bath
                    Type.SLEEP -> R.string.event_type_item_sleep
                    Type.UNKNOWN -> R.string.event_type_item_unknown
                }
            )
        }
    }
}

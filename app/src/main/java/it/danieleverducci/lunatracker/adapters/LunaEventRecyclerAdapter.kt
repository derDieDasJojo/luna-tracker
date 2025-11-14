package it.danieleverducci.lunatracker.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import it.danieleverducci.lunatracker.entities.LunaEvent
import it.danieleverducci.lunatracker.R
import utils.DateUtils
import utils.NumericUtils

class LunaEventRecyclerAdapter: RecyclerView.Adapter<LunaEventRecyclerAdapter.LunaEventVH> {
    private val context: Context
    private val items: ArrayList<LunaEvent>
    val numericUtils: NumericUtils
    var onItemClickListener: OnItemClickListener? = null
    val layoutRes: Int

    constructor(context: Context, items: ArrayList<LunaEvent>) {
        this.context = context
        this.items = items
        this.numericUtils = NumericUtils(context)

        val fontScale = context.resources.configuration.fontScale
        val screenSize = context.resources.configuration.screenWidthDp
        this.layoutRes =
            if(fontScale > 1.2 || screenSize < 320 || (fontScale > 1 && screenSize < 400))
                R.layout.row_luna_event_vertical
            else
                R.layout.row_luna_event
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): LunaEventVH {
        val view = LayoutInflater.from(context).inflate(layoutRes, parent, false)
        return LunaEventVH(view)
    }

    override fun onBindViewHolder(
        holder: LunaEventVH,
        position: Int
    ) {
        val item = items.get(position)
        // Colors
        holder.root.setBackgroundResource(
            if (position % 2 == 0) R.color.list_background_even else R.color.list_background_odd
        )
        holder.quantity.setTextColor(ContextCompat.getColor(context, R.color.textColor))
        // Contents
        holder.type.text = item.getTypeEmoji(context)
        holder.description.text = when (item.type) {
            LunaEvent.TYPE_MEDICINE -> item.notes
            LunaEvent.TYPE_NOTE -> item.notes
            LunaEvent.TYPE_CUSTOM -> item.notes
            else -> item.getTypeDescription(context)
        }
        holder.time.text = DateUtils.formatTimeAgo(context, item.getEndTime())
        var quantityText = numericUtils.formatEventQuantity(item)

        // if the event is weight, show difference with the last one
        if (item.type == LunaEvent.TYPE_WEIGHT) {
            val lastWeight = getPreviousWeightEvent(position)
            if (lastWeight != null) {
                val differenceInWeight = item.quantity - lastWeight.quantity
                val sign = if (differenceInWeight > 0) "+" else ""
                quantityText += "\n($sign$differenceInWeight)"
                if (differenceInWeight < 0) {
                    holder.quantity.setTextColor(ContextCompat.getColor(context, R.color.danger))
                }
            }
        }

        holder.quantity.text = quantityText

        // Listeners
        if (onItemClickListener != null) {
            holder.root.setOnClickListener({
                onItemClickListener?.onItemClick(item)
            })
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    private fun getPreviousWeightEvent(startFromPosition: Int): LunaEvent? {
        if (startFromPosition == items.size - 1)
            return null
        for (pos in startFromPosition + 1 until items.size) {
            val item = items.get(pos)
            if (item.type != LunaEvent.TYPE_WEIGHT)
                continue
            return item
        }
        return null
    }

    class LunaEventVH: RecyclerView.ViewHolder {
        val root: View
        val type: TextView
        val description: TextView
        val quantity: TextView
        val time: TextView

        constructor(v: View) : super(v) {
            root = v
            type = v.findViewById<TextView>(R.id.type)
            description = v.findViewById<TextView>(R.id.description)
            quantity = v.findViewById<TextView>(R.id.quantity)
            time = v.findViewById<TextView>(R.id.time)
        }
    }

    interface OnItemClickListener {
        fun onItemClick(event: LunaEvent)
    }
}
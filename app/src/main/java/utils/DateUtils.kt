package utils

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import it.danieleverducci.lunatracker.R
import java.util.Date

class DateUtils {
    companion object {
        /**
         * Format time duration in seconds as e.g. "2 hours, 1 min".
         * Used for the duration to the next/previous event in the event details dialog.
         */
        fun formatTimeDuration(context: Context, secondsDiff: Long): String {
            var seconds = secondsDiff

            val years = (seconds / (365 * 24 * 60 * 60F)).toLong()
            seconds -= years * (365 * 24 * 60 * 60)
            val days = (seconds / (24 * 60 * 60F)).toLong()
            seconds -= days * (24 * 60 * 60)
            val hours = (seconds / (60 * 60F)).toLong()
            seconds -= hours * (60 * 60)
            val minutes = (seconds / 60F).toLong()
            seconds -= minutes * 60

            fun format(value1: Long, value2: Long, resIdSingular1: Int, resIdPlural1: Int, resIdSingular2: Int, resIdPlural2: Int): String {
                val builder = StringBuilder()
                if (value1 == 0L) {
                    // omit
                } else if (value1 == 1L) {
                    builder.append(value1)
                    builder.append(" ")
                    builder.append(context.getString(resIdSingular1))
                } else {
                    builder.append(value1)
                    builder.append(" ")
                    builder.append(context.getString(resIdPlural1))
                }

                if (value1 > 0L && value2 > 0L) {
                    builder.append(", ")
                }

                if (value2 == 0L) {
                    // omit
                } else if (value2 == 1L) {
                    builder.append(value2)
                    builder.append(" ")
                    builder.append(context.getString(resIdSingular2))
                } else {
                    builder.append(value2)
                    builder.append(" ")
                    builder.append(context.getString(resIdPlural2))
                }
                return builder.toString()
            }

            if (years != 0L) {
                return format(years, days, R.string.year_ago, R.string.years_ago, R.string.day_ago, R.string.days_ago)
            } else if (days != 0L) {
                return format(days, hours, R.string.day_ago, R.string.days_ago, R.string.hour_ago, R.string.hours_ago)
            } else if (hours != 0L) {
                return format(hours, minutes, R.string.hour_ago, R.string.hours_ago, R.string.minute_ago, R.string.minutes_ago)
            } else {
                return format(minutes, seconds, R.string.minute_ago, R.string.minute_ago, R.string.second_ago, R.string.seconds_ago)
            }
        }

        /**
         * Formats the provided unix timestamp in a string like "3 hours, 26 minutes ago".
         * Used for the event list.
         */
        fun formatTimeAgo(context: Context, unixTime: Long): String {
            val secondsDiff = (System.currentTimeMillis() / 1000) - unixTime
            val minutesDiff = secondsDiff / 60

            if (minutesDiff < 1)
                return context.getString(R.string.now)

            val hoursAgo = (secondsDiff / (60 * 60)).toInt()
            val minutesAgo = (minutesDiff % 60).toInt()

            if (hoursAgo > 24)
                return DateFormat.getDateFormat(context).format(Date(unixTime*1000)) + "\n" +
                        DateFormat.getTimeFormat(context).format(Date(unixTime*1000))

            val formattedTime = StringBuilder()
            if (hoursAgo > 0) {
                formattedTime.append(hoursAgo).append(" ")
                if (hoursAgo == 1)
                    formattedTime.append(context.getString(R.string.hour_ago))
                else
                    formattedTime.append(context.getString(R.string.hours_ago))
            }
            if (minutesAgo > 0) {
                if (formattedTime.isNotEmpty())
                    formattedTime.append(", ")
                formattedTime.append(minutesAgo).append(" ")
                if (minutesAgo == 1)
                    formattedTime.append(context.getString(R.string.minute_ago))
                else
                    formattedTime.append(context.getString(R.string.minutes_ago))
            }
            return formattedTime.toString()
        }

        /**
         * Format time as localized string without seconds. E.g. "Sept 18, 2025, 03:36 PM".
         * Used in the event detail dialog.
         */
        fun formatDateTime(unixTime: Long): String {
            val date = Date(unixTime * 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dateFormat = android.icu.text.DateFormat.getDateTimeInstance(android.icu.text.DateFormat.DEFAULT, android.icu.text.DateFormat.SHORT)
                return dateFormat.format(date)
            } else {
                // fallback
                val dateFormat = java.text.DateFormat.getDateTimeInstance()
                return dateFormat.format(date)
            }
        }
    }
}

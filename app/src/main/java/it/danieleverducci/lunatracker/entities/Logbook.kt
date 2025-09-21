package it.danieleverducci.lunatracker.entities

class Logbook(val name: String) {
    companion object {
        const val MAX_SAFE_LOGBOOK_SIZE = 30000
    }
    val logs = ArrayList<LunaEvent>()

    fun isTooBig(): Boolean {
        return logs.size > MAX_SAFE_LOGBOOK_SIZE
    }

    /**
     * Halves the logbook to avoid the file being too big
     */
    fun trim() {
        logs.subList(MAX_SAFE_LOGBOOK_SIZE/2, logs.size).clear()
    }

    fun sort() {
        logs.sortDescending()
    }
}
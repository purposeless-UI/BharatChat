package com.droid.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MessageTimestampFormatter {

    /**
     * Returns a full timestamp string: "Mar 5, 2026, 3:45 PM"
     * Creates a new formatter each time to respect the current device locale.
     */
    fun formatFullTimestamp(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
}
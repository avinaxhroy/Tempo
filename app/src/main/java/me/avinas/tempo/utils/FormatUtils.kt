package me.avinas.tempo.utils

import java.util.Locale

/**
 * Utility functions for formatting values for display.
 */
object FormatUtils {
    
    /**
     * Format byte count as human-readable string (B, KB, MB, GB).
     */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
    
    /**
     * Format large numbers with K/M suffix.
     */
    fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

/**
 * Extension function for formatting bytes.
 */
fun Long.formatBytes(): String = FormatUtils.formatBytes(this)

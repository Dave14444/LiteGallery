package org.iurl.litegallery

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers what a network folder contains (number of pictures/videos, newest picture, total
 * size), so the folder cards can show a cover and a count right away - also on a slow or
 * missing connection. The values are refreshed every time the folder list is opened.
 */
object SmbFolderSummaries {

    data class Summary(
        val mediaCount: Int,
        /** URL of the newest picture, or null if the folder has none. */
        val coverUrl: String?,
        val latestModified: Long,
        val totalSize: Long
    )

    private const val PREFS = "smb_folder_summaries"
    private const val SEPARATOR = "\t"

    private val memory = ConcurrentHashMap<String, Summary>()

    /** Last known summary of [folderUrl], without any network access. */
    fun cached(context: Context, folderUrl: String): Summary? {
        memory[folderUrl]?.let { return it }
        val raw = prefs(context).getString(folderUrl, null) ?: return null
        return parse(raw)?.also { memory[folderUrl] = it }
    }

    /** Lists [folderUrl] (network access), stores and returns the new summary. */
    fun refresh(context: Context, folderUrl: String): Summary? {
        val client = RemoteClients.forUrl(folderUrl) ?: return null
        val media = client.listFiles(context, folderUrl).filter { !it.isDirectory && it.isMedia }
        val cover = media.filter { !it.isVideo }.maxByOrNull { it.lastModified }

        val summary = Summary(
            mediaCount = media.size,
            coverUrl = cover?.url,
            latestModified = media.maxOfOrNull { it.lastModified } ?: 0L,
            totalSize = media.sumOf { it.size }
        )
        memory[folderUrl] = summary
        prefs(context).edit().putString(folderUrl, serialize(summary)).apply()
        return summary
    }

    /** [card] showing the cover, count, size and date of [summary]. */
    fun applyTo(card: MediaFolder, summary: Summary): MediaFolder =
        card.copy(
            itemCount = if (summary.mediaCount > 0) summary.mediaCount else -1,
            // Covers come from the thumbnail cache on the share (SMB only).
            thumbnail = summary.coverUrl?.takeIf { SmbPath.isSmb(it) }?.let { "smbthumb://$it" },
            totalSizeBytes = summary.totalSize,
            latestDateModifiedMs = summary.latestModified
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun serialize(summary: Summary): String =
        listOf(
            summary.mediaCount.toString(),
            summary.coverUrl.orEmpty(),
            summary.latestModified.toString(),
            summary.totalSize.toString()
        ).joinToString(SEPARATOR)

    private fun parse(raw: String): Summary? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != 4) return null
        return Summary(
            mediaCount = parts[0].toIntOrNull() ?: return null,
            coverUrl = parts[1].ifEmpty { null },
            latestModified = parts[2].toLongOrNull() ?: return null,
            totalSize = parts[3].toLongOrNull() ?: return null
        )
    }
}

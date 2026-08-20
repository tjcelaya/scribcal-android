package com.tjcelaya.scribcal.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.tjcelaya.scribcal.data.database.AlbumConfig
import com.tjcelaya.scribcal.data.database.Cadence
import com.tjcelaya.scribcal.data.database.Event
import com.tjcelaya.scribcal.data.database.EventType
import com.tjcelaya.scribcal.data.database.FutureEvent
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes ScribCal "configuration" (event types, scheduled events, album config, app
 * settings and the selected calendar) to a single JSON file and exports/imports it to:
 *  - the user's shared storage at Documents/ScribCal/scribcal_config.json (via MediaStore), and
 *  - the ScribCal folder in the user's Google Drive.
 *
 * Recorded event history is intentionally NOT included. On import (replace-all), each event
 * type's most-recent occurrence is looked up in Google Calendar and stored as a single local
 * pseudo-event so the previous time label and frequency stats have a seed timestamp. Google
 * Calendar remains the source of truth for analytics/summarization.
 */
class ConfigBackupManager(
    private val database: ScribCalDatabase,
    private val storagePreferences: StoragePreferences,
    private val calendarRepository: CalendarRepository,
    private val driveRepository: DriveRepository
) {

    companion object {
        private const val TAG = "ConfigBackupManager"
        private const val SCHEMA_VERSION = 1
        private const val APP_VERSION_CODE = 5
        const val FILE_NAME = "scribcal_config.json"
        // MediaStore RELATIVE_PATH is canonically stored with a trailing slash.
        private val RELATIVE_PATH = Environment.DIRECTORY_DOCUMENTS + "/ScribCal/"
        const val DISPLAY_PATH = "Documents/ScribCal/$FILE_NAME"
    }

    sealed class BackupResult {
        data class Success(val message: String) : BackupResult()
        data class Error(val message: String) : BackupResult()
    }

    private val eventTypeDao = database.eventTypeDao()
    private val eventDao = database.eventDao()
    private val futureEventDao = database.futureEventDao()
    private val albumConfigDao = database.albumConfigDao()

    // === Public entry points ===

    suspend fun exportToDocuments(context: Context): BackupResult = withContext(Dispatchers.IO) {
        try {
            val json = buildConfigJson()
            writeToMediaStore(context, json)
            BackupResult.Success("Exported to $DISPLAY_PATH")
        } catch (e: Exception) {
            Log.e(TAG, "Export to Documents failed", e)
            BackupResult.Error("Export failed: ${e.message}")
        }
    }

    suspend fun importFromDocuments(context: Context): BackupResult = withContext(Dispatchers.IO) {
        try {
            val json = readFromMediaStore(context)
                ?: return@withContext BackupResult.Error("No backup file found at $DISPLAY_PATH")
            val stats = applyConfigJson(json)
            BackupResult.Success(stats.toMessage())
        } catch (e: Exception) {
            Log.e(TAG, "Import from Documents failed", e)
            BackupResult.Error("Import failed: ${e.message}")
        }
    }

    suspend fun exportToDrive(): BackupResult = withContext(Dispatchers.IO) {
        if (!driveRepository.isDriveInitialized()) {
            return@withContext BackupResult.Error("Connect Google Drive first (Settings → Google Drive).")
        }
        try {
            val json = buildConfigJson()
            if (driveRepository.uploadConfigFile(json)) {
                BackupResult.Success("Exported to Google Drive (ScribCal/$FILE_NAME)")
            } else {
                BackupResult.Error("Failed to upload to Google Drive")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export to Drive failed", e)
            BackupResult.Error("Export failed: ${e.message}")
        }
    }

    suspend fun importFromDrive(): BackupResult = withContext(Dispatchers.IO) {
        if (!driveRepository.isDriveInitialized()) {
            return@withContext BackupResult.Error("Connect Google Drive first (Settings → Google Drive).")
        }
        try {
            val json = driveRepository.downloadConfigFile()
                ?: return@withContext BackupResult.Error("No backup file found in Google Drive")
            val stats = applyConfigJson(json)
            BackupResult.Success(stats.toMessage())
        } catch (e: Exception) {
            Log.e(TAG, "Import from Drive failed", e)
            BackupResult.Error("Import failed: ${e.message}")
        }
    }

    // === Serialization ===

    suspend fun buildConfigJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("appVersionCode", APP_VERSION_CODE)
        root.put("exportedAt", System.currentTimeMillis())

        val eventTypes = JSONArray()
        for (type in eventTypeDao.getAllEventTypesSync()) {
            eventTypes.put(
                JSONObject()
                    .put("id", type.id)
                    .put("name", type.name)
                    .put("description", type.description ?: JSONObject.NULL)
                    .put("colorId", type.colorId ?: JSONObject.NULL)
                    .put("shouldBubble", type.shouldBubble)
                    .put("sortOrder", type.sortOrder)
                    .put("createdAt", type.createdAt)
                    .put("cadence", type.cadence.name)
            )
        }
        root.put("eventTypes", eventTypes)

        val futureEvents = JSONArray()
        for (fe in futureEventDao.getAllFutureEventsSync()) {
            futureEvents.put(
                JSONObject()
                    .put("id", fe.id)
                    .put("eventTypeId", fe.eventTypeId)
                    .put("targetTime", fe.targetTime)
                    .put("notes", fe.notes ?: JSONObject.NULL)
            )
        }
        root.put("futureEvents", futureEvents)

        val albumConfig = albumConfigDao.getAlbumConfig()
        root.put(
            "albumConfig",
            if (albumConfig == null) JSONObject.NULL else JSONObject()
                .put("googlePhotosAlbumId", albumConfig.googlePhotosAlbumId ?: JSONObject.NULL)
                .put("googlePhotosAlbumName", albumConfig.googlePhotosAlbumName ?: JSONObject.NULL)
                .put("createdAt", albumConfig.createdAt)
                .put("lastVerified", albumConfig.lastVerified ?: JSONObject.NULL)
        )

        val storage = JSONObject()
            .put("bubbleMode", storagePreferences.getBubbleMode().name)
            .put("instantEventIcon", storagePreferences.getInstantEventIcon().name)
            .put("googleDriveEnabled", storagePreferences.isGoogleDriveEnabled())
            .put("googlePhotosEnabled", storagePreferences.isGooglePhotosEnabled())

        val calendar = JSONObject()
            .put("selectedCalendarId", calendarRepository.getSelectedCalendarId() ?: JSONObject.NULL)
            .put("selectedCalendarName", calendarRepository.getSelectedCalendarName() ?: JSONObject.NULL)
            .put("selectedCalendarAccount", calendarRepository.getSelectedCalendarAccount() ?: JSONObject.NULL)
            .put("calendarSetupComplete", calendarRepository.isCalendarSetupComplete())
            .put("enhancedCalendarEnabled", calendarRepository.isEnhancedCalendarEnabled())

        root.put("settings", JSONObject().put("storage", storage).put("calendar", calendar))

        root.toString(2)
    }

    private data class ApplyStats(val importedTypes: Int, val seeded: Int, val futureEvents: Int) {
        fun toMessage(): String {
            val seedNote = if (seeded > 0) "; seeded $seeded from calendar" else ""
            return "Imported $importedTypes event type${if (importedTypes == 1) "" else "s"}$seedNote"
        }
    }

    /**
     * Replace-all import. Parses [json], validates the schema, wipes existing event types
     * (cascade clears events + future events), restores types/future events/album config/settings
     * in a single transaction, then seeds each type's last-occurrence from Google Calendar.
     */
    private suspend fun applyConfigJson(json: String): ApplyStats {
        val root = JSONObject(json)
        val version = root.optInt("schemaVersion", -1)
        require(version == SCHEMA_VERSION) {
            "Unsupported backup version: $version (expected $SCHEMA_VERSION)"
        }

        val importedTypes = parseEventTypes(root.optJSONArray("eventTypes"))
        val importedFuture = parseFutureEvents(root.optJSONArray("futureEvents"))
        val albumConfig = parseAlbumConfig(if (root.isNull("albumConfig")) null else root.optJSONObject("albumConfig"))

        database.withTransaction {
            // Deleting event types cascades (ON DELETE CASCADE) to events + future_events.
            eventTypeDao.deleteAllEventTypes()
            eventTypeDao.insertEventTypes(importedTypes)
            if (importedFuture.isNotEmpty()) {
                futureEventDao.insertFutureEvents(importedFuture)
            }
            albumConfigDao.clearAlbumConfig()
            if (albumConfig != null) {
                albumConfigDao.insertAlbumConfig(albumConfig)
            }
        }

        // Restore preferences (outside the DB transaction). Calendar selection must be written
        // before seeding so the last-occurrence lookup targets the restored calendar.
        applySettings(root.optJSONObject("settings"))

        val seeded = seedLastOccurrences(importedTypes)
        return ApplyStats(importedTypes.size, seeded, importedFuture.size)
    }

    private fun parseEventTypes(array: JSONArray?): List<EventType> {
        if (array == null) return emptyList()
        val list = ArrayList<EventType>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(
                EventType(
                    id = o.optLong("id", 0L),
                    name = o.optString("name"),
                    description = o.optStringOrNull("description"),
                    colorId = o.optIntOrNull("colorId"),
                    shouldBubble = o.optBoolean("shouldBubble", false),
                    sortOrder = o.optInt("sortOrder", 0),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    cadence = Cadence.fromName(o.optStringOrNull("cadence"))
                )
            )
        }
        return list
    }

    private fun parseFutureEvents(array: JSONArray?): List<FutureEvent> {
        if (array == null) return emptyList()
        val list = ArrayList<FutureEvent>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(
                FutureEvent(
                    id = o.optLong("id", 0L),
                    eventTypeId = o.optLong("eventTypeId", 0L),
                    targetTime = o.optLong("targetTime", 0L),
                    notes = o.optStringOrNull("notes")
                )
            )
        }
        return list
    }

    private fun parseAlbumConfig(o: JSONObject?): AlbumConfig? {
        if (o == null) return null
        return AlbumConfig(
            id = 1,
            googlePhotosAlbumId = o.optStringOrNull("googlePhotosAlbumId"),
            googlePhotosAlbumName = o.optStringOrNull("googlePhotosAlbumName"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastVerified = o.optLongOrNull("lastVerified")
        )
    }

    private fun applySettings(settings: JSONObject?) {
        if (settings == null) return

        settings.optJSONObject("storage")?.let { storage ->
            storage.optStringOrNull("bubbleMode")?.let { name ->
                runCatching { BubbleMode.valueOf(name) }.getOrNull()?.let { storagePreferences.setBubbleMode(it) }
            }
            storage.optStringOrNull("instantEventIcon")?.let { name ->
                runCatching { InstantEventIcon.valueOf(name) }.getOrNull()?.let { storagePreferences.setInstantEventIcon(it) }
            }
            if (storage.has("googleDriveEnabled")) {
                storagePreferences.setGoogleDriveEnabled(storage.optBoolean("googleDriveEnabled", false))
            }
            if (storage.has("googlePhotosEnabled")) {
                storagePreferences.setGooglePhotosEnabled(storage.optBoolean("googlePhotosEnabled", false))
            }
        }

        settings.optJSONObject("calendar")?.let { cal ->
            calendarRepository.restoreSelection(
                id = cal.optLongOrNull("selectedCalendarId"),
                name = cal.optStringOrNull("selectedCalendarName"),
                account = cal.optStringOrNull("selectedCalendarAccount"),
                setupComplete = cal.optBoolean("calendarSetupComplete", false)
            )
            calendarRepository.setEnhancedCalendarEnabled(cal.optBoolean("enhancedCalendarEnabled", false))
        }
    }

    /**
     * For each imported event type, look up the most recent matching event in Google Calendar
     * and store one pseudo instant-event as a seed timestamp. Skips silently when there is no
     * calendar permission/selection. Returns the number of types seeded.
     */
    private suspend fun seedLastOccurrences(types: List<EventType>): Int {
        if (!calendarRepository.hasCalendarPermissions() || !calendarRepository.isCalendarSetupComplete()) {
            return 0
        }
        var seeded = 0
        for (type in types) {
            val lastTime = try {
                calendarRepository.getLastOccurrence(type.name)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to look up last occurrence for '${type.name}'", e)
                null
            }
            if (lastTime != null) {
                eventDao.insertEvent(
                    Event(
                        eventTypeId = type.id,
                        startTime = lastTime,
                        endTime = lastTime, // instant pseudo-event
                        notes = ""
                    )
                )
                seeded++
            }
        }
        return seeded
    }

    // === MediaStore (Documents/ScribCal/scribcal_config.json) ===

    private fun writeToMediaStore(context: Context, content: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val existing = findDocumentUri(context)

        val uri: Uri = if (existing != null) {
            existing
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create $DISPLAY_PATH")
        }

        // "wt" truncates existing content before writing.
        resolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Could not open $DISPLAY_PATH for writing" }
            output.write(content.toByteArray(Charsets.UTF_8))
        }

        if (existing == null) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    private fun readFromMediaStore(context: Context): String? {
        val uri = findDocumentUri(context) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun findDocumentUri(context: Context): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(FILE_NAME, RELATIVE_PATH)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}

// === org.json null-safe helpers ===

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

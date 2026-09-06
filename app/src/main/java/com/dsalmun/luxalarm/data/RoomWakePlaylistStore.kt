/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.dsalmun.luxalarm.WakePlaylist
import com.dsalmun.luxalarm.WakePlaylistEntry
import com.dsalmun.luxalarm.WakePlaylistRegistration
import com.dsalmun.luxalarm.WakePlaylistStore
import com.dsalmun.luxalarm.WakeTrack
import java.util.UUID

@Entity(tableName = "wake_playlists")
data class WakePlaylistEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "wake_tracks")
data class WakeTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val storedPath: String,
)

@Entity(
    tableName = "wake_playlist_entries",
    foreignKeys =
        [
            ForeignKey(
                entity = WakePlaylistEntity::class,
                parentColumns = ["id"],
                childColumns = ["playlistId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = WakeTrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices =
        [
            Index(value = ["playlistId", "trackId"], unique = true),
            Index(value = ["playlistId", "position"], unique = true),
            Index("trackId"),
        ],
)
data class WakePlaylistEntryEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
)

@Entity(
    tableName = "wake_playlist_selection",
    foreignKeys =
        [
            ForeignKey(
                entity = WakePlaylistEntity::class,
                parentColumns = ["id"],
                childColumns = ["playlistId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("playlistId")],
)
data class WakePlaylistSelectionEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val playlistId: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

data class WakePlaylistEntryRow(
    val entryId: String,
    val playlistId: String,
    val position: Int,
    val trackId: String,
    val trackTitle: String,
    val trackStoredPath: String,
)

@Dao
interface WakePlaylistDao {
    @Insert suspend fun insertPlaylist(playlist: WakePlaylistEntity)

    @Query("SELECT * FROM wake_playlists ORDER BY rowid")
    suspend fun listPlaylists(): List<WakePlaylistEntity>

    @Query("UPDATE wake_playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: String, name: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun selectPlaylist(selection: WakePlaylistSelectionEntity)

    @Query(
        """
        SELECT wake_playlists.* FROM wake_playlists
        INNER JOIN wake_playlist_selection
            ON wake_playlists.id = wake_playlist_selection.playlistId
        WHERE wake_playlist_selection.singletonId = 1
        """
    )
    suspend fun selectedPlaylist(): WakePlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackIfAbsent(track: WakeTrackEntity): Long

    @Query("SELECT * FROM wake_tracks WHERE id = :trackId")
    suspend fun track(trackId: String): WakeTrackEntity?

    @Query("SELECT * FROM wake_tracks ORDER BY rowid")
    suspend fun listTracks(): List<WakeTrackEntity>

    @Query("SELECT COUNT(*) FROM wake_playlist_entries WHERE playlistId = :playlistId")
    suspend fun entryCount(playlistId: String): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM wake_playlist_entries
            WHERE playlistId = :playlistId AND trackId = :trackId
        )
        """
    )
    suspend fun containsTrack(playlistId: String, trackId: String): Boolean

    @Insert suspend fun insertEntry(entry: WakePlaylistEntryEntity)

    @Query(
        """
        DELETE FROM wake_playlist_entries
        WHERE playlistId = :playlistId AND trackId = :trackId
        """
    )
    suspend fun deleteEntry(playlistId: String, trackId: String)

    @Query("SELECT * FROM wake_playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entryEntities(playlistId: String): List<WakePlaylistEntryEntity>

    @Query("UPDATE wake_playlist_entries SET position = :position WHERE id = :entryId")
    suspend fun updatePosition(entryId: String, position: Int)

    @Query(
        """
        SELECT e.id AS entryId, e.playlistId, e.position,
               t.id AS trackId, t.title AS trackTitle, t.storedPath AS trackStoredPath
        FROM wake_playlist_entries AS e
        INNER JOIN wake_tracks AS t ON t.id = e.trackId
        WHERE e.playlistId = :playlistId
        ORDER BY e.position
        """
    )
    suspend fun listEntries(playlistId: String): List<WakePlaylistEntryRow>
}

class RoomWakePlaylistStore(
    private val database: WarmlyDatabase,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WakePlaylistStore {
    private val dao = database.wakePlaylistDao()

    override suspend fun createPlaylist(name: String): WakePlaylist {
        val playlist = WakePlaylist(id = newId(), name = name)
        dao.insertPlaylist(WakePlaylistEntity(id = playlist.id, name = playlist.name))
        return playlist
    }

    override suspend fun listPlaylists(): List<WakePlaylist> =
        dao.listPlaylists().map { WakePlaylist(id = it.id, name = it.name) }

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        dao.renamePlaylist(playlistId, name)
    }

    override suspend fun selectPlaylistForWake(playlistId: String) {
        dao.selectPlaylist(WakePlaylistSelectionEntity(playlistId = playlistId))
    }

    override suspend fun selectedPlaylistForWake(): WakePlaylist? =
        dao.selectedPlaylist()?.let { WakePlaylist(id = it.id, name = it.name) }

    override suspend fun addTrackToLibrary(title: String, storedPath: String): WakeTrack {
        val track = WakeTrack(id = newId(), title = title, storedPath = storedPath)
        dao.insertTrackIfAbsent(WakeTrackEntity(track.id, track.title, track.storedPath))
        return track
    }

    override suspend fun registerTrackInPlaylist(
        playlistId: String,
        track: WakeTrack,
    ): WakePlaylistRegistration = database.withTransaction {
        dao.insertTrackIfAbsent(WakeTrackEntity(track.id, track.title, track.storedPath))
        val libraryTrack = requireNotNull(dao.track(track.id)).toModel()
        val existing =
            dao.listEntries(playlistId).singleOrNull { it.trackId == track.id }?.toModel()
        if (existing != null) {
            WakePlaylistRegistration.AlreadyPresent(existing)
        } else {
            val entity =
                WakePlaylistEntryEntity(
                    id = newId(),
                    playlistId = playlistId,
                    trackId = libraryTrack.id,
                    position = dao.entryCount(playlistId),
                )
            dao.insertEntry(entity)
            WakePlaylistRegistration.Added(
                dao.listEntries(playlistId).single { it.entryId == entity.id }.toModel()
            )
        }
    }

    override suspend fun listLibraryTracks(): List<WakeTrack> =
        dao.listTracks().map { WakeTrack(it.id, it.title, it.storedPath) }

    override suspend fun addTrack(playlistId: String, trackId: String): WakePlaylistEntry =
        database.withTransaction {
            require(!dao.containsTrack(playlistId, trackId)) {
                "Track is already in this playlist"
            }
            val entity =
                WakePlaylistEntryEntity(
                    id = newId(),
                    playlistId = playlistId,
                    trackId = trackId,
                    position = dao.entryCount(playlistId),
                )
            dao.insertEntry(entity)
            dao.listEntries(playlistId).single { it.entryId == entity.id }.toModel()
        }

    override suspend fun removeTrack(playlistId: String, trackId: String) {
        database.withTransaction {
            val entries = dao.entryEntities(playlistId)
            val removed = entries.singleOrNull { it.trackId == trackId } ?: return@withTransaction
            dao.deleteEntry(playlistId, trackId)
            entries
                .filter { it.position > removed.position }
                .forEach { dao.updatePosition(it.id, it.position - 1) }
        }
    }

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) {
        database.withTransaction {
            val entries = dao.entryEntities(playlistId)
            require(position in entries.indices) { "Position is outside this playlist" }
            val moved = entries.singleOrNull { it.trackId == trackId }
            requireNotNull(moved) { "Track is not in this playlist" }
            if (moved.position == position) return@withTransaction

            dao.updatePosition(moved.id, -1)
            if (moved.position < position) {
                entries
                    .filter { it.position in (moved.position + 1)..position }
                    .forEach { dao.updatePosition(it.id, it.position - 1) }
            } else {
                entries
                    .filter { it.position in position until moved.position }
                    .asReversed()
                    .forEach { dao.updatePosition(it.id, it.position + 1) }
            }
            dao.updatePosition(moved.id, position)
        }
    }

    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> =
        dao.listEntries(playlistId).map { it.toModel() }

    private fun WakeTrackEntity.toModel() =
        WakeTrack(id = id, title = title, storedPath = storedPath)

    private fun WakePlaylistEntryRow.toModel() =
        WakePlaylistEntry(
            id = entryId,
            playlistId = playlistId,
            position = position,
            track = WakeTrack(id = trackId, title = trackTitle, storedPath = trackStoredPath),
        )
}

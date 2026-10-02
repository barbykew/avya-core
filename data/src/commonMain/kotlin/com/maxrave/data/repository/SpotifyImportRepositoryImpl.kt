package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.importdata.ImportResult
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SpotifyImportProgress
import com.maxrave.domain.repository.SpotifyImportRepository
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.logger.Logger
import com.maxrave.spotify.Spotify
import com.maxrave.spotify.model.response.spotify.playlist.SpotifyPlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "SpotifyImportRepositoryImpl"

/** Same batching rationale as `ImportRepositoryImpl`: one commit per batch, not per song. */
private const val SONG_BATCH_SIZE = 500

/**
 * How far a YouTube Music result may be from the Spotify running time and still count.
 *
 * Six seconds is wide enough for the ordinary disagreement between two catalogues about where a
 * track ends (fades, trailing silence, a tacked-on intro) and narrow enough to exclude the things
 * that actually rank well in a title search: live takes, extended mixes, sped-up edits.
 */
private const val DURATION_TOLERANCE_SECONDS = 6

/** Stop paging a pathological playlist rather than looping forever on a bad `next` link. */
private const val MAX_PAGES = 200

internal class SpotifyImportRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    private val spotify: Spotify,
    private val dataStoreManager: DataStoreManager,
) : SpotifyImportRepository {
    override fun importPlaylist(
        playlistUrl: String,
        invalidUrlMessage: String,
        notLoggedInMessage: String,
    ): Flow<SpotifyImportProgress> =
        flow {
            emit(SpotifyImportProgress.Preparing)

            val playlistId = parsePlaylistId(playlistUrl)
            if (playlistId == null) {
                Logger.e(TAG, "importPlaylist: no playlist id in input")
                emit(SpotifyImportProgress.Error(invalidUrlMessage))
                return@flow
            }

            val token = personalToken()
            if (token.isNullOrEmpty()) {
                Logger.e(TAG, "importPlaylist: no Spotify session")
                emit(SpotifyImportProgress.Error(notLoggedInMessage))
                return@flow
            }

            val playlist = spotify.getSpotifyPlaylist(playlistId, token).getOrNull()
            if (playlist == null) {
                Logger.e(TAG, "importPlaylist: playlist $playlistId could not be read")
                emit(SpotifyImportProgress.Error(invalidUrlMessage))
                return@flow
            }

            // Page the whole playlist up front: the total is needed for progress, and nothing is
            // written until every track has been looked up anyway.
            val tracks = mutableListOf<SpotifyPlaylistTrack>()
            tracks += playlist.tracks?.items.orEmpty().mapNotNull { it.track }
            var next = playlist.tracks?.next
            var pages = 0
            while (next != null && pages < MAX_PAGES) {
                val page = spotify.getSpotifyPlaylistTracksPage(next, token).getOrNull() ?: break
                tracks += page.items.mapNotNull { it.track }
                next = page.next
                pages++
            }

            // Local files have no Spotify catalogue entry and nothing to search for by id; a
            // nameless entry is a removed track. Both are dropped before counting, so they do not
            // inflate the "couldn't find" tally with things that were never findable.
            val playable = tracks.filter { !it.isLocal && !it.name.isNullOrBlank() }
            if (playable.isEmpty()) {
                Logger.e(TAG, "importPlaylist: playlist $playlistId has no playable tracks")
                emit(SpotifyImportProgress.Error(invalidUrlMessage))
                return@flow
            }

            val total = playable.size
            val matches = linkedMapOf<String, SongEntity>()
            var unmatched = 0

            emit(SpotifyImportProgress.Matching(matched = 0, total = total))
            playable.forEach { track ->
                val song = findMatch(track)
                if (song == null) {
                    unmatched++
                } else {
                    // A linked map keyed by videoId keeps first-seen order and silently absorbs
                    // the case where two Spotify entries resolve to the same video, which would
                    // otherwise be a duplicate row in the playlist's track table.
                    matches.putIfAbsent(song.videoId, song)
                }
                emit(SpotifyImportProgress.Matching(matched = matches.size, total = total))
            }

            if (matches.isEmpty()) {
                Logger.w(TAG, "importPlaylist: nothing matched out of $total")
                emit(
                    SpotifyImportProgress.Success(
                        result = ImportResult(playlistsCreated = 0, songsImported = 0, skippedEntries = 0),
                        unmatched = unmatched,
                    ),
                )
                return@flow
            }

            val songs = matches.values.toList()
            val videoIds = songs.map { it.videoId }

            runCatching {
                var written = 0
                songs.chunked(SONG_BATCH_SIZE).forEach { chunk ->
                    localDataSource.insertSongs(chunk)
                    written += chunk.size
                    emit(SpotifyImportProgress.Writing(processed = written, total = songs.size))
                }

                val playlistRowId =
                    localDataSource.insertLocalPlaylistWithTracks(
                        localPlaylist =
                            LocalPlaylistEntity(
                                title = playlist.name?.takeIf { it.isNotBlank() } ?: "Spotify playlist",
                                thumbnail = playlist.images.firstOrNull()?.url,
                                tracks = videoIds,
                            ),
                        videoIds = videoIds,
                    )

                ImportResult(
                    playlistsCreated = if (playlistRowId != -1L) 1 else 0,
                    songsImported = written,
                    skippedEntries = 0,
                )
            }.onSuccess { result ->
                Logger.i(TAG, "importPlaylist: $result, unmatched=$unmatched")
                emit(SpotifyImportProgress.Success(result = result, unmatched = unmatched))
            }.onFailure { throwable ->
                Logger.e(TAG, "importPlaylist: failed while writing - ${throwable.message}")
                emit(SpotifyImportProgress.Error(throwable.message ?: invalidUrlMessage))
            }
        }.flowOn(Dispatchers.IO)

    /**
     * A cached personal token when it is still valid, otherwise a fresh one from the stored
     * `sp_dc` cookie. Null when the user has not connected Spotify.
     */
    private suspend fun personalToken(): String? {
        val cached = dataStoreManager.spotifyPersonalToken.first()
        val expires = dataStoreManager.spotifyPersonalTokenExpires.first()
        if (cached.isNotEmpty() && expires > nowMillis()) return cached

        val spdc = dataStoreManager.spdc.first()
        if (spdc.isEmpty()) return null

        return spotify
            .getPersonalTokenWithTotp(spdc)
            .onSuccess {
                dataStoreManager.setSpotifyPersonalToken(it.accessToken)
                dataStoreManager.setSpotifyPersonalTokenExpires(it.accessTokenExpirationTimestampMs)
            }.getOrNull()
            ?.accessToken
    }

    /**
     * The YouTube Music song whose running time is closest to [track]'s, within
     * [DURATION_TOLERANCE_SECONDS].
     *
     * Matching on duration rather than on title is the whole point: a title cannot tell a studio
     * recording from a live take, a remix or a sped-up edit, and those routinely outrank the
     * original in search. Anything outside the tolerance is rejected, so a track is left out
     * rather than replaced by the wrong recording.
     */
    private suspend fun findMatch(track: SpotifyPlaylistTrack): SongEntity? {
        val title = track.name ?: return null
        val artists = track.artists.mapNotNull { it.name }.filter { it.isNotBlank() }
        val query = listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        val wanted = (track.durationMs / 1000L).toInt()
        if (wanted <= 0) return null

        val results =
            youTube
                .search(query, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                .orEmpty()

        val best =
            results
                .mapNotNull { item -> item.duration?.let { item to kotlin.math.abs(it - wanted) } }
                .filter { (_, delta) -> delta <= DURATION_TOLERANCE_SECONDS }
                .minByOrNull { (_, delta) -> delta }
                ?.first

        if (best == null) {
            Logger.d(TAG, "findMatch: no match within ${DURATION_TOLERANCE_SECONDS}s for \"$query\" (${wanted}s)")
            return null
        }
        return best.toSongEntity(track)
    }
}

/**
 * Milliseconds since the epoch, via the same clock the rest of the data layer uses for token
 * expiry comparisons.
 */
private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/**
 * The playlist id in [input], or null when there is none.
 *
 * Accepts the three things a user actually pastes: an open.spotify.com link (with or without a
 * `?si=` tracking suffix or a locale path segment), a `spotify:playlist:` URI, or a bare id.
 */
internal fun parsePlaylistId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val id =
        when {
            trimmed.startsWith("spotify:") ->
                trimmed.substringAfter("playlist:", "").substringBefore(':')

            trimmed.contains("open.spotify.com") ->
                trimmed
                    .substringAfter("/playlist/", "")
                    .substringBefore('?')
                    .substringBefore('/')

            else -> trimmed
        }

    // Spotify base-62 ids are 22 characters; anything else came out of a link this cannot read.
    return id.takeIf { it.length == 22 && it.all { c -> c.isLetterOrDigit() } }
}

/**
 * Mirrors `ImportSong.toSongEntity()`: the runtime-only columns start at the values a freshly
 * imported track has — not liked, never played, not downloaded, available.
 *
 * Album and artist names come from Spotify rather than from the YouTube Music result, so the
 * imported library reads the way the source playlist did; everything needed to play the track
 * (the videoId, the duration, the thumbnail) comes from the match.
 */
private fun SongItem.toSongEntity(source: SpotifyPlaylistTrack): SongEntity {
    val seconds = duration ?: (source.durationMs / 1000L).toInt()
    return SongEntity(
        videoId = id,
        albumId = null,
        albumName = source.album?.name ?: album?.name,
        artistId = null,
        artistName = source.artists.mapNotNull { it.name }.ifEmpty { artists.map { it.name } },
        duration = formatDuration(seconds),
        durationSeconds = seconds,
        isAvailable = true,
        isExplicit = explicit,
        likeStatus = "",
        thumbnails = thumbnail,
        title = title,
        videoType = "",
        category = null,
        resultType = null,
        liked = false,
        totalPlayTime = 0,
        downloadState = 0,
    )
}

/** `m:ss`, matching the display format the rest of the database stores. */
private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

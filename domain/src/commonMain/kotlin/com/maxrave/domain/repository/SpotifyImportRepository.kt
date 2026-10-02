package com.maxrave.domain.repository

import com.maxrave.domain.data.model.importdata.ImportResult
import kotlinx.coroutines.flow.Flow

/**
 * Rebuilds a Spotify playlist as a local playlist, by matching each track against YouTube Music.
 *
 * Unlike [ImportRepository], which replays a file whose tracks were already resolved to videoIds,
 * this does the resolving itself and is therefore network-bound and slow: one search per track.
 */
interface SpotifyImportRepository {
    /**
     * @param playlistUrl anything a user might paste: an open.spotify.com link, a `spotify:` URI,
     * or a bare playlist id.
     * @param invalidUrlMessage reported when no playlist id can be read out of [playlistUrl].
     * @param notLoggedInMessage reported when there is no Spotify session to read the playlist
     * with. Both are passed in rather than built here because string resources live in the app
     * module — the same shape as [ImportRepository.import].
     */
    fun importPlaylist(
        playlistUrl: String,
        invalidUrlMessage: String,
        notLoggedInMessage: String,
    ): Flow<SpotifyImportProgress>
}

/**
 * Progress of one Spotify import.
 *
 * Mirrors [ImportProgress], with an extra [Matching] phase: resolving tracks is the long part and
 * it happens before anything is written, so the two phases cannot share one counter.
 */
sealed interface SpotifyImportProgress {
    /** Reading the playlist and paging its tracks. No counts yet. */
    data object Preparing : SpotifyImportProgress

    /** [matched] of [total] tracks resolved to a YouTube Music video. */
    data class Matching(
        val matched: Int,
        val total: Int,
    ) : SpotifyImportProgress

    /** [processed] of [total] songs written. Emitted once per batch. */
    data class Writing(
        val processed: Int,
        val total: Int,
    ) : SpotifyImportProgress

    /**
     * [unmatched] counts tracks no confident YouTube Music match was found for. Surfaced rather
     * than hidden: YouTube Music genuinely does not carry everything Spotify does, so a shorter
     * playlist is expected rather than a bug.
     */
    data class Success(
        val result: ImportResult,
        val unmatched: Int,
    ) : SpotifyImportProgress

    data class Error(
        val message: String,
    ) : SpotifyImportProgress
}

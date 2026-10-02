package com.maxrave.spotify.model.response.spotify.playlist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A playlist as returned by `GET /v1/playlists/{id}` on the public Web API.
 *
 * Only the fields the importer actually reads are declared; the client ignores unknown keys, so
 * the rest of a very large response is dropped at parse time rather than modelled.
 */
@Serializable
data class SpotifyPlaylistResponse(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val tracks: SpotifyPlaylistTracksPage? = null,
)

/**
 * One page of a playlist's tracks.
 *
 * [next] is an absolute URL Spotify builds for the following page, or null on the last one, which
 * is what the importer pages on rather than computing offsets itself.
 */
@Serializable
data class SpotifyPlaylistTracksPage(
    val items: List<SpotifyPlaylistItem> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

/**
 * [track] is nullable on purpose: a playlist entry whose track has been removed from Spotify, or
 * is a local file, comes back as an item with a null (or id-less) track.
 */
@Serializable
data class SpotifyPlaylistItem(
    val track: SpotifyPlaylistTrack? = null,
)

@Serializable
data class SpotifyPlaylistTrack(
    val id: String? = null,
    val name: String? = null,
    @SerialName("duration_ms")
    val durationMs: Long = 0,
    @SerialName("is_local")
    val isLocal: Boolean = false,
    val artists: List<SpotifyTrackArtist> = emptyList(),
    val album: SpotifyTrackAlbum? = null,
)

@Serializable
data class SpotifyTrackArtist(
    val name: String? = null,
)

@Serializable
data class SpotifyTrackAlbum(
    val name: String? = null,
    val images: List<SpotifyImage> = emptyList(),
)

@Serializable
data class SpotifyImage(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

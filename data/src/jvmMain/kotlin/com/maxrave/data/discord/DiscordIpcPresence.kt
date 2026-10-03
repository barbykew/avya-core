package com.maxrave.data.discord

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.logger.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "DiscordIpcPresence"

/** Avya's own Discord application. Public: it identifies the app, it is not a credential. */
private const val APPLICATION_ID = "1555687238300598432"

/** Asset uploaded to that application, used as the small icon. */
private const val APP_ICON_ASSET = "avya"

private const val OP_HANDSHAKE = 0
private const val OP_FRAME = 1
private const val OP_CLOSE = 2

/**
 * Rich Presence over Discord's local IPC socket.
 *
 * This is the mechanism Discord documents for third-party desktop apps, and the one games and
 * editors use: the running Discord **client** exposes a pipe, and an app asks it to display an
 * activity. It is deliberately not the gateway route the Android side uses.
 *
 * The distinction matters for more than tidiness. Driving the gateway requires the user's account
 * token and connects to Discord *as that user* — a self-bot, which the Terms of Service prohibit
 * and which Discord's abuse detection cannot tell apart from a stolen token. Accounts get disabled
 * for it. Nothing here asks for, stores or transmits a credential: the only identifier sent is the
 * public application id above, and all authority comes from the Discord client the user is already
 * signed in to.
 *
 * The trade is that Discord must be running locally. When it is not, [connect] fails quietly and
 * presence is simply absent, which is the correct outcome rather than something to report.
 */
class DiscordIpcPresence {
    private var pipe: RandomAccessFile? = null
    private var channel: SocketChannel? = null
    private val lock = Any()

    @Volatile
    private var connected = false

    fun isRunning(): Boolean = connected

    /**
     * Opens the first Discord IPC endpoint that accepts us.
     *
     * Discord numbers its sockets 0-9 so several clients (stable, PTB, canary) can coexist; the
     * convention is to try each in turn and take the first that completes a handshake.
     */
    fun connect(): Boolean =
        synchronized(lock) {
            if (connected) return true
            for (i in 0..9) {
                runCatching {
                    if (openEndpoint(i)) {
                        send(OP_HANDSHAKE, buildJsonObject { put("v", 1); put("client_id", APPLICATION_ID) }.toString())
                        // Discord answers READY on the same socket; a client that is not actually
                        // listening shows up as a read failure here rather than at open time.
                        readFrame() ?: error("no handshake response on ipc-$i")
                        connected = true
                        Logger.i(TAG, "connected to discord-ipc-$i")
                        return true
                    }
                }.onFailure {
                    closeQuietly()
                }
            }
            Logger.d(TAG, "no Discord client listening; presence disabled")
            return false
        }

    private fun openEndpoint(index: Int): Boolean {
        val name = "discord-ipc-$index"
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return if (os.contains("win")) {
            val f = File("\\\\.\\pipe\\$name")
            if (!f.exists()) return false
            pipe = RandomAccessFile(f, "rw")
            true
        } else {
            val dir =
                sequenceOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP")
                    .mapNotNull { System.getenv(it) }
                    .firstOrNull() ?: "/tmp"
            val path = File(dir, name)
            if (!path.exists()) return false
            channel =
                SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(path.toPath()))
                }
            true
        }
    }

    /**
     * Publishes [song] as the current activity.
     *
     * Timestamps are derived the same way the gateway path derives them: a start in the past and
     * an end in the future, adjusted for [playbackSpeed], which is what makes Discord render a
     * live progress bar rather than a static label.
     */
    @OptIn(ExperimentalTime::class)
    fun updateSong(
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float,
        song: SongEntity,
    ): Result<Unit> =
        runCatching {
            if (!connected && !connect()) return@runCatching

            val now = Clock.System.now().toEpochMilliseconds()
            val speed = if (playbackSpeed <= 0f) 1.0f else playbackSpeed
            val start = now - (currentPlaybackTimeMillis / speed).toLong()
            val end = now + ((durationMillis - currentPlaybackTimeMillis) / speed).toLong()

            val activity =
                buildJsonObject {
                    // 2 = Listening. Older Discord builds ignore this and render "Playing"; they
                    // still show the rest of the activity correctly, so it is sent regardless.
                    put("type", 2)
                    put("details", song.title)
                    song.artistName?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let { put("state", it) }
                    putJsonObject("timestamps") {
                        put("start", start)
                        put("end", end)
                    }
                    putJsonObject("assets") {
                        // Album art is passed straight through as a URL. Discord resolves http(s)
                        // asset values itself; if a build refuses it, it falls back to showing
                        // nothing for the large image rather than failing the whole update.
                        song.thumbnails?.takeIf { it.startsWith("http") }?.let { put("large_image", it) }
                        song.albumName?.takeIf { it.isNotBlank() }?.let { put("large_text", it) }
                        put("small_image", APP_ICON_ASSET)
                        put("small_text", "Avya")
                    }
                    putJsonArray("buttons") {
                        add(
                            buildJsonObject {
                                put("label", "Listen on YouTube Music")
                                put("url", "https://music.youtube.com/watch?v=${song.videoId}")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("label", "Get Avya")
                                put("url", "https://github.com/barbykew/avya")
                            },
                        )
                    }
                }

            val frame =
                buildJsonObject {
                    put("cmd", "SET_ACTIVITY")
                    put("nonce", UUID.randomUUID().toString())
                    putJsonObject("args") {
                        put("pid", ProcessHandle.current().pid())
                        put("activity", activity)
                    }
                }
            send(OP_FRAME, frame.toString())
        }.onFailure {
            // A dropped pipe (Discord quit or restarted) must not latch: clearing the flag lets
            // the next update reconnect instead of silently never showing anything again.
            Logger.w(TAG, "presence update failed, will reconnect: ${it.message}")
            closeQuietly()
        }

    /** Clears the activity and closes the socket. */
    fun close() {
        synchronized(lock) {
            runCatching {
                if (connected) {
                    send(
                        OP_FRAME,
                        buildJsonObject {
                            put("cmd", "SET_ACTIVITY")
                            put("nonce", UUID.randomUUID().toString())
                            putJsonObject("args") { put("pid", ProcessHandle.current().pid()) }
                        }.toString(),
                    )
                    send(OP_CLOSE, "{}")
                }
            }
            closeQuietly()
        }
    }

    private fun send(
        opcode: Int,
        payload: String,
    ) {
        val body = payload.toByteArray(Charsets.UTF_8)
        val buf =
            ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(opcode)
                putInt(body.size)
                put(body)
                flip()
            }
        pipe?.let { it.write(buf.array()); return }
        channel?.let { ch -> while (buf.hasRemaining()) ch.write(buf) }
    }

    /** Reads one frame, or null if the peer gave us nothing. Payload is discarded. */
    private fun readFrame(): JsonObject? {
        val header = ByteArray(8)
        pipe?.let { p ->
            p.readFully(header)
        } ?: channel?.let { ch ->
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            while (hb.hasRemaining()) if (ch.read(hb) < 0) return null
        } ?: return null

        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.int // opcode, unused
        val length = hb.int
        if (length <= 0 || length > 1 shl 20) return JsonObject(emptyMap())

        val body = ByteArray(length)
        pipe?.readFully(body) ?: channel?.let { ch ->
            val bb = ByteBuffer.wrap(body)
            while (bb.hasRemaining()) if (ch.read(bb) < 0) return null
        }
        return runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    private fun closeQuietly() {
        connected = false
        runCatching { pipe?.close() }
        runCatching { channel?.close() }
        pipe = null
        channel = null
    }
}

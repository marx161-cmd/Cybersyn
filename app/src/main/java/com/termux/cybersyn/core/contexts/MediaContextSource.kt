package com.termux.cybersyn.core.contexts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.graphics.Bitmap
import android.os.Build
import com.termux.cybersyn.app.R
import com.termux.cybersyn.core.mqtt.MqttBridge
import com.termux.cybersyn.core.transfer.AlbumArtBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MediaContextSource : SubscriptionReadyContextSource {

    override val type = "media"

    private var lastAlbumArtHash: String? = null
    private var albumArtBitmap: Bitmap? = null
    private var lastSnapshot: MediaSnapshot? = null
    private var lastWasIdle: Boolean? = null

    private data class MediaSnapshot(
        val title: String?,
        val artist: String?,
        val album: String?,
        val length: Long,
        val isPlaying: Boolean,
        val actions: Long,
        val albumArt: Bitmap?,
        val thumbUp: Boolean?,
        val thumbDown: Boolean?,
    )

    override fun events(app: Context, onSubscribed: () -> Unit): Flow<ContextEvent> = channelFlow {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val session = withContext(Dispatchers.Main) { createMediaSession(app) }
        var lastStatus: JSONObject? = null

        val statusFlow = MqttBridge.subscribe(app, "cybersyn/comrade/media/status")
        onSubscribed()

        val collectJob = scope.launch(Dispatchers.IO) {
            statusFlow.collect { message ->
                try {
                    val status = JSONObject(message.payload)
                    lastStatus = status
                    scope.launch(Dispatchers.Main) {
                        // Request album art if hash changed — drop the old bitmap first so a
                        // track change doesn't keep showing the previous track's art.
                        val artHash = findAlbumArtHash(status)
                        if (artHash != null && artHash != lastAlbumArtHash) {
                            lastAlbumArtHash = artHash
                            albumArtBitmap = null
                            MqttBridge.publish(app, "cybersyn/comrade/action", "albumart:$artHash")
                        }
                        updateMediaSession(app, session, status)
                    }
                } catch (_: Exception) { }
            }
        }

        // Apply album art once FileOfferContextSource has downloaded it (only if it's still
        // the art we last asked for — a slow download can race a track change).
        val artJob = scope.launch(Dispatchers.Main) {
            AlbumArtBus.art.collect { (hash, bitmap) ->
                if (hash == lastAlbumArtHash) {
                    albumArtBitmap = bitmap
                    lastStatus?.let { updateMediaSession(app, session, it) }
                }
            }
        }

        // Emit status events for profile matching
        val emitJob = scope.launch(Dispatchers.IO) {
            statusFlow.collect { message ->
                try {
                    val status = JSONObject(message.payload)
                    val active = findActivePlayer(status)
                    send(
                        ContextEvent(
                            type = "event",
                            matched = true,
                            metadata = buildMediaMetadata(status, active),
                        )
                    )
                } catch (_: Exception) { }
            }
        }

        awaitClose {
            collectJob.cancel()
            artJob.cancel()
            emitJob.cancel()
            try { session.release() } catch (_: Exception) { }
        }
    }

    private fun createMediaSession(app: Context): MediaSession {
        val session = MediaSession(app, "CybersynMedia")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.play")
            }
            override fun onPause() {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.pause")
            }
            override fun onSkipToNext() {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.next")
            }
            override fun onSkipToPrevious() {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.prev")
            }
            override fun onStop() {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.stop")
            }
            override fun onSeekTo(pos: Long) {
                MqttBridge.publish(app, "cybersyn/comrade/action", "mpris.position:${pos / 1000}")
            }
            // Thumbs: toggles live on comrade (~/bin/shader-music thumb-up|thumb-down). 👎 deliberately
            // does not skip, so a mis-tap can be undone by tapping again.
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    ACTION_THUMB_UP -> MqttBridge.publish(app, "cybersyn/comrade/action", "script:shader-music thumb-up")
                    ACTION_THUMB_DOWN -> MqttBridge.publish(app, "cybersyn/comrade/action", "script:shader-music thumb-down")
                }
            }
        })

        createNotificationChannel(app)
        val notification = buildNotification(app, session)
        notifyManager(app)?.notify(NOTIFICATION_ID, notification)

        session.isActive = true
        return session
    }

    private fun updateMediaSession(app: Context, session: MediaSession, status: JSONObject) {
        val active = findActivePlayer(status)
        if (active == null) {
            // No player — set idle state, but only once (repeated identical
            // status messages shouldn't re-touch the session every time).
            if (lastWasIdle == true) return
            lastWasIdle = true
            lastSnapshot = null
            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_NONE, 0, 0f)
                .build()
            session.setPlaybackState(state)
            return
        }
        lastWasIdle = false

        val title = active.optString("title", null)
        val artist = active.optString("artist", null)
        val album = active.optString("album", null)
        val isPlaying = active.optBoolean("is_playing", false)
        val position = active.optLong("position_ms", 0)
        val length = active.optLong("length_ms", 0)
        val volume = active.optInt("volume", 100)
        val canPlay = active.optBoolean("can_play", true)
        val canPause = active.optBoolean("can_pause", true)
        val canGoNext = active.optBoolean("can_go_next", true)
        val canGoPrevious = active.optBoolean("can_go_previous", true)
        val canSeek = active.optBoolean("can_seek", length > 0)
        // Only present for local file:// tracks (what shader-music can favourite/ignore).
        val thumbUp = if (active.has("thumb_up")) active.optBoolean("thumb_up") else null
        val thumbDown = if (active.has("thumb_down")) active.optBoolean("thumb_down") else null

        val metadata = MediaMetadata.Builder()
        if (title != null) metadata.putString(MediaMetadata.METADATA_KEY_TITLE, title)
        if (artist != null) metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        if (album != null) metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        if (length > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, length)
        albumArtBitmap?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(metadata.build())

        val playState = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        var actions = 0L
        if (canPlay) actions = actions or PlaybackState.ACTION_PLAY
        if (canPause) actions = actions or PlaybackState.ACTION_PAUSE
        if (canGoNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (canGoPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO

        // The relay republishes player status on a fixed cadence regardless of
        // whether anything changed. PlaybackState already carries position +
        // speed + a timestamp, so the OS extrapolates the progress bar on its
        // own — skip the session/notification rebuild (and the SystemUI media
        // card re-render it triggers) unless something real actually changed.
        val snapshot = MediaSnapshot(title, artist, album, length, isPlaying, actions, albumArtBitmap, thumbUp, thumbDown)
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        val stateBuilder = PlaybackState.Builder()
            .setState(playState, position, if (isPlaying) 1.0f else 0f)
            .setActions(actions)
        if (thumbDown != null) {
            stateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_THUMB_DOWN, "Ignore track",
                    if (thumbDown) R.drawable.ic_thumb_down_on else R.drawable.ic_thumb_down_off,
                ).build()
            )
        }
        if (thumbUp != null) {
            stateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_THUMB_UP, "Favourite track",
                    if (thumbUp) R.drawable.ic_thumb_up_on else R.drawable.ic_thumb_up_off,
                ).build()
            )
        }
        session.setPlaybackState(stateBuilder.build())

        // Update notification
        try {
            val notification = buildNotification(app, session)
            notifyManager(app)?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    private fun findActivePlayer(status: JSONObject): JSONObject? {
        val players = status.optJSONArray("players") ?: return null
        // Return the first "is_playing" player, or the first player
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            if (p.optBoolean("is_playing", false)) return p
        }
        return if (players.length() > 0) players.getJSONObject(0) else null
    }

    private fun findAlbumArtHash(status: JSONObject): String? {
        val players = status.optJSONArray("players") ?: return null
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            val hash = p.optString("album_art_hash", null)
            if (hash != null && p.optBoolean("album_art_ready", false)) return hash
        }
        return null
    }

    private fun buildMediaMetadata(status: JSONObject, active: JSONObject?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (active != null) {
            map["player"] = active.optString("name", "")
            active.optString("title", null)?.let { map["title"] = it }
            active.optString("artist", null)?.let { map["artist"] = it }
            active.optString("album", null)?.let { map["album"] = it }
            map["is_playing"] = active.optBoolean("is_playing", false).toString()
            map["position_ms"] = active.optLong("position_ms", 0).toString()
            map["length_ms"] = active.optLong("length_ms", 0).toString()
            map["volume"] = active.optInt("volume", 100).toString()
        }
        return map
    }

    private fun buildNotification(app: Context, session: MediaSession): Notification {
        val controller = session.controller
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "No media"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(app)
        }

        val openIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(app.packageName, "${app.packageName}.ui.MainActivity")
        }

        albumArtBitmap?.let { builder.setLargeIcon(it) }

        return builder
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(PendingIntent.getActivity(app, 0, openIntent, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(playbackState?.state == PlaybackState.STATE_PLAYING)
            .setStyle(android.app.Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }

    private fun createNotificationChannel(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Cybersyn media control"
                setShowBadge(false)
            }
            app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun notifyManager(app: Context): NotificationManager? {
        return app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    companion object {
        private const val ACTION_THUMB_UP = "cybersyn.thumb_up"
        private const val ACTION_THUMB_DOWN = "cybersyn.thumb_down"
        private const val CHANNEL_ID = "cybersyn_media"
        private const val NOTIFICATION_ID = 9001
    }
}

package app.obsi.core.speech

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps read aloud going with the screen off, and draws the media notification and the lock screen
 * controls: back a sentence, play or pause, next sentence. Headphone and car buttons do the same.
 * Every Obsi app gets it through the core's manifest; nothing to set up in the app.
 */
class ReadAloudService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = ReadAloud.get(this)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN, true)
        }
        val open = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val back = SessionCommand(BACK, Bundle.EMPTY)
        val forward = SessionCommand(FORWARD, Bundle.EMPTY)
        // Custom buttons, not the standard previous and next: phone makers draw custom buttons on their
        // media card, and the standard ones would jump between files, not sentences.
        val buttons = ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK).setDisplayName("Back a sentence").setSessionCommand(back).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD).setDisplayName("Next sentence").setSessionCommand(forward).setSlots(CommandButton.SLOT_FORWARD).build(),
        )
        val b = MediaSession.Builder(this, SentencePlayer(engine.player, engine))
            .setId("obsi-listen")
            .setMediaButtonPreferences(buttons)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(back).add(forward).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        BACK -> engine.previous()
                        FORWARD -> engine.next()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
        if (open != null) b.setSessionActivity(open)
        session = b.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to ReadAloud and lives as long as the app; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val BACK = "app.obsi.listen.BACK"
        const val FORWARD = "app.obsi.listen.FORWARD"
        /** On the intent that opens the app from the notification: show what is playing ([ReadAloud.ui] has its id). */
        const val EXTRA_OPEN = "app.obsi.listen.OPEN"
    }
}

/** The whole article is one track with one time bar. Headphone and car buttons step by sentence. The standard previous and next are hidden, so Android draws no second pair. The list of sentences is hidden too. */
private class SentencePlayer(player: Player, private val engine: ReadAloud) : ForwardingPlayer(player) {
    private val hidden = listOf(
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        // Every sentence is its own item. Shown as a queue, Android copies each one, with its cover, on the
        // main thread: a long article froze the app. Without the timeline it sees only the one playing.
        Player.COMMAND_GET_TIMELINE,
    )

    override fun getAvailableCommands(): Player.Commands {
        val b = super.getAvailableCommands().buildUpon()
        hidden.forEach { b.remove(it) }
        return b.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    override fun seekToPrevious() = engine.previous()
    override fun seekToNext() = engine.next()
    override fun seekToPreviousMediaItem() = engine.previous()
    override fun seekToNextMediaItem() = engine.next()

    // One track for the whole article, so the notification's time bar shows all of it, like any other player.
    override fun getDuration(): Long = engine.wholeDurationMs().takeIf { it > 0 } ?: super.getDuration()
    override fun getContentDuration(): Long = duration
    override fun getCurrentPosition(): Long = if (engine.wholeDurationMs() > 0) engine.wholePositionMs() else super.getCurrentPosition()
    override fun getContentPosition(): Long = currentPosition
    override fun getBufferedPosition(): Long = currentPosition
    override fun getContentBufferedPosition(): Long = currentPosition
    override fun seekTo(positionMs: Long) = engine.seekWholeMs(positionMs)
}

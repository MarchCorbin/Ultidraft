package com.ultidraft.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.ultidraft.MainActivity
import com.ultidraft.R
import com.ultidraft.data.BookSession
import com.ultidraft.data.Prefs
import com.ultidraft.domain.SpokenSpan
import com.ultidraft.domain.spokenSpan
import java.util.Locale

/**
 * Narration that survives a locked screen.
 *
 * A foreground service owns the speech engine so playback keeps going with the phone in
 * a pocket, and a media session puts play/pause/skip on the lock screen and on headphone
 * buttons. Android's TextToSpeech has no pause, so pausing stops the utterance and
 * resuming re-speaks from the sentence the engine had reached — which is also what you
 * want after pausing to think about a line.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.ultidraft.PLAY"
        const val ACTION_PAUSE = "com.ultidraft.PAUSE"
        const val ACTION_TOGGLE = "com.ultidraft.TOGGLE"
        const val ACTION_NEXT_SENTENCE = "com.ultidraft.NEXT_SENTENCE"
        const val ACTION_PREV_SENTENCE = "com.ultidraft.PREV_SENTENCE"
        const val ACTION_NEXT_PARAGRAPH = "com.ultidraft.NEXT_PARAGRAPH"
        const val ACTION_PREV_PARAGRAPH = "com.ultidraft.PREV_PARAGRAPH"
        const val ACTION_JUMP = "com.ultidraft.JUMP"
        const val ACTION_SPEED = "com.ultidraft.SPEED"
        const val ACTION_VOICE = "com.ultidraft.VOICE"
        const val ACTION_STOP = "com.ultidraft.STOP"

        const val EXTRA_INDEX = "index"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_VOICE = "voice"

        private const val CHANNEL_ID = "ultidraft.playback"
        private const val NOTIFICATION_ID = 1

        /** Voices the engine offers for English, best first, for the voice picker. */
        @Volatile
        var availableVoices: List<VoiceChoice> = emptyList()
            private set

        fun send(context: Context, action: String, configure: (Intent) -> Unit = {}) {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            configure(intent)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    data class VoiceChoice(val name: String, val label: String)

    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPlay = false

    private var session: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    private var token = 0
    private var spoken: SpokenSpan? = null
    private var spanEnd = 0
    private var startedForeground = false
    private var notifiedIndex = -1

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "Ultidraft").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopEverything()
                override fun onSkipToNext() = step(forward = true, paragraph = false)
                override fun onSkipToPrevious() = step(forward = false, paragraph = false)
                override fun onFastForward() = step(forward = true, paragraph = true)
                override fun onRewind() = step(forward = false, paragraph = true)
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires the notification promptly after any startForegroundService.
        promoteToForeground()

        // Headphone and bluetooth buttons arrive here as ACTION_MEDIA_BUTTON and have to
        // be handed to the session, or the hardware play/pause key does nothing.
        session?.let { MediaButtonReceiver.handleIntent(it, intent) }

        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (BookSession.playing.value) pause() else play()
            ACTION_NEXT_SENTENCE -> step(forward = true, paragraph = false)
            ACTION_PREV_SENTENCE -> step(forward = false, paragraph = false)
            ACTION_NEXT_PARAGRAPH -> step(forward = true, paragraph = true)
            ACTION_PREV_PARAGRAPH -> step(forward = false, paragraph = true)
            ACTION_JUMP -> jumpTo(intent.getIntExtra(EXTRA_INDEX, BookSession.index.value))
            ACTION_SPEED -> applySpeed(intent.getFloatExtra(EXTRA_SPEED, 1.0f))
            ACTION_VOICE -> applyVoice(intent.getStringExtra(EXTRA_VOICE))
            ACTION_STOP -> stopEverything()
            else -> updateNotification()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseFocus()
        releaseWakeLock()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
            // Already gone; nothing to undo.
        }
        session?.isActive = false
        session?.release()
        session = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        BookSession.setPlaying(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------------ tts

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            handler.post {
                if (status != TextToSpeech.SUCCESS) {
                    BookSession.setStatus("No speech engine is available on this phone.")
                    return@post
                }
                val engine = tts ?: return@post
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val languageStatus = engine.setLanguage(Locale.US)
                if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                    languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setOnUtteranceProgressListener(progressListener)
                availableVoices = collectVoices(engine)
                Prefs(applicationContext).voiceName?.let { applyVoice(it) }
                engine.setSpeechRate(BookSession.speed.value)
                ttsReady = true
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        }
    }

    private fun collectVoices(engine: TextToSpeech): List<VoiceChoice> = try {
        engine.voices.orEmpty()
            .asSequence()
            .filter { it.locale.language == Locale.ENGLISH.language }
            .filterNot { it.isNetworkConnectionRequired }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .map { VoiceChoice(it.name, describeVoice(it)) }
            .toList()
    } catch (_: Exception) {
        // Some engines throw rather than report an empty voice list.
        emptyList()
    }

    private fun describeVoice(voice: Voice): String {
        val country = voice.locale.displayCountry.ifBlank { voice.locale.displayLanguage }
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "very high"
            voice.quality >= Voice.QUALITY_HIGH -> "high"
            voice.quality >= Voice.QUALITY_NORMAL -> "normal"
            else -> "low"
        }
        return "$country · $quality · ${voice.name}"
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            handler.post {
                if (utteranceId != token.toString() || !BookSession.playing.value) return@post
                advance()
            }
        }

        @Deprecated("Kept because the base class still declares it abstract.")
        override fun onError(utteranceId: String?) = onError(utteranceId, -1)

        override fun onError(utteranceId: String?, errorCode: Int) {
            handler.post {
                if (utteranceId != token.toString()) return@post
                BookSession.setStatus("The voice could not read that passage; skipping it.")
                advance()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            handler.post {
                if (utteranceId != token.toString()) return@post
                val sentence = spoken?.sentenceAtOffset(start) ?: return@post
                BookSession.moveTo(sentence.index, fromNarrator = true)
                // Rebuilding the notification per word would be several hundred updates a
                // minute; the sentence is the smallest thing worth showing on a lock screen.
                if (sentence.index != notifiedIndex) {
                    notifiedIndex = sentence.index
                    updateNotification()
                }
            }
        }
    }

    // ------------------------------------------------------------------- controls

    private fun play() {
        val manuscript = BookSession.manuscript.value
        if (manuscript == null || manuscript.sentences.isEmpty()) {
            BookSession.setStatus("Open a manuscript first.")
            return
        }
        if (!ttsReady) {
            pendingPlay = true
            BookSession.setStatus("Waking the voice…")
            return
        }
        if (!requestFocus()) {
            BookSession.setStatus("Another app is holding the audio.")
            return
        }
        acquireWakeLock()
        BookSession.setPlaying(true)
        speakFrom(BookSession.index.value)
    }

    private fun pause() {
        tts?.stop()
        token += 1
        BookSession.setPlaying(false)
        releaseFocus()
        releaseWakeLock()
        updateNotification()
        // Only now, when the run of sentences has ended, write the position back to the
        // synced sidecar. Doing it per sentence would churn the folder.
        BookSession.persist(applicationContext, writeExport = false)
    }

    private fun stopEverything() {
        tts?.stop()
        token += 1
        BookSession.setPlaying(false)
        BookSession.persist(applicationContext, writeExport = false)
        releaseFocus()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        startedForeground = false
        stopSelf()
    }

    private fun step(forward: Boolean, paragraph: Boolean) {
        val manuscript = BookSession.manuscript.value ?: return
        val current = BookSession.index.value
        val target = when {
            paragraph && forward -> manuscript.nextParagraphIndex(current)
            paragraph -> manuscript.previousParagraphIndex(current)
            forward -> current + 1
            else -> current - 1
        }
        jumpTo(target)
    }

    private fun jumpTo(index: Int) {
        BookSession.moveTo(index)
        if (BookSession.playing.value) speakFrom(BookSession.index.value) else updateNotification()
    }

    private fun applySpeed(speed: Float) {
        BookSession.setSpeed(applicationContext, speed)
        tts?.setSpeechRate(BookSession.speed.value)
        if (BookSession.playing.value) speakFrom(BookSession.index.value)
    }

    private fun applyVoice(name: String?) {
        val engine = tts ?: return
        val voice = engine.voices.orEmpty().firstOrNull { it.name == name } ?: return
        engine.setVoice(voice)
        Prefs(applicationContext).voiceName = voice.name
        if (BookSession.playing.value) speakFrom(BookSession.index.value)
    }

    private fun advance() {
        val manuscript = BookSession.manuscript.value ?: return
        val next = spanEnd + 1
        if (next >= manuscript.sentences.size) {
            BookSession.setStatus("Reached the end of the draft.")
            pause()
            return
        }
        speakFrom(next)
    }

    private fun speakFrom(index: Int) {
        val manuscript = BookSession.manuscript.value ?: return
        val engine = tts ?: return
        val span = manuscript.spanFrom(index) ?: return
        val rules = BookSession.sidecar?.lexicon.orEmpty()
        val prepared = spokenSpan(span, rules)

        token += 1
        spoken = prepared
        spanEnd = span.endIndex
        BookSession.moveTo(span.startIndex, fromNarrator = true)
        engine.setSpeechRate(BookSession.speed.value)
        val result = engine.speak(prepared.text, TextToSpeech.QUEUE_FLUSH, null, token.toString())
        if (result != TextToSpeech.SUCCESS) {
            BookSession.setStatus("The speech engine refused that passage.")
            BookSession.setPlaying(false)
        }
        updateNotification()
    }

    // ---------------------------------------------------------------- audio focus

    private fun requestFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        if (BookSession.playing.value) {
                            pausedByFocusLoss = true
                            pause()
                        }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (pausedByFocusLoss) {
                            pausedByFocusLoss = false
                            play()
                        }
                    }
                }
            }
            .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        val request = focusRequest ?: return
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultidraft:playback").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // --------------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Controls for the draft being read aloud."
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        startedForeground = true
    }

    private fun updateNotification() {
        val playing = BookSession.playing.value
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) BookSession.speed.value else 0f,
                )
                .build()
        )
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapterTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, BookSession.bookName ?: "Ultidraft")
                .build()
        )
        if (!startedForeground) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun currentChapterTitle(): String =
        BookSession.currentSentence()?.chapterTitle ?: (BookSession.bookName ?: "Ultidraft")

    private fun buildNotification(): android.app.Notification {
        val playing = BookSession.playing.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(BookSession.bookName ?: "Ultidraft")
            .setContentText(BookSession.currentSentence()?.text?.take(80) ?: currentChapterTitle())
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_previous,
                "Previous",
                servicePendingIntent(ACTION_PREV_SENTENCE, 1),
            )
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                servicePendingIntent(ACTION_TOGGLE, 2),
            )
            .addAction(
                R.drawable.ic_next,
                "Next",
                servicePendingIntent(ACTION_NEXT_SENTENCE, 3),
            )
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                servicePendingIntent(ACTION_STOP, 4),
            )

        session?.sessionToken?.let { token ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Convenience wrappers so the UI never builds an Intent by hand. */
object Narration {
    fun play(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_PLAY)
    fun pause(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_PAUSE)
    fun toggle(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_TOGGLE)
    fun stop(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_STOP)

    fun nextSentence(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_NEXT_SENTENCE)
    fun previousSentence(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_PREV_SENTENCE)
    fun nextParagraph(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_NEXT_PARAGRAPH)
    fun previousParagraph(context: Context) = PlaybackService.send(context, PlaybackService.ACTION_PREV_PARAGRAPH)

    fun jumpTo(context: Context, index: Int) =
        PlaybackService.send(context, PlaybackService.ACTION_JUMP) {
            it.putExtra(PlaybackService.EXTRA_INDEX, index)
        }

    fun setSpeed(context: Context, speed: Float) =
        PlaybackService.send(context, PlaybackService.ACTION_SPEED) {
            it.putExtra(PlaybackService.EXTRA_SPEED, speed)
        }

    fun setVoice(context: Context, name: String) =
        PlaybackService.send(context, PlaybackService.ACTION_VOICE) {
            it.putExtra(PlaybackService.EXTRA_VOICE, name)
        }

    fun voices(): List<PlaybackService.VoiceChoice> = PlaybackService.availableVoices
}

package chat.stoat.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import chat.stoat.R
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import livekit.org.webrtc.RtpParameters
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.nio.ByteBuffer

/**
 * Screen-share quality choices. Upstream Stoat always shares at 720p30;
 * this fork lets the user pick, and encodes with the phone's hardware H.264
 * encoder so high resolutions and frame rates stay smooth and cool.
 *
 * Width/height are the long and short side; LiveKit rotates them to match
 * the phone's orientation. 0×0 means the screen's native resolution.
 */
enum class ScreenShareQuality(
    @StringRes val label: Int,
    @StringRes val description: Int,
    val longSide: Int,
    val shortSide: Int,
    val fps: Int,
    val maxBitrate: Int,
) {
    SMOOTH_720(R.string.screenshare_quality_720_30, R.string.screenshare_quality_720_30_desc, 1280, 720, 30, 2_500_000),
    HD_1080_30(R.string.screenshare_quality_1080_30, R.string.screenshare_quality_1080_30_desc, 1920, 1080, 30, 5_000_000),
    HD_1080_60(R.string.screenshare_quality_1080_60, R.string.screenshare_quality_1080_60_desc, 1920, 1080, 60, 8_000_000),
    NATIVE_60(R.string.screenshare_quality_native_60, R.string.screenshare_quality_native_60_desc, 0, 0, 60, 10_000_000),
    ;

    /** Applies this quality to the next screen share published in [room]. */
    fun applyTo(room: Room) {
        val participant = room.localParticipant
        participant.screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
            isScreencast = true,
            // No extra crop: LiveKit already sizes the capture for the
            // current orientation, so portrait phones keep their full screen.
            captureParams = VideoCaptureParameter(longSide, shortSide, fps, adaptOutputToDimensions = false),
        )
        participant.screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(maxBitrate, fps),
            // Hardware H.264 on every phone; viewers' browsers and apps all decode it.
            videoCodec = VideoCodec.H264.codecName,
            // Text and UI stay sharp; low-resolution simulcast layers of a
            // screen are unreadable anyway.
            simulcast = false,
            degradationPreference = if (fps >= 60) {
                RtpParameters.DegradationPreference.BALANCED
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            },
        )
    }
}

/** Remembers the user's screen-share choices between calls. */
object ScreenShareSettings {
    private const val PREFS = "screenshare"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUDIO = "share_audio"

    fun quality(context: Context): ScreenShareQuality {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUALITY, null)
        return ScreenShareQuality.entries.firstOrNull { it.name == name } ?: ScreenShareQuality.HD_1080_30
    }

    fun shareAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUDIO, true)

    fun save(context: Context, quality: ScreenShareQuality, shareAudio: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, quality.name)
            .putBoolean(KEY_AUDIO, shareAudio)
            .apply()
    }

    /** Phone-audio sharing needs Android 10+ and the microphone permission (already used for voice). */
    fun canShareAudio(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/**
 * Mixes what the phone is playing (game, video) into the outgoing voice
 * track while a screen share is live, using LiveKit's ScreenAudioCapturer.
 * Apps that opt out of capture (most DRM video, calls) stay silent.
 *
 * While it runs, "mute" silences only the voice: the microphone samples are
 * zeroed before the phone audio is mixed in, so viewers keep hearing the
 * shared game or video.
 */
internal class ScreenShareAudio {
    private var capturer: ScreenAudioCapturer? = null
    private var track: LocalAudioTrack? = null

    @Volatile var voiceMuted = false

    val active: Boolean get() = capturer != null

    private val callback = object : AudioBufferCallback {
        override fun onBuffer(
            buffer: ByteBuffer,
            audioFormat: Int,
            channelCount: Int,
            sampleRate: Int,
            bytesRead: Int,
            captureTimeNs: Long,
        ): Long {
            if (voiceMuted) {
                buffer.position(0)
                val zeros = ByteArray(buffer.capacity())
                buffer.put(zeros)
                buffer.position(0)
            }
            return capturer?.onBuffer(buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs)
                ?: captureTimeNs
        }
    }

    @Suppress("MissingPermission") // checked by ScreenShareSettings.canShareAudio
    fun start(room: Room): Boolean {
        stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val screenTrack = room.localParticipant
                .getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack ?: return false
            val micTrack = room.localParticipant
                .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack ?: return false
            val audio = ScreenAudioCapturer.createFromScreenShareTrack(screenTrack) ?: return false
            // Keep the voice audible over game or video sound.
            audio.gain = 0.6f
            capturer = audio
            track = micTrack
            micTrack.setAudioBufferCallback(callback)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Could not share screen audio\n" + e.asLog() }
            stop()
            false
        }
    }

    fun stop() {
        try {
            track?.setAudioBufferCallback(null)
        } catch (_: Exception) {
        }
        capturer?.releaseAudioResources()
        capturer = null
        track = null
        voiceMuted = false
    }
}

package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.ImmichAuthType
import com.neilturner.aerialviews.models.enums.LimitLongerVideos
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.ImmichMediaPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.services.CustomRendererFactory
import com.neilturner.aerialviews.services.SambaDataSourceFactory
import com.neilturner.aerialviews.services.WebDavDataSourceFactory
import com.neilturner.aerialviews.utils.WindowHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

object VideoPlayerHelper {
    fun setRefreshRate(
        context: Context,
        framerate: Float?,
    ) {
        if (framerate == null || framerate == 0f) {
            Timber.i("Unable to get video frame rate...")
            return
        }

        Timber.i("${framerate}fps video, setting refresh rate if needed...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowHelper.setLegacyRefreshRate(context, framerate)
        }
    }

    fun disableAudioTrack(player: ExoPlayer) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
    }

    @OptIn(UnstableApi::class)
    fun buildPlayer(
        context: Context,
        prefs: GeneralPrefs,
    ): ExoPlayer {
        val parametersBuilder = DefaultTrackSelector.Parameters.Builder(context)

        if (prefs.enableTunneling) {
            parametersBuilder
                .setTunnelingEnabled(true)
        }

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = parametersBuilder.build()

        var rendererFactory = DefaultRenderersFactory(context)
        if (prefs.allowFallbackDecoders) {
            rendererFactory.setEnableDecoderFallback(true)
        }
        if (prefs.philipsDolbyVisionFix) {
            rendererFactory = CustomRendererFactory(context)
        }

        val player =
            ExoPlayer
                .Builder(context)
                .setTrackSelector(trackSelector)
                .setRenderersFactory(rendererFactory)
                .build()

        if (prefs.enablePlaybackLogging) {
            player.addAnalyticsListener(EventLogger())
        }

        if (!prefs.muteVideos) player.volume = prefs.videoVolume.toFloat() / 100 else player.volume = 0f

        // https://medium.com/androiddevelopers/prep-your-tv-app-for-android-12-9a859d9bb967
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.refreshRateSwitching) {
            Timber.i("Android 12+, enabling refresh rate switching")
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }

        player.setPlaybackSpeed(prefs.playbackSpeed.toFloat())
        return player
    }

    @OptIn(UnstableApi::class)
    fun setupMediaSource(
        player: ExoPlayer,
        media: AerialMedia,
    ) {
        val mediaItem = MediaItem.fromUri(media.uri)
        when (media.source) {
            AerialMediaSource.SAMBA -> {
                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(SambaDataSourceFactory())
                        .createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            }
            AerialMediaSource.IMMICH -> {
                val dataSourceFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())
                        .setReadTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())

                // Add necessary headers for Immich
                if (ImmichMediaPrefs.authType == ImmichAuthType.API_KEY) {
                    dataSourceFactory.setDefaultRequestProperties(
                        mapOf("X-API-Key" to ImmichMediaPrefs.apiKey),
                    )
                }

                // If SSL validation is disabled, we need to set the appropriate flags
                if (!ImmichMediaPrefs.validateSsl) {
                    System.setProperty("javax.net.ssl.trustAll", "true")
                }

                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)

                player.setMediaSource(mediaSource)
                Timber.d("Setting up Immich media source with URI: ${media.uri}")
            }
            AerialMediaSource.WEBDAV -> {
                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(WebDavDataSourceFactory())
                        .createMediaSource(mediaItem)
                player.setMediaSource(mediaSource)
            }
            else -> {
                player.setMediaItem(mediaItem)
            }
        }
    }

    fun calculateSegments(
        duration: Long,
        maxLength: Long,
        video: VideoInfo,
    ) {
        if (duration == 0L ||
            maxLength == 0L
        ) {
            return
        }

        val tenSeconds = 10 * 1000
        val segments = duration / maxLength

        // If too short or no segments
        if (maxLength < tenSeconds ||
            segments < 2
        ) {
            Timber.i("Video too short for segments")
            video.isSegmented = false
            video.segmentStart = 0L
            video.segmentEnd = 0L
            return
        }

        val length = duration.floorDiv(segments).toLong()
        val random = (1..segments).random()
        val segmentStart = (random - 1) * length
        val segmentEnd = random * length

        val message1 = "Segment chosen: ${segmentStart.milliseconds} - ${segmentEnd.milliseconds}"
        val message2 = "($random of $segments, duration: ${duration.milliseconds}"
        Timber.i("$message1 $message2")

        video.isSegmented = true
        video.segmentStart = segmentStart
        video.segmentEnd = segmentEnd
    }

    fun calculateDelay(
        video: VideoInfo,
        player: ExoPlayer,
        prefs: GeneralPrefs,
    ): Long {
        val loopShortVideos = prefs.loopShortVideos
        val allowLongerVideos = prefs.limitLongerVideos == LimitLongerVideos.IGNORE
        val maxVideoLength = prefs.maxVideoLength.toLong() * 1000
        val duration = player.duration
        val position = player.currentPosition

        // 10 seconds is the min. video length
        val tenSeconds = 10 * 1000

        // If max length disabled, play full video
        if (maxVideoLength < tenSeconds) {
            return calculateEndOfVideo(position, duration, video, prefs)
        }

        // Play a part/segment of a video only
        if (video.isSegmented) {
            val segmentPosition = if (position < video.segmentStart) 0 else position - video.segmentStart
            val segmentDuration = video.segmentEnd - video.segmentStart
            return calculateEndOfVideo(segmentPosition, segmentDuration, video, prefs)
        }

        // Check if we need to loop the video
        if (loopShortVideos &&
            duration < maxVideoLength
        ) {
            val (isLooping, loopingDuration) = calculateLoopingVideo(maxVideoLength, player)
            var targetDuration =
                if (isLooping) {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    loopingDuration
                } else {
                    duration
                }
            return calculateEndOfVideo(position, targetDuration, video, prefs)
        }

        // Limit the duration of the video, or not
        if (maxVideoLength in tenSeconds until duration &&
            !allowLongerVideos
        ) {
            Timber.i("Limiting duration (video is ${duration.milliseconds}, limit is ${maxVideoLength.milliseconds})")
            return calculateEndOfVideo(position, maxVideoLength, video, prefs)
        }
        Timber.i("Ignoring limit (video is ${duration.milliseconds}, limit is ${maxVideoLength.milliseconds})")
        return calculateEndOfVideo(position, duration, video, prefs)
    }

    private fun calculateEndOfVideo(
        position: Long,
        duration: Long,
        video: VideoInfo,
        prefs: GeneralPrefs,
    ): Long {
        // Adjust the duration based on the playback speed
        // Take into account the current player position in case of speed changes during playback
        val delay = ((duration - position) / prefs.playbackSpeed.toDouble().toLong() - prefs.mediaFadeOutDuration.toLong())
        val actualPosition = if (video.isSegmented) position + video.segmentStart else position
        Timber.i("Delay: ${delay.milliseconds} (duration: ${duration.milliseconds}, position: ${actualPosition.milliseconds})")
        return if (delay < 0) 0 else delay
    }

    private fun calculateLoopingVideo(
        maxVideoLength: Long,
        player: ExoPlayer,
    ): Pair<Boolean, Long> {
        val loopCount = ceil(maxVideoLength / player.duration.toDouble()).toInt()
        val targetDuration = player.duration * loopCount
        Timber.i("Looping $loopCount times (video is ${player.duration.milliseconds}, limit is ${maxVideoLength.milliseconds})")
        return Pair(loopCount > 1, targetDuration.toLong())
    }
}

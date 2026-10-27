package com.example.domain.connect

import kotlin.math.abs
import org.json.JSONObject
import org.json.JSONTokener

enum class PlaybackCorrectionDecision {
    NONE_IN_SYNC,
    GENTLE_RATE_ADJUST,
    DIRECT_SEEK
}

data class PlaybackSyncAdjustment(
    val decision: PlaybackCorrectionDecision,
    val targetPlayState: Boolean,
    val targetPositionSeconds: Double,
    val targetPlaybackRate: Double,
    val driftSeconds: Double,
    val nextSyncStatus: SyncConnectionStatus
)

data class WatchTogetherProbeResult(
    val compatibility: VideoCompatibilityState,
    val currentTimeSec: Double,
    val durationSec: Double,
    val isPaused: Boolean,
    val playbackRate: Double
)

/**
 * Watch Together HTML5 Video Synchronization Engine & Drift Calculator.
 *
 * Principles:
 * 1. Host's playback state is the source of truth.
 * 2. Avoids constant disruptive seeking: small drift (< 0.85s) is ignored; moderate drift
 *    (0.85s..2.2s) during active playback uses a gentle temporary rate nudge (0.95x / 1.05x)
 *    unless a seek cooldown has elapsed; large drift (>= 2.2s) or play/pause transition
 *    performs a clean direct seek.
 * 3. Never attempts to bypass DRM (Encrypted Media Extensions `mediaKeys`), cross-origin
 *    iframe boundaries, or website access controls.
 */
object WatchTogetherMediaBridge {

    private const val IN_SYNC_TOLERANCE_SEC = 0.85
    private const val HARD_SEEK_THRESHOLD_SEC = 2.20
    private const val SEEK_COOLDOWN_MS = 4000L

    fun calculatePlaybackAdjustment(
        hostState: WatchTogetherPlaybackState,
        localPositionSeconds: Double,
        localIsPlaying: Boolean,
        nowMillis: Long,
        lastSeekAtMillis: Long,
        forceManualResync: Boolean = false
    ): PlaybackSyncAdjustment {
        val networkLatencySec = if (hostState.isPlaying) {
            val elapsedMs = (nowMillis - hostState.updatedAtMillis).coerceIn(0L, 6000L)
            (elapsedMs / 1000.0) * hostState.playbackRate
        } else {
            0.0
        }

        val rawTargetPos = (hostState.positionSeconds + networkLatencySec).coerceAtLeast(0.0)
        val clampedTargetPos = if (hostState.durationSeconds > 0.0) {
            rawTargetPos.coerceAtMost(hostState.durationSeconds)
        } else {
            rawTargetPos
        }

        val signedDrift = clampedTargetPos - localPositionSeconds
        val absDrift = abs(signedDrift)
        val playStateMismatch = hostState.isPlaying != localIsPlaying
        val cooldownElapsed = (nowMillis - lastSeekAtMillis) >= SEEK_COOLDOWN_MS

        return when {
            forceManualResync ||
                absDrift >= HARD_SEEK_THRESHOLD_SEC ||
                (playStateMismatch && absDrift >= 0.40) ||
                (!hostState.isPlaying && absDrift >= 0.50) ||
                (absDrift >= 1.25 && cooldownElapsed) -> {
                PlaybackSyncAdjustment(
                    decision = PlaybackCorrectionDecision.DIRECT_SEEK,
                    targetPlayState = hostState.isPlaying,
                    targetPositionSeconds = clampedTargetPos,
                    targetPlaybackRate = hostState.playbackRate,
                    driftSeconds = signedDrift,
                    nextSyncStatus = SyncConnectionStatus.SYNCHRONIZING
                )
            }
            hostState.isPlaying && localIsPlaying && absDrift >= IN_SYNC_TOLERANCE_SEC -> {
                // Gentle 5% rate adjustment to converge smoothly without audio/video seek glitch
                val nudgeFactor = if (signedDrift > 0) 1.05 else 0.95
                val nudgedRate = (hostState.playbackRate * nudgeFactor).coerceIn(0.5, 2.0)
                PlaybackSyncAdjustment(
                    decision = PlaybackCorrectionDecision.GENTLE_RATE_ADJUST,
                    targetPlayState = true,
                    targetPositionSeconds = clampedTargetPos,
                    targetPlaybackRate = nudgedRate,
                    driftSeconds = signedDrift,
                    nextSyncStatus = SyncConnectionStatus.SYNCHRONIZING
                )
            }
            playStateMismatch -> {
                PlaybackSyncAdjustment(
                    decision = PlaybackCorrectionDecision.DIRECT_SEEK,
                    targetPlayState = hostState.isPlaying,
                    targetPositionSeconds = clampedTargetPos,
                    targetPlaybackRate = hostState.playbackRate,
                    driftSeconds = signedDrift,
                    nextSyncStatus = SyncConnectionStatus.SYNCHRONIZING
                )
            }
            else -> {
                PlaybackSyncAdjustment(
                    decision = PlaybackCorrectionDecision.NONE_IN_SYNC,
                    targetPlayState = hostState.isPlaying,
                    targetPositionSeconds = clampedTargetPos,
                    targetPlaybackRate = hostState.playbackRate,
                    driftSeconds = signedDrift,
                    nextSyncStatus = SyncConnectionStatus.SYNCED
                )
            }
        }
    }

    /**
     * JavaScript injected into the active WebView to inspect HTML5 `<video>` compatibility
     * (respecting DRM and iframe restrictions) and attach non-intrusive playback listeners.
     */
    fun buildMediaHookAndProbeScript(): String {
        return """
            (function() {
                try {
                    var videos = document.getElementsByTagName('video');
                    if (!videos || videos.length === 0) {
                        var iframes = document.getElementsByTagName('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            var src = (iframes[i].src || '').toLowerCase();
                            if (src.indexOf('youtube.com/embed') !== -1 ||
                                src.indexOf('player.vimeo.com') !== -1 ||
                                src.indexOf('dailymotion.com/embed') !== -1 ||
                                src.indexOf('twitch.tv') !== -1) {
                                return JSON.stringify({ status: 'IFRAME_OR_EMBED_RESTRICTED', currentTime: 0, duration: 0, paused: true, rate: 1.0 });
                            }
                        }
                        return JSON.stringify({ status: 'NO_VIDEO_DETECTED', currentTime: 0, duration: 0, paused: true, rate: 1.0 });
                    }
                    var v = videos[0];
                    if (v.mediaKeys || v.webkitKeys) {
                        return JSON.stringify({ status: 'DRM_PROTECTED_UNSUPPORTED', currentTime: 0, duration: 0, paused: true, rate: 1.0 });
                    }
                    if (!v.__nyxoraConnectHooked) {
                        v.__nyxoraConnectHooked = true;
                        var notify = function(evType) {
                            if (window.__nyxoraApplyingRemoteSync) return;
                            if (window.NyxoraConnectJsBridge && window.NyxoraConnectJsBridge.onMediaEvent) {
                                window.NyxoraConnectJsBridge.onMediaEvent(
                                    evType,
                                    Number(v.currentTime || 0),
                                    Number(v.duration || 0),
                                    Boolean(v.paused),
                                    Number(v.playbackRate || 1.0)
                                );
                            }
                        };
                        v.addEventListener('play', function() { notify('PLAY'); }, { passive: true });
                        v.addEventListener('pause', function() { notify('PAUSE'); }, { passive: true });
                        v.addEventListener('seeked', function() { notify('SEEK'); }, { passive: true });
                        v.addEventListener('ratechange', function() { notify('RATE'); }, { passive: true });
                    }
                    return JSON.stringify({
                        status: 'COMPATIBLE_HTML5',
                        currentTime: Number(v.currentTime || 0),
                        duration: Number(isFinite(v.duration) ? v.duration : 0),
                        paused: Boolean(v.paused),
                        rate: Number(v.playbackRate || 1.0)
                    });
                } catch (e) {
                    return JSON.stringify({ status: 'NO_VIDEO_DETECTED', currentTime: 0, duration: 0, paused: true, rate: 1.0 });
                }
            })();
        """.trimIndent()
    }

    /**
     * JavaScript command to apply a synchronized playback state to the primary HTML5 `<video>`
     * element while suppressing feedback-loop events via `window.__nyxoraApplyingRemoteSync`.
     */
    fun buildApplyRemotePlaybackScript(
        shouldPlay: Boolean,
        seekToSeconds: Double?,
        playbackRate: Double
    ): String {
        val safeRate = playbackRate.coerceIn(0.25, 2.0)
        val seekLine = if (seekToSeconds != null && !seekToSeconds.isNaN() && seekToSeconds >= 0.0) {
            "if (Math.abs((v.currentTime || 0) - $seekToSeconds) > 0.25) { v.currentTime = $seekToSeconds; }"
        } else {
            ""
        }
        val playPauseLine = if (shouldPlay) {
            "if (v.paused) { var p = v.play(); if (p && p.catch) { p.catch(function(){}); } }"
        } else {
            "if (!v.paused) { v.pause(); }"
        }
        return """
            (function() {
                try {
                    var v = document.querySelector('video');
                    if (!v || v.mediaKeys || v.webkitKeys) return false;
                    window.__nyxoraApplyingRemoteSync = true;
                    if (Math.abs((v.playbackRate || 1.0) - $safeRate) > 0.02) {
                        v.playbackRate = $safeRate;
                    }
                    $seekLine
                    $playPauseLine
                    setTimeout(function() { window.__nyxoraApplyingRemoteSync = false; }, 320);
                    return true;
                } catch (e) {
                    window.__nyxoraApplyingRemoteSync = false;
                    return false;
                }
            })();
        """.trimIndent()
    }

    fun parseProbeResult(rawJsResult: String?): WatchTogetherProbeResult {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") {
            return WatchTogetherProbeResult(
                compatibility = VideoCompatibilityState.NO_VIDEO_DETECTED,
                currentTimeSec = 0.0,
                durationSec = 0.0,
                isPaused = true,
                playbackRate = 1.0
            )
        }
        return try {
            val unquoted = if (rawJsResult.startsWith("\"") && rawJsResult.endsWith("\"")) {
                JSONTokener(rawJsResult).nextValue().toString()
            } else {
                rawJsResult
            }
            val obj = JSONObject(unquoted)
            val statusStr = obj.optString("status", "NO_VIDEO_DETECTED")
            val compat = runCatching { VideoCompatibilityState.valueOf(statusStr) }
                .getOrDefault(VideoCompatibilityState.NO_VIDEO_DETECTED)
            val curTime = obj.optDouble("currentTime", 0.0).let { if (it.isNaN()) 0.0 else it }
            val dur = obj.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }
            val paused = obj.optBoolean("paused", true)
            val rate = obj.optDouble("rate", 1.0).let { if (it.isNaN()) 1.0 else it }
            WatchTogetherProbeResult(
                compatibility = compat,
                currentTimeSec = curTime,
                durationSec = dur,
                isPaused = paused,
                playbackRate = rate
            )
        } catch (_: Exception) {
            WatchTogetherProbeResult(
                compatibility = VideoCompatibilityState.NO_VIDEO_DETECTED,
                currentTimeSec = 0.0,
                durationSec = 0.0,
                isPaused = true,
                playbackRate = 1.0
            )
        }
    }
}

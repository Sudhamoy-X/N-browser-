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

data class MediaControlActionResult(
    val succeeded: Boolean,
    val compatibility: VideoCompatibilityState,
    val isPlaying: Boolean,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val playbackRate: Double,
    val failureReason: String? = null
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
     * Evaluates a user-triggered Watch Together playback control action (Play/Pause, -10s, +10s)
     * against the current compatibility state and room playback state when WebView DOM evaluation
     * is unavailable or returns null.
     *
     * Never reports success when media is unsupported, DRM-protected, cross-origin restricted,
     * or when no supported media is detected.
     */
    fun computeControlledPlaybackState(
        currentRoomPlayback: WatchTogetherPlaybackState?,
        currentCompatibility: VideoCompatibilityState,
        shouldPlay: Boolean,
        seekDeltaSeconds: Double?
    ): MediaControlActionResult {
        when (currentCompatibility) {
            VideoCompatibilityState.DRM_PROTECTED_UNSUPPORTED,
            VideoCompatibilityState.IFRAME_OR_EMBED_RESTRICTED,
            VideoCompatibilityState.NO_VIDEO_DETECTED -> {
                return MediaControlActionResult(
                    succeeded = false,
                    compatibility = currentCompatibility,
                    isPlaying = false,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0,
                    playbackRate = 1.0,
                    failureReason = currentCompatibility.label
                )
            }
            VideoCompatibilityState.UNKNOWN -> {
                if (currentRoomPlayback == null) {
                    return MediaControlActionResult(
                        succeeded = false,
                        compatibility = VideoCompatibilityState.NO_VIDEO_DETECTED,
                        isPlaying = false,
                        positionSeconds = 0.0,
                        durationSeconds = 0.0,
                        playbackRate = 1.0,
                        failureReason = "No supported media detected on current page"
                    )
                }
            }
            VideoCompatibilityState.COMPATIBLE_HTML5 -> Unit
        }

        val basePos = (currentRoomPlayback?.positionSeconds ?: 0.0).coerceAtLeast(0.0)
        val dur = (currentRoomPlayback?.durationSeconds ?: 0.0).coerceAtLeast(0.0)
        val rate = (currentRoomPlayback?.playbackRate ?: 1.0).coerceIn(0.25, 2.0)
        val nextPos = if (seekDeltaSeconds != null && !seekDeltaSeconds.isNaN()) {
            val raw = (basePos + seekDeltaSeconds).coerceAtLeast(0.0)
            if (dur > 0.0) raw.coerceAtMost(dur) else raw
        } else {
            basePos
        }

        return MediaControlActionResult(
            succeeded = true,
            compatibility = VideoCompatibilityState.COMPATIBLE_HTML5,
            isPlaying = shouldPlay,
            positionSeconds = nextPos,
            durationSeconds = dur,
            playbackRate = rate,
            failureReason = null
        )
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
                    for (var k = 0; k < videos.length; k++) {
                        if (!videos[k].paused || videos[k].readyState >= 2) {
                            v = videos[k];
                            break;
                        }
                    }
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
                                    Number(isFinite(v.duration) ? v.duration : 0),
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
     * JavaScript command to execute a local user Watch Together control action (Play/Pause, -10s, +10s)
     * directly on the active HTML5 `<video>` element and return a verified JSON result.
     */
    fun buildExecuteControlActionScript(
        shouldPlay: Boolean,
        seekDeltaSeconds: Double?,
        fallbackPositionSeconds: Double = 0.0,
        fallbackDurationSeconds: Double = 0.0
    ): String {
        val safeFallbackPos = if (fallbackPositionSeconds.isNaN() || fallbackPositionSeconds < 0.0) 0.0 else fallbackPositionSeconds
        val safeFallbackDur = if (fallbackDurationSeconds.isNaN() || fallbackDurationSeconds < 0.0) 0.0 else fallbackDurationSeconds
        val hasSeekDelta = seekDeltaSeconds != null && !seekDeltaSeconds.isNaN()
        val seekDeltaVal = if (hasSeekDelta) seekDeltaSeconds!! else 0.0

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
                                return JSON.stringify({
                                    ok: false,
                                    status: 'IFRAME_OR_EMBED_RESTRICTED',
                                    reason: 'Cross-origin embedded player cannot be controlled directly',
                                    isPlaying: false,
                                    currentTime: 0,
                                    duration: 0,
                                    rate: 1.0
                                });
                            }
                        }
                        return JSON.stringify({
                            ok: false,
                            status: 'NO_VIDEO_DETECTED',
                            reason: 'No supported HTML5 video detected on this page',
                            isPlaying: false,
                            currentTime: 0,
                            duration: 0,
                            rate: 1.0
                        });
                    }
                    var v = videos[0];
                    for (var k = 0; k < videos.length; k++) {
                        if (!videos[k].paused || videos[k].readyState >= 2) {
                            v = videos[k];
                            break;
                        }
                    }
                    if (v.mediaKeys || v.webkitKeys) {
                        return JSON.stringify({
                            ok: false,
                            status: 'DRM_PROTECTED_UNSUPPORTED',
                            reason: 'DRM-protected video restricts external playback control',
                            isPlaying: false,
                            currentTime: 0,
                            duration: 0,
                            rate: 1.0
                        });
                    }
                    window.__nyxoraApplyingRemoteSync = true;
                    var rawCur = Number(v.currentTime || 0);
                    var basePos = (rawCur > 0 || $safeFallbackPos <= 0) ? rawCur : $safeFallbackPos;
                    var rawDur = (isFinite(v.duration) && v.duration > 0) ? Number(v.duration) : $safeFallbackDur;
                    var nextPos = basePos;
                    if ($hasSeekDelta) {
                        nextPos = Math.max(0, basePos + ($seekDeltaVal));
                        if (rawDur > 0) {
                            nextPos = Math.min(rawDur, nextPos);
                        }
                        v.currentTime = nextPos;
                    }
                    if ($shouldPlay) {
                        if (v.paused) {
                            var p = v.play();
                            if (p && p.catch) { p.catch(function(){}); }
                        }
                    } else {
                        if (!v.paused) {
                            v.pause();
                        }
                    }
                    setTimeout(function() { window.__nyxoraApplyingRemoteSync = false; }, 320);
                    return JSON.stringify({
                        ok: true,
                        status: 'COMPATIBLE_HTML5',
                        reason: null,
                        isPlaying: Boolean($shouldPlay),
                        currentTime: Number(nextPos),
                        duration: Number(rawDur),
                        rate: Number(v.playbackRate || 1.0)
                    });
                } catch (e) {
                    window.__nyxoraApplyingRemoteSync = false;
                    return JSON.stringify({
                        ok: false,
                        status: 'NO_VIDEO_DETECTED',
                        reason: (e && e.message) ? e.message : 'Playback control failed',
                        isPlaying: false,
                        currentTime: 0,
                        duration: 0,
                        rate: 1.0
                    });
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
            """
            var clampedTarget = (isFinite(v.duration) && v.duration > 0) ? Math.min(v.duration, $seekToSeconds) : $seekToSeconds;
            if (Math.abs((v.currentTime || 0) - clampedTarget) > 0.25) { v.currentTime = clampedTarget; }
            """.trimIndent()
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
                    var videos = document.getElementsByTagName('video');
                    if (!videos || videos.length === 0) return false;
                    var v = videos[0];
                    for (var k = 0; k < videos.length; k++) {
                        if (!videos[k].paused || videos[k].readyState >= 2) {
                            v = videos[k];
                            break;
                        }
                    }
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

    fun parseControlActionResult(rawJsResult: String?): MediaControlActionResult? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return try {
            val unquoted = if (rawJsResult.startsWith("\"") && rawJsResult.endsWith("\"")) {
                JSONTokener(rawJsResult).nextValue().toString()
            } else {
                rawJsResult
            }
            val obj = JSONObject(unquoted)
            val ok = obj.optBoolean("ok", false)
            val statusStr = obj.optString("status", "").ifBlank {
                obj.optString("compat", "NO_VIDEO_DETECTED")
            }
            val compat = runCatching { VideoCompatibilityState.valueOf(statusStr) }
                .getOrDefault(VideoCompatibilityState.NO_VIDEO_DETECTED)
            val reason = obj.optString("reason", "").takeIf { it.isNotBlank() && it != "null" }
            val isPlaying = if (obj.has("isPlaying")) {
                obj.optBoolean("isPlaying", false)
            } else if (obj.has("paused")) {
                !obj.optBoolean("paused", true)
            } else {
                false
            }
            val curTime = obj.optDouble("currentTime", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val dur = obj.optDouble("duration", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
            val rawRate = if (obj.has("rate")) obj.optDouble("rate", 1.0) else obj.optDouble("playbackRate", 1.0)
            val rate = rawRate.let { if (it.isNaN()) 1.0 else it.coerceIn(0.25, 2.0) }
            MediaControlActionResult(
                succeeded = ok,
                compatibility = compat,
                isPlaying = isPlaying,
                positionSeconds = curTime,
                durationSeconds = dur,
                playbackRate = rate,
                failureReason = reason
            )
        } catch (_: Exception) {
            null
        }
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

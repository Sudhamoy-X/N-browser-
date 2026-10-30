package com.example.domain.browser

import android.content.ComponentCallbacks2
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

enum class MemoryPressureLevel(val label: String) {
    NORMAL("Normal"),
    MODERATE("Moderate"),
    HIGH("High"),
    CRITICAL("Critical")
}

data class DiagnosticEventEntry(
    val timestampMillis: Long = System.currentTimeMillis(),
    val category: String,
    val message: String
)

data class BrowserDiagnosticsSnapshot(
    val usedHeapMb: Long = 0L,
    val maxHeapMb: Long = 1L,
    val memoryUsagePercent: Int = 0,
    val memoryPressureLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL,
    val trimMemoryCount: Int = 0,
    val lastTrimMemoryLevel: Int = 0,
    val processCpuTimeMs: Long = 0L,
    val cpuUsagePercent: Double = 0.0,
    val activeWebViewCount: Int = 0,
    val evictedWebViewCount: Int = 0,
    val duplicateWebViewPreventedCount: Int = 0,
    val suspendedTabCount: Int = 0,
    val rendererCrashCount: Int = 0,
    val uncaughtErrorCount: Int = 0,
    val lastCrashOrErrorSummary: String? = null,
    val wsConnectionFailureCount: Int = 0,
    val wsReconnectCount: Int = 0,
    val lastWsHeartbeatRttMs: Long = 0L,
    val subresourceCheckCount: Long = 0L,
    val subresourceBlockedCount: Long = 0L,
    val avgSubresourceCheckMicros: Double = 0.0,
    val recentEvents: List<DiagnosticEventEntry> = emptyList(),
    val capturedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Lightweight, lock-free diagnostics collector for NYXORA Browser.
 * Tracks CPU usage, JVM memory pressure, WebView pool & tab suspension lifecycle,
 * renderer crash recovery, WebSocket connection health/RTT, and ad-block filter latency.
 */
object BrowserDiagnosticsEngine {
    private const val MAX_RECENT_EVENTS = 24

    private val trimMemoryCounter = AtomicInteger(0)
    private val lastTrimLevel = AtomicInteger(0)
    private val activeWebViews = AtomicInteger(0)
    private val evictedWebViews = AtomicInteger(0)
    private val duplicateWebViewsPrevented = AtomicInteger(0)
    private val suspendedTabs = AtomicInteger(0)
    private val rendererCrashes = AtomicInteger(0)
    private val uncaughtErrors = AtomicInteger(0)
    private val wsConnectionFailures = AtomicInteger(0)
    private val wsReconnects = AtomicInteger(0)
    private val lastWsRttMs = AtomicLong(0L)

    private val subresourceChecks = AtomicLong(0L)
    private val subresourceBlocked = AtomicLong(0L)
    private val subresourceTotalDurationNs = AtomicLong(0L)

    @Volatile
    private var lastCrashOrError: String? = null

    @Volatile
    private var lastCpuSampleWallMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var lastCpuSampleProcessMs: Long = runCatching { Process.getElapsedCpuTime() }.getOrDefault(0L)

    @Volatile
    private var lastComputedCpuPercent: Double = 0.0

    private val recentEventsDeque = ConcurrentLinkedDeque<DiagnosticEventEntry>()

    private val _snapshotFlow = MutableStateFlow(BrowserDiagnosticsSnapshot())
    val snapshotFlow: StateFlow<BrowserDiagnosticsSnapshot> = _snapshotFlow.asStateFlow()

    fun recordSubresourceCheck(durationNs: Long, blocked: Boolean) {
        val count = subresourceChecks.incrementAndGet()
        if (blocked) {
            subresourceBlocked.incrementAndGet()
        }
        if (durationNs > 0L) {
            subresourceTotalDurationNs.addAndGet(durationNs)
        }
        // Refresh live snapshot occasionally to avoid hot-path overhead on IO threads
        if (count % 128L == 0L) {
            refreshSnapshotInternal(sampleCpu = false)
        }
    }

    fun updateActiveWebViewCount(activeCount: Int) {
        activeWebViews.set(max(0, activeCount))
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordWebViewEvicted(reason: String = "pool_or_suspend") {
        evictedWebViews.incrementAndGet()
        appendEvent("WEBVIEW", "Evicted WebView ($reason); active=${activeWebViews.get()}")
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordWebViewReusedFromPool(tabId: String) {
        duplicateWebViewsPrevented.incrementAndGet()
    }

    fun updateSuspendedTabCount(count: Int) {
        suspendedTabs.set(max(0, count))
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordRendererCrash(tabId: String, didCrash: Boolean) {
        rendererCrashes.incrementAndGet()
        val summary = "WebView renderer gone (tab=$tabId, crashed=$didCrash)"
        lastCrashOrError = summary
        appendEvent("CRASH", summary)
        refreshSnapshotInternal(sampleCpu = true)
    }

    fun recordRecoveredError(category: String, message: String) {
        uncaughtErrors.incrementAndGet()
        val summary = "$category: ${message.take(120)}"
        lastCrashOrError = summary
        appendEvent("ERROR", summary)
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordWebSocketConnectionFailure(reason: String) {
        wsConnectionFailures.incrementAndGet()
        val summary = "WS failure: ${reason.take(100)}"
        lastCrashOrError = summary
        appendEvent("WEBSOCKET", summary)
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordWebSocketReconnect(attempt: Int) {
        wsReconnects.incrementAndGet()
        appendEvent("WEBSOCKET", "Reconnect scheduled (attempt #$attempt)")
        refreshSnapshotInternal(sampleCpu = false)
    }

    fun recordWebSocketHeartbeatRtt(rttMs: Long) {
        if (rttMs >= 0L) {
            lastWsRttMs.set(rttMs)
            refreshSnapshotInternal(sampleCpu = false)
        }
    }

    fun onTrimMemory(level: Int) {
        trimMemoryCounter.incrementAndGet()
        lastTrimLevel.set(level)
        appendEvent("MEMORY", "onTrimMemory level=$level")
        refreshSnapshotInternal(sampleCpu = true)
    }

    fun captureSnapshot(): BrowserDiagnosticsSnapshot {
        return refreshSnapshotInternal(sampleCpu = true)
    }

    fun resetDiagnostics() {
        trimMemoryCounter.set(0)
        lastTrimLevel.set(0)
        evictedWebViews.set(0)
        duplicateWebViewsPrevented.set(0)
        rendererCrashes.set(0)
        uncaughtErrors.set(0)
        wsConnectionFailures.set(0)
        wsReconnects.set(0)
        lastWsRttMs.set(0L)
        subresourceChecks.set(0L)
        subresourceBlocked.set(0L)
        subresourceTotalDurationNs.set(0L)
        lastCrashOrError = null
        recentEventsDeque.clear()
        refreshSnapshotInternal(sampleCpu = true)
    }

    fun formatDiagnosticReport(snapshot: BrowserDiagnosticsSnapshot = captureSnapshot()): String {
        return buildString {
            appendLine("NYXORA Browser Diagnostics Report")
            appendLine("Memory: ${snapshot.usedHeapMb} MB / ${snapshot.maxHeapMb} MB (${snapshot.memoryUsagePercent}% - ${snapshot.memoryPressureLevel.label})")
            appendLine("CPU Usage Estimate: ${snapshot.cpuUsagePercent}% (Process CPU Time: ${snapshot.processCpuTimeMs} ms)")
            appendLine("WebViews: ${snapshot.activeWebViewCount} active, ${snapshot.evictedWebViewCount} evicted, ${snapshot.duplicateWebViewPreventedCount} pooled reuses")
            appendLine("Tabs Suspended: ${snapshot.suspendedTabCount} (Trim Memory Events: ${snapshot.trimMemoryCount})")
            appendLine("Ad-Block Engine: ${snapshot.subresourceBlockedCount}/${snapshot.subresourceCheckCount} blocked (Avg ${snapshot.avgSubresourceCheckMicros} us/check)")
            appendLine("Stability: ${snapshot.rendererCrashCount} renderer crashes, ${snapshot.uncaughtErrorCount} recovered errors")
            appendLine("WebSocket Sync: ${snapshot.wsConnectionFailureCount} failures, ${snapshot.wsReconnectCount} reconnects, Last RTT ${snapshot.lastWsHeartbeatRttMs} ms")
            snapshot.lastCrashOrErrorSummary?.let {
                appendLine("Last Issue: $it")
            }
        }.trim()
    }

    private fun appendEvent(category: String, message: String) {
        recentEventsDeque.addFirst(
            DiagnosticEventEntry(
                timestampMillis = System.currentTimeMillis(),
                category = category,
                message = message
            )
        )
        while (recentEventsDeque.size > MAX_RECENT_EVENTS) {
            recentEventsDeque.pollLast()
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshSnapshotInternal(sampleCpu: Boolean): BrowserDiagnosticsSnapshot {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = max(runtime.maxMemory(), 1L)
        val usedBytes = max(0L, totalMemory - freeMemory)
        val usedMb = usedBytes / (1024L * 1024L)
        val maxMb = max(1L, maxMemory / (1024L * 1024L))
        val usagePercent = ((usedBytes.toDouble() / maxMemory.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)

        val trimLevel = lastTrimLevel.get()
        val pressureLevel = when {
            usagePercent >= 88 || trimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressureLevel.CRITICAL
            usagePercent >= 75 || trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressureLevel.HIGH
            usagePercent >= 60 || trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.NORMAL
        }

        val currentProcessCpuMs = runCatching { Process.getElapsedCpuTime() }.getOrDefault(0L)
        if (sampleCpu) {
            val nowWallMs = SystemClock.elapsedRealtime()
            val wallDelta = nowWallMs - lastCpuSampleWallMs
            val cpuDelta = currentProcessCpuMs - lastCpuSampleProcessMs
            if (wallDelta >= 50L && cpuDelta >= 0L) {
                val cores = max(1, runtime.availableProcessors())
                val rawPercent = (cpuDelta.toDouble() / (wallDelta.toDouble() * cores.toDouble())) * 100.0
                lastComputedCpuPercent = ((rawPercent * 10.0).roundToInt() / 10.0).coerceIn(0.0, 100.0)
                lastCpuSampleWallMs = nowWallMs
                lastCpuSampleProcessMs = currentProcessCpuMs
            }
        }

        val checks = subresourceChecks.get()
        val avgMicros = if (checks > 0L) {
            val micros = (subresourceTotalDurationNs.get().toDouble() / checks.toDouble()) / 1_000.0
            (micros * 100.0).roundToInt() / 100.0
        } else {
            0.0
        }

        val snapshot = BrowserDiagnosticsSnapshot(
            usedHeapMb = usedMb,
            maxHeapMb = maxMb,
            memoryUsagePercent = usagePercent,
            memoryPressureLevel = pressureLevel,
            trimMemoryCount = trimMemoryCounter.get(),
            lastTrimMemoryLevel = trimLevel,
            processCpuTimeMs = currentProcessCpuMs,
            cpuUsagePercent = lastComputedCpuPercent,
            activeWebViewCount = activeWebViews.get(),
            evictedWebViewCount = evictedWebViews.get(),
            duplicateWebViewPreventedCount = duplicateWebViewsPrevented.get(),
            suspendedTabCount = suspendedTabs.get(),
            rendererCrashCount = rendererCrashes.get(),
            uncaughtErrorCount = uncaughtErrors.get(),
            lastCrashOrErrorSummary = lastCrashOrError,
            wsConnectionFailureCount = wsConnectionFailures.get(),
            wsReconnectCount = wsReconnects.get(),
            lastWsHeartbeatRttMs = lastWsRttMs.get(),
            subresourceCheckCount = checks,
            subresourceBlockedCount = subresourceBlocked.get(),
            avgSubresourceCheckMicros = avgMicros,
            recentEvents = recentEventsDeque.toList(),
            capturedAtMillis = System.currentTimeMillis()
        )
        _snapshotFlow.value = snapshot
        return snapshot
    }
}

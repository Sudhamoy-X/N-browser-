package com.example.domain.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.example.data.local.SearchEngine
import com.example.data.model.BookmarkItem
import com.example.data.model.HistoryItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

enum class SuggestionType {
    DIRECT_URL,
    SEARCH_HISTORY,
    BOOKMARK,
    HISTORY,
    REMOTE_SUGGESTION
}

data class SearchSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val queryOrUrl: String,
    val type: SuggestionType,
    val providerLabel: String = "",
    val isDirectNavigation: Boolean = false
)

object SmartSearchSuggestionEngine {

    const val DEFAULT_DEBOUNCE_MS = 180L
    private const val MAX_CACHE_ENTRIES = 64
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val MAX_TOTAL_SUGGESTIONS = 8
    private const val MAX_REMOTE_SUGGESTIONS = 6
    private const val MAX_STORED_SEARCH_QUERIES = 20

    private data class CachedSuggestionEntry(
        val suggestions: List<String>,
        val timestampMs: Long
    )

    private val suggestionCacheLock = Any()
    private val suggestionMemoryCache = object : LinkedHashMap<String, CachedSuggestionEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSuggestionEntry>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private val sensitiveParamRegex = Regex(
        pattern = """(?i)(?:^|[?&;#])(token|access_token|id_token|refresh_token|password|passwd|pwd|secret|api_key|apikey|auth|session|session_id|sessionid|private_key|client_secret)=""",
        option = RegexOption.IGNORE_CASE
    )

    private val embeddedCredentialsRegex = Regex(
        pattern = """^(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?[^/\s:@]+:[^/\s@]+@"""
    )

    /**
     * Builds the exact suggestion endpoint URL for the selected search engine.
     */
    fun buildSuggestionEndpointUrl(engine: SearchEngine, rawQuery: String): String {
        val trimmed = rawQuery.trim()
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        return String.format(engine.suggestUrlTemplate, encoded)
    }

    /**
     * Checks whether an input contains sensitive URL parameters, credentials, or internal/private addresses
     * that must never be sent to remote suggestion servers or stored in search history.
     */
    fun containsSensitiveParametersOrCredentials(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        if (lower.startsWith("nyxora://") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("about:")
        ) {
            return true
        }
        if (embeddedCredentialsRegex.containsMatchIn(trimmed)) {
            return true
        }
        if (sensitiveParamRegex.containsMatchIn(trimmed)) {
            return true
        }
        val hostCandidate = lower
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
        if (hostCandidate == "localhost" ||
            hostCandidate == "127.0.0.1" ||
            hostCandidate.startsWith("192.168.") ||
            hostCandidate.startsWith("10.")
        ) {
            return true
        }
        return false
    }

    /**
     * Determines whether remote network suggestions should be fetched for the current state.
     */
    fun shouldFetchRemoteSuggestions(
        query: String,
        suggestionsEnabled: Boolean,
        isPrivateMode: Boolean,
        privateModeSuggestionsEnabled: Boolean = false
    ): Boolean {
        if (!suggestionsEnabled) return false
        if (isPrivateMode && !privateModeSuggestionsEnabled) return false
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 160) return false
        if (containsSensitiveParametersOrCredentials(trimmed)) return false
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            // Full URLs with paths/queries should not be leaked to third-party search autocomplete APIs
            val afterScheme = trimmed.substringAfter("://")
            if (afterScheme.contains('/') || afterScheme.contains('?')) {
                return false
            }
        }
        return true
    }

    /**
     * Parses OpenSearch JSON `["query", ["s1", "s2"]]` and object-list `[{"phrase": "s1"}]` payloads.
     */
    fun parseRemoteSuggestionPayload(rawJson: String): List<String> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching {
            val results = mutableListOf<String>()
            if (trimmed.startsWith("[")) {
                val rootArray = JSONArray(trimmed)
                if (rootArray.length() >= 2 && rootArray.optJSONArray(1) != null) {
                    val suggestionsArray = rootArray.getJSONArray(1)
                    for (i in 0 until suggestionsArray.length()) {
                        val item = suggestionsArray.opt(i)
                        when (item) {
                            is String -> {
                                val clean = item.trim()
                                if (clean.isNotEmpty()) results.add(clean)
                            }
                            is JSONObject -> {
                                val phrase = item.optString("phrase", item.optString("q", "")).trim()
                                if (phrase.isNotEmpty()) results.add(phrase)
                            }
                        }
                    }
                } else {
                    for (i in 0 until rootArray.length()) {
                        val item = rootArray.opt(i)
                        when (item) {
                            is JSONObject -> {
                                val phrase = item.optString("phrase", item.optString("q", "")).trim()
                                if (phrase.isNotEmpty()) results.add(phrase)
                            }
                            is String -> {
                                if (i > 0 || rootArray.length() == 1) {
                                    val clean = item.trim()
                                    if (clean.isNotEmpty()) results.add(clean)
                                }
                            }
                        }
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val rootObj = JSONObject(trimmed)
                val arr = rootObj.optJSONArray("suggestions")
                    ?: rootObj.optJSONArray("results")
                    ?: rootObj.optJSONArray("AS")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.opt(i)
                        when (item) {
                            is String -> {
                                val clean = item.trim()
                                if (clean.isNotEmpty()) results.add(clean)
                            }
                            is JSONObject -> {
                                val phrase = item.optString("phrase", item.optString("query", item.optString("Txt", ""))).trim()
                                if (phrase.isNotEmpty()) results.add(phrase)
                            }
                        }
                    }
                }
            }
            results
        }.getOrElse {
            parseRemoteSuggestionPayloadFallback(trimmed)
        }
        return parsed.distinctBy { it.lowercase() }.take(MAX_REMOTE_SUGGESTIONS)
    }

    private fun parseRemoteSuggestionPayloadFallback(trimmed: String): List<String> {
        val results = mutableListOf<String>()
        val objectPhraseRegex = Regex(""""(?:phrase|query|Txt|q)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val objectMatches = objectPhraseRegex.findAll(trimmed).map { unescapeJsonString(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (objectMatches.isNotEmpty()) {
            return objectMatches
        }
        val nestedBracketIdx = trimmed.indexOf('[', startIndex = 1)
        val targetSlice = if (nestedBracketIdx >= 1) {
            val endNested = trimmed.indexOf(']', startIndex = nestedBracketIdx)
            if (endNested > nestedBracketIdx) trimmed.substring(nestedBracketIdx, endNested + 1) else trimmed
        } else {
            trimmed
        }
        val stringLiteralRegex = Regex(""""((?:\\.|[^"\\])*)"""")
        val allStrings = stringLiteralRegex.findAll(targetSlice)
            .map { unescapeJsonString(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        results.addAll(allStrings)
        return results
    }

    private fun unescapeJsonString(raw: String): String {
        return raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun escapeJsonString(raw: String): String {
        return buildString(raw.length + 4) {
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    fun getCachedRemoteSuggestions(
        engine: SearchEngine,
        rawQuery: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<String>? {
        val key = "${engine.name}:${rawQuery.trim().lowercase()}"
        synchronized(suggestionCacheLock) {
            val entry = suggestionMemoryCache[key] ?: return null
            if (nowMs - entry.timestampMs > CACHE_TTL_MS) {
                suggestionMemoryCache.remove(key)
                return null
            }
            return entry.suggestions
        }
    }

    fun putCachedRemoteSuggestions(
        engine: SearchEngine,
        rawQuery: String,
        suggestions: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = "${engine.name}:${rawQuery.trim().lowercase()}"
        synchronized(suggestionCacheLock) {
            suggestionMemoryCache[key] = CachedSuggestionEntry(
                suggestions = suggestions.distinctBy { it.lowercase() }.take(MAX_REMOTE_SUGGESTIONS),
                timestampMs = nowMs
            )
        }
    }

    fun clearMemoryCache() {
        synchronized(suggestionCacheLock) {
            suggestionMemoryCache.clear()
        }
    }

    /**
     * Default non-blocking network fetcher for live suggestion endpoints.
     */
    suspend fun defaultHttpFetcher(
        endpointUrl: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): String? = withContext(ioDispatcher) {
        coroutineContext.ensureActive()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 1800
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            }
            coroutineContext.ensureActive()
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                coroutineContext.ensureActive()
                body
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * Builds local suggestions immediately (Direct URL, Search History, Bookmarks, Browsing History)
     * without blocking on network IO.
     */
    fun buildLocalSuggestions(
        rawQuery: String,
        bookmarks: List<BookmarkItem>,
        history: List<HistoryItem>,
        recentSearchQueries: List<String>,
        isPrivateMode: Boolean,
        searchEngine: SearchEngine
    ): List<SearchSuggestionItem> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return emptyList()
        val qLower = trimmed.lowercase()
        val seenKeys = HashSet<String>()
        val results = mutableListOf<SearchSuggestionItem>()

        // 1. Direct URL suggestion if typed input looks like a domain or URL
        if (UrlAndSearchHandler.isLikelyDirectUrl(trimmed)) {
            val normalizedUrl = if (trimmed.contains("://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val dedupKey = normalizedUrl.lowercase().removeSuffix("/")
            if (seenKeys.add(dedupKey)) {
                results.add(
                    SearchSuggestionItem(
                        id = "direct_url_$dedupKey",
                        title = trimmed,
                        subtitle = "Open website • $normalizedUrl",
                        queryOrUrl = normalizedUrl,
                        type = SuggestionType.DIRECT_URL,
                        providerLabel = "Direct URL",
                        isDirectNavigation = true
                    )
                )
            }
        }

        // 2. Recent search history (only shown in normal mode so private mode stays isolated)
        if (!isPrivateMode) {
            for (savedQuery in recentSearchQueries) {
                val cleanQuery = savedQuery.trim()
                if (cleanQuery.isEmpty() || containsSensitiveParametersOrCredentials(cleanQuery)) continue
                if (cleanQuery.lowercase().contains(qLower)) {
                    val dedupKey = "query:${cleanQuery.lowercase()}"
                    if (seenKeys.add(dedupKey)) {
                        results.add(
                            SearchSuggestionItem(
                                id = "search_hist_${cleanQuery.lowercase()}",
                                title = cleanQuery,
                                subtitle = "Recent search • ${searchEngine.label}",
                                queryOrUrl = cleanQuery,
                                type = SuggestionType.SEARCH_HISTORY,
                                providerLabel = searchEngine.label,
                                isDirectNavigation = false
                            )
                        )
                        if (results.size >= 3) break
                    }
                }
            }
        }

        // 3. Matching Bookmarks
        for (bm in bookmarks) {
            if (containsSensitiveParametersOrCredentials(bm.url)) continue
            if (bm.title.lowercase().contains(qLower) || bm.url.lowercase().contains(qLower)) {
                val dedupKey = bm.url.trim().lowercase().removeSuffix("/")
                if (seenKeys.add(dedupKey)) {
                    results.add(
                        SearchSuggestionItem(
                            id = "bookmark_${bm.id}",
                            title = bm.title.ifBlank { bm.url },
                            subtitle = bm.url,
                            queryOrUrl = bm.url,
                            type = SuggestionType.BOOKMARK,
                            providerLabel = "Bookmark",
                            isDirectNavigation = true
                        )
                    )
                    if (results.size >= 5) break
                }
            }
        }

        // 4. Matching Browsing History (excluded in private mode to prevent history leakage)
        if (!isPrivateMode) {
            for (hist in history) {
                if (containsSensitiveParametersOrCredentials(hist.url)) continue
                if (hist.title.lowercase().contains(qLower) || hist.url.lowercase().contains(qLower)) {
                    val dedupKey = hist.url.trim().lowercase().removeSuffix("/")
                    if (seenKeys.add(dedupKey)) {
                        results.add(
                            SearchSuggestionItem(
                                id = "history_${hist.id}",
                                title = hist.title.ifBlank { hist.url },
                                subtitle = hist.url,
                                queryOrUrl = hist.url,
                                type = SuggestionType.HISTORY,
                                providerLabel = "History",
                                isDirectNavigation = true
                            )
                        )
                        if (results.size >= 6) break
                    }
                }
            }
        }

        return results.take(MAX_TOTAL_SUGGESTIONS)
    }

    /**
     * Combines local suggestions with live remote engine suggestions while deduplicating
     * case-insensitively and preserving order priority.
     */
    fun mergeLocalAndRemoteSuggestions(
        localSuggestions: List<SearchSuggestionItem>,
        remotePhrases: List<String>,
        engine: SearchEngine
    ): List<SearchSuggestionItem> {
        val merged = mutableListOf<SearchSuggestionItem>()
        val seen = HashSet<String>()

        for (item in localSuggestions) {
            val normTitle = item.title.trim().lowercase()
            val normTarget = item.queryOrUrl.trim().lowercase().removeSuffix("/")
            seen.add(normTitle)
            seen.add(normTarget)
            merged.add(item)
        }

        for (phrase in remotePhrases) {
            val clean = phrase.trim()
            if (clean.isEmpty() || containsSensitiveParametersOrCredentials(clean)) continue
            val key = clean.lowercase()
            if (seen.add(key)) {
                val isUrl = UrlAndSearchHandler.isLikelyDirectUrl(clean)
                merged.add(
                    SearchSuggestionItem(
                        id = "remote_${engine.name.lowercase()}_$key",
                        title = clean,
                        subtitle = if (isUrl) "Suggested site • ${engine.label}" else "${engine.label} suggestion",
                        queryOrUrl = clean,
                        type = if (isUrl) SuggestionType.DIRECT_URL else SuggestionType.REMOTE_SUGGESTION,
                        providerLabel = engine.label,
                        isDirectNavigation = isUrl
                    )
                )
                if (merged.size >= MAX_TOTAL_SUGGESTIONS) break
            }
        }

        return merged.take(MAX_TOTAL_SUGGESTIONS)
    }

    /**
     * Fetches remote suggestions using cache-first lookup + pluggable network fetcher.
     */
    suspend fun fetchRemoteSuggestions(
        rawQuery: String,
        engine: SearchEngine,
        suggestionsEnabled: Boolean,
        isPrivateMode: Boolean,
        privateModeSuggestionsEnabled: Boolean = false,
        fetcher: suspend (url: String) -> String? = { defaultHttpFetcher(it) }
    ): List<String> {
        if (!shouldFetchRemoteSuggestions(
                query = rawQuery,
                suggestionsEnabled = suggestionsEnabled,
                isPrivateMode = isPrivateMode,
                privateModeSuggestionsEnabled = privateModeSuggestionsEnabled
            )
        ) {
            return emptyList()
        }

        getCachedRemoteSuggestions(engine, rawQuery)?.let { cached ->
            return cached
        }

        val url = buildSuggestionEndpointUrl(engine, rawQuery)
        val rawBody = fetcher(url) ?: return emptyList()
        coroutineContext.ensureActive()
        val parsed = parseRemoteSuggestionPayload(rawBody)
        if (parsed.isNotEmpty()) {
            putCachedRemoteSuggestions(engine, rawQuery, parsed)
        }
        return parsed
    }

    /**
     * Parses persisted recent search queries JSON array.
     */
    fun parseRecentSearchQueries(rawJson: String): List<String> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        val parsed = runCatching {
            val arr = JSONArray(trimmed)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty() && !containsSensitiveParametersOrCredentials(item)) {
                    list.add(item)
                }
            }
            list
        }.getOrElse {
            val stringLiteralRegex = Regex(""""((?:\\.|[^"\\])*)"""")
            stringLiteralRegex.findAll(trimmed)
                .map { unescapeJsonString(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() && !containsSensitiveParametersOrCredentials(it) }
                .toList()
        }
        return parsed.distinctBy { it.lowercase() }.take(MAX_STORED_SEARCH_QUERIES)
    }

    /**
     * Records a user search query into the persisted JSON list ONLY when not in private mode
     * and when the query does not contain sensitive parameters or credentials.
     */
    fun recordSearchQuery(
        existingJson: String,
        rawQuery: String,
        isPrivateMode: Boolean
    ): String {
        if (isPrivateMode) return existingJson
        val clean = rawQuery.trim()
        if (clean.isEmpty() || clean.length > 140) return existingJson
        if (containsSensitiveParametersOrCredentials(clean)) return existingJson
        val existing = parseRecentSearchQueries(existingJson)
        val updated = (listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_STORED_SEARCH_QUERIES)
        return runCatching {
            val arr = JSONArray()
            updated.forEach { arr.put(it) }
            arr.toString()
        }.getOrElse {
            updated.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${escapeJsonString(it)}\"" }
        }
    }

    /**
     * Highlights matching occurrences of `query` inside `fullText` for suggestion rows.
     */
    fun buildHighlightedSuggestionAnnotatedString(
        fullText: String,
        query: String,
        highlightColor: Color
    ): AnnotatedString {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || fullText.isEmpty()) {
            return AnnotatedString(fullText)
        }
        return buildAnnotatedString {
            append(fullText)
            val lowerText = fullText.lowercase()
            val lowerQuery = cleanQuery.lowercase()
            var startIndex = 0
            while (startIndex < lowerText.length) {
                val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
                if (matchIndex == -1) break
                val endIndex = (matchIndex + lowerQuery.length).coerceAtMost(fullText.length)
                addStyle(
                    style = SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold
                    ),
                    start = matchIndex,
                    end = endIndex
                )
                startIndex = endIndex
            }
        }
    }
}

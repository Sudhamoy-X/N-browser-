package com.example.domain.browser

import org.json.JSONObject

data class ReaderArticle(
    val title: String,
    val byline: String,
    val siteName: String,
    val paragraphs: List<String>,
    val estimatedReadMinutes: Int
)

object ReaderModeExtractor {

    /**
     * JavaScript snippet that extracts readable article paragraphs, title, and metadata from the live DOM.
     */
    val EXTRACTION_JS = """
        (function() {
            var title = document.title || '';
            var h1 = document.querySelector('article h1, main h1, h1');
            if (h1 && h1.innerText && h1.innerText.trim().length > 4) {
                title = h1.innerText.trim();
            }
            var authorMeta = document.querySelector('meta[name="author"], meta[property="article:author"]');
            var byline = authorMeta ? (authorMeta.getAttribute('content') || '') : '';
            var siteMeta = document.querySelector('meta[property="og:site_name"]');
            var siteName = siteMeta ? (siteMeta.getAttribute('content') || location.hostname) : location.hostname;

            var root = document.querySelector('article') || document.querySelector('main') || document.body;
            var nodes = root ? root.querySelectorAll('p, h2, h3, blockquote, li') : [];
            var blocks = [];
            for (var i = 0; i < nodes.length; i++) {
                var text = (nodes[i].innerText || '').trim();
                if (text.length >= 28) {
                    blocks.push(text);
                }
            }
            if (blocks.length === 0 && document.body) {
                var rawLines = (document.body.innerText || '').split('\n');
                for (var j = 0; j < rawLines.length; j++) {
                    var line = rawLines[j].trim();
                    if (line.length >= 35) blocks.push(line);
                }
            }
            return JSON.stringify({
                title: title,
                byline: byline,
                siteName: siteName,
                paragraphs: blocks.slice(0, 120)
            });
        })();
    """.trimIndent()

    fun parseEvaluateJavascriptResult(rawJsResult: String?): ReaderArticle? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null") return null
        return try {
            // evaluateJavascript returns a JSON-encoded string literal when the JS returns a string
            val unquoted = if (rawJsResult.startsWith("\"") && rawJsResult.endsWith("\"")) {
                org.json.JSONTokener(rawJsResult).nextValue().toString()
            } else {
                rawJsResult
            }
            val obj = JSONObject(unquoted)
            val title = obj.optString("title", "Reader View").ifBlank { "Reader View" }
            val byline = obj.optString("byline", "")
            val siteName = obj.optString("siteName", "")
            val arr = obj.optJSONArray("paragraphs")
            val paragraphs = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim()
                    if (p.isNotEmpty() && !paragraphs.contains(p)) {
                        paragraphs.add(p)
                    }
                }
            }
            val wordCount = paragraphs.sumOf { it.split(Regex("\\s+")).size }
            val minutes = (wordCount / 200).coerceAtLeast(1)
            ReaderArticle(
                title = title,
                byline = byline,
                siteName = siteName,
                paragraphs = if (paragraphs.isEmpty()) listOf("No structured article body detected on this page.") else paragraphs,
                estimatedReadMinutes = minutes
            )
        } catch (e: Exception) {
            null
        }
    }
}

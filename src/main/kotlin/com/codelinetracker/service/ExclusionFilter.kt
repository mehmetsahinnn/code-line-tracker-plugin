package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.settings.TrackerSettings
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/** Drops files matching the user's ignore patterns (lock files, generated code, ...) from a diff. */
object ExclusionFilter {

    private val LOG = Logger.getInstance(ExclusionFilter::class.java)
    private val matchers = ConcurrentHashMap<String, List<PathMatcher>>()

    fun apply(diff: DiffResult): DiffResult {
        val patterns = TrackerSettings.getInstance().excludePatterns
        if (patterns.isEmpty() || diff.perFileStats.isEmpty()) return diff

        val kept = diff.perFileStats.filterKeys { !isExcluded(it, patterns) }
        if (kept.size == diff.perFileStats.size) return diff

        return DiffResult(
            kept.values.sumOf { it.addedLines },
            kept.values.sumOf { it.deletedLines },
            kept.size,
            kept,
            ignoredFiles = diff.perFileStats.size - kept.size
        )
    }

    fun isExcluded(relativePath: String, patterns: List<String>): Boolean {
        val path = try {
            Path.of(relativePath)
        } catch (_: InvalidPathException) {
            return false
        }
        return patterns.any { pattern -> matchersFor(pattern).any { it.matches(path) } }
    }

    // A "**/" prefix should also match files at the project root, which a Java glob does not do
    private fun matchersFor(pattern: String): List<PathMatcher> = matchers.getOrPut(pattern) {
        val globs = if (pattern.startsWith("**/")) listOf(pattern, pattern.removePrefix("**/")) else listOf(pattern)
        globs.mapNotNull { glob ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$glob")
            } catch (e: PatternSyntaxException) {
                LOG.warn("Invalid ignore pattern: $glob", e)
                null
            }
        }
    }
}

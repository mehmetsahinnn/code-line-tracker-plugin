package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.model.FileLineStat
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import java.io.File
import java.util.concurrent.TimeUnit

object GitDiffExecutor {

    private val LOG = Logger.getInstance(GitDiffExecutor::class.java)
    private const val TIMEOUT_SEC = 10L
    private const val NUM_STAT = "--numstat"
    private const val NO_RENAMES = "--no-renames"

    fun diffAll(basePath: String?): DiffResult {
        val dir = basePath?.let(::File) ?: return DiffResult()

        val headDiff = runGit(dir, "git", "diff", "HEAD", NUM_STAT, NO_RENAMES)
        if (headDiff != null) return parseNumstat(headDiff)

        val cached = runGit(dir, "git", "diff", "--cached", NUM_STAT, NO_RENAMES) ?: ""
        val working = runGit(dir, "git", "diff", NUM_STAT, NO_RENAMES) ?: ""
        return merge(parseNumstat(cached), parseNumstat(working))
    }

    /** Snapshot of unsaved project documents, keyed by path relative to the project base path. */
    fun collectUnsavedDocuments(project: Project): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        return ReadAction.compute<Map<String, String>, RuntimeException> {
            if (project.isDisposed) return@compute emptyMap()
            val fdm = FileDocumentManager.getInstance()
            val idx = ProjectFileIndex.getInstance(project)
            fdm.unsavedDocuments.mapNotNull { doc ->
                val file = fdm.getFile(doc) ?: return@mapNotNull null
                if (!idx.isInContent(file) || !file.path.startsWith("$basePath/")) return@mapNotNull null
                file.path.removePrefix("$basePath/") to doc.text
            }.toMap()
        }
    }

    /**
     * Diff of the working tree against HEAD, where files that have unsaved edits in the
     * editor are diffed in memory instead of being saved first. Saving documents while the
     * user types closes the code completion popup, so we must never do that here.
     *
     * [unsavedDocs] maps a path relative to [basePath] to the current document text.
     */
    fun diffAllWithUnsaved(basePath: String?, unsavedDocs: Map<String, String>): DiffResult {
        val onDisk = diffAll(basePath)
        if (unsavedDocs.isEmpty()) return onDisk
        val dir = basePath?.let(::File) ?: return onDisk

        // numstat paths are relative to the repository root, which may sit above basePath
        val prefix = runGit(dir, "git", "rev-parse", "--show-prefix")?.trim() ?: return onDisk

        val merged = onDisk.perFileStats.toMutableMap()
        for ((relPath, text) in unsavedDocs) {
            val headText = runGit(dir, "git", "show", "HEAD:./$relPath") ?: ""
            val (added, deleted) = countLineChanges(headText, text) ?: continue
            val key = prefix + relPath
            if (added == 0 && deleted == 0) merged.remove(key)
            else merged[key] = FileLineStat(key.substringAfterLast('/'), key, added, deleted)
        }

        return DiffResult(
            merged.values.sumOf { it.addedLines },
            merged.values.sumOf { it.deletedLines },
            merged.size,
            merged
        )
    }

    private fun countLineChanges(before: String, after: String): Pair<Int, Int>? {
        val beforeLines = if (before.isEmpty()) emptyArray() else before.lines().toTypedArray()
        val afterLines = if (after.isEmpty()) emptyArray() else after.lines().toTypedArray()
        return try {
            var change = Diff.buildChanges(beforeLines, afterLines)
            var added = 0
            var deleted = 0
            while (change != null) {
                added += change.inserted
                deleted += change.deleted
                change = change.link
            }
            Pair(added, deleted)
        } catch (e: FilesTooBigForDiffException) {
            LOG.debug(e)
            null
        }
    }

    fun diffCached(basePath: String?): DiffResult {
        val dir = basePath?.let(::File) ?: return DiffResult()
        val output = runGit(dir, "git", "diff", "--cached", NUM_STAT, NO_RENAMES) ?: return DiffResult()
        return parseNumstat(output)
    }

    private fun runGit(dir: File, vararg command: String): String? {
        if (!dir.isDirectory) return null
        return try {
            val process = ProcessBuilder(*command).directory(dir).start()

            val stderr = StringBuilder()
            val errThread = Thread({
                try {
                    process.errorStream.bufferedReader().use { r ->
                        r.lineSequence().forEach { stderr.appendLine(it) }
                    }
                } catch (e: Exception) {
                    LOG.debug("stderr reader interrupted", e)
                }
            }, "CLT-git-stderr").apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                errThread.join(500)
                LOG.debug("git timed out: ${command.joinToString(" ")}")
                return null
            }
            errThread.join(500)

            if (process.exitValue() != 0) {
                LOG.debug("git failed (exit=${process.exitValue()}): ${command.joinToString(" ")} :: $stderr")
                null
            } else stdout
        } catch (e: Exception) {
            LOG.debug("git error: ${command.joinToString(" ")}", e)
            null
        }
    }

    private fun merge(a: DiffResult, b: DiffResult): DiffResult {
        val merged = a.perFileStats.toMutableMap()
        for ((k, v) in b.perFileStats) {
            merged[k] = merged[k]?.let { existing ->
                FileLineStat(
                    existing.fileName,
                    existing.filePath,
                    existing.addedLines + v.addedLines,
                    existing.deletedLines + v.deletedLines
                )
            } ?: v
        }
        return DiffResult(
            a.addedLines + b.addedLines,
            a.deletedLines + b.deletedLines,
            merged.size,
            merged
        )
    }

    private fun parseNumstat(output: String): DiffResult {
        val perFile = mutableMapOf<String, FileLineStat>()
        var totalAdded = 0
        var totalDeleted = 0

        for (line in output.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue

            val added = parts[0].trim().toIntOrNull() ?: continue
            val deleted = parts[1].trim().toIntOrNull() ?: continue
            val filePath = parts[2].trim()
            val fileName = filePath.substringAfterLast('/')

            totalAdded += added
            totalDeleted += deleted
            perFile[filePath] = FileLineStat(fileName, filePath, added, deleted)
        }

        return DiffResult(totalAdded, totalDeleted, perFile.size, perFile)
    }
}

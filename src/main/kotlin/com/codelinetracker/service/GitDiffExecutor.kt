package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.model.FileLineStat
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

object GitDiffExecutor {

    private val LOG = Logger.getInstance(GitDiffExecutor::class.java)
    private const val TIMEOUT_SEC = 10L

    fun diffAll(basePath: String?): DiffResult {
        val dir = basePath?.let(::File) ?: return DiffResult()

        val headDiff = runGit(dir, "git", "diff", "HEAD", "--numstat", "--no-renames")
        if (headDiff != null) return parseNumstat(headDiff)

        val cached = runGit(dir, "git", "diff", "--cached", "--numstat", "--no-renames") ?: ""
        val working = runGit(dir, "git", "diff", "--numstat", "--no-renames") ?: ""
        return merge(parseNumstat(cached), parseNumstat(working))
    }

    fun diffCached(basePath: String?): DiffResult {
        val dir = basePath?.let(::File) ?: return DiffResult()
        val output = runGit(dir, "git", "diff", "--cached", "--numstat", "--no-renames") ?: return DiffResult()
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
                } catch (_: Exception) {}
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

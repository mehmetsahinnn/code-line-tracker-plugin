package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.model.FileLineStat
import java.io.File
import java.util.concurrent.TimeUnit

object GitDiffExecutor {

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
        return try {
            val process = ProcessBuilder(*command)
                .directory(dir)
                .start()

            Thread { process.errorStream.bufferedReader().readText() }.start()

            val stdout = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }

            if (process.exitValue() != 0) null else stdout
        } catch (_: Exception) {
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

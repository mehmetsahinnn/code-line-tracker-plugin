package com.codelinetracker.service

import com.codelinetracker.model.FileLineStat

/**
 * Picks which changed files go into the next commit when a change is bigger than the line limit.
 *
 * Related files are never split across commits: a class and its test (`UserService.kt` +
 * `UserServiceTest.kt`), or a component and its companions (`Button.tsx` + `Button.module.css` +
 * `Button.test.tsx`) stay together. Code-level dependencies between unrelated files cannot be
 * detected, so the result is a suggestion the user should review.
 */
object CommitSplitter {

    class Plan(
        /** Paths to include in the next commit */
        val keep: Set<String>,
        /** Groups of more than one file that were kept together */
        val keptTogether: List<List<FileLineStat>>,
        /** Groups that exceed the limit on their own and have to be committed separately */
        val tooLarge: List<List<FileLineStat>>,
    )

    // Stems shared by many unrelated files; grouping them by name alone would join unrelated code
    private val GENERIC_STEMS = setOf(
        "index", "main", "mod", "__init__", "init", "utils", "util", "types", "styles", "style",
        "constants", "config", "readme", "package", "build", "app", "module", "setup", "settings",
        "changelog", "license", "dockerfile", "makefile", "pom", "application",
    )

    private val CAMEL_TEST_SUFFIX = Regex("^(.+?)(IntegrationTests?|Tests?|Specs?|IT)$")
    private val SNAKE_TEST_SUFFIX = Regex("^(.+?)[_-](tests?|specs?)$", RegexOption.IGNORE_CASE)
    private val TEST_PREFIX = Regex("^tests?[_-](.+)$", RegexOption.IGNORE_CASE)

    fun plan(files: List<FileLineStat>, limit: Int): Plan {
        val groups = files.groupBy(::groupKey).values.sortedByDescending(::size)

        var budget = limit
        val keep = mutableSetOf<String>()
        val keptTogether = mutableListOf<List<FileLineStat>>()
        val tooLarge = mutableListOf<List<FileLineStat>>()

        // Largest groups first so each commit carries as much as possible
        for (group in groups) {
            val size = size(group)
            when {
                size > limit -> tooLarge += group
                size <= budget -> {
                    group.mapTo(keep) { it.filePath }
                    if (group.size > 1) keptTogether += group
                    budget -= size
                }
            }
        }
        return Plan(keep, keptTogether, tooLarge)
    }

    fun size(group: List<FileLineStat>): Int = group.sumOf { it.addedLines + it.deletedLines }

    internal fun groupKey(file: FileLineStat): String {
        val stem = relatedStem(file.fileName)
        if (stem in GENERIC_STEMS) return "${file.filePath.substringBeforeLast('/', "")}/$stem"
        return stem
    }

    /** `UserServiceTest.kt`, `user_service_test.go`, `test_user_service.py`, `UserService.module.css` → base name */
    internal fun relatedStem(fileName: String): String {
        val name = fileName.substringBefore('.').ifEmpty { fileName }
        val stem = CAMEL_TEST_SUFFIX.matchEntire(name)?.groupValues?.get(1)
            ?: SNAKE_TEST_SUFFIX.matchEntire(name)?.groupValues?.get(1)
            ?: TEST_PREFIX.matchEntire(name)?.groupValues?.get(1)
            ?: name
        return stem.lowercase()
    }
}

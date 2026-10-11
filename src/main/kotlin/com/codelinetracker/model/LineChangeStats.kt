package com.codelinetracker.model

data class FileLineStat(
    val fileName: String,
    val filePath: String = "",
    val addedLines: Int = 0,
    val deletedLines: Int = 0
)

data class DiffResult(
    val addedLines: Int = 0,
    val deletedLines: Int = 0,
    val filesChanged: Int = 0,
    val perFileStats: Map<String, FileLineStat> = emptyMap(),
    /** Changed files left out of the counts because they match an ignore pattern */
    val ignoredFiles: Int = 0
)

/** Changes on [branch] compared with where it split off [base] */
class BranchDiff(
    val branch: String,
    val base: String,
    val diff: DiffResult
)

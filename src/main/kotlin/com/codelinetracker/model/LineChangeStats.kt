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
    val perFileStats: Map<String, FileLineStat> = emptyMap()
)

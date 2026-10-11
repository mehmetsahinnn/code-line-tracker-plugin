package com.codelinetracker.vcs

import com.intellij.openapi.vcs.changes.Change

/** Path of a change relative to [basePath], matching the keys of [com.codelinetracker.model.DiffResult.perFileStats]. */
fun relativePath(change: Change, basePath: String): String? {
    val path = change.virtualFile?.path
        ?: change.afterRevision?.file?.path
        ?: change.beforeRevision?.file?.path
        ?: return null
    return path.removePrefix("$basePath/")
}

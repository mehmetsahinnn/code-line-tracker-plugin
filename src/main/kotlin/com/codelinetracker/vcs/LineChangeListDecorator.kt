package com.codelinetracker.vcs

import com.codelinetracker.service.CommitTrackerService
import com.codelinetracker.service.SessionTrackerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListDecorator
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color

class LineChangeListDecorator(private val project: Project) : ChangeListDecorator {

    override fun decorateChangeList(
        changeList: LocalChangeList,
        cellRenderer: ColoredTreeCellRenderer,
        selected: Boolean,
        expanded: Boolean,
        hasFocus: Boolean
    ) {
        val commitService = CommitTrackerService.getInstance(project)
        val diff = if (commitService.isCommitModeActive) {
            commitService.currentDiff
        } else {
            SessionTrackerService.getInstance(project).currentDiff
        }
        if (diff.perFileStats.isEmpty()) return

        val basePath = project.basePath ?: return
        val listChanges = changeList.changes
        if (listChanges.isEmpty()) return

        val listPaths = listChanges.mapNotNull { change ->
            val vf = change.virtualFile
            if (vf != null) return@mapNotNull vf.path.removePrefix("$basePath/")
            val after = change.afterRevision?.file?.path
            if (after != null) return@mapNotNull after.removePrefix("$basePath/")
            change.beforeRevision?.file?.path?.removePrefix("$basePath/")
        }.toSet()

        var added = 0
        var deleted = 0
        for ((path, stat) in diff.perFileStats) {
            if (listPaths.contains(path)) {
                added += stat.addedLines
                deleted += stat.deletedLines
            }
        }

        if (added == 0 && deleted == 0) return

        val total = added + deleted

        cellRenderer.append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        cellRenderer.append("$total || ", SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            JBColor(Color(0x6C707E), Color(0xA9B7C6))
        ))

        cellRenderer.append("+$added", SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            JBColor(Color(0x59A869), Color(0x6A8759))
        ))

        cellRenderer.append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

        cellRenderer.append("-$deleted", SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            JBColor(Color(0xDB5860), Color(0xCC7832))
        ))
    }
}

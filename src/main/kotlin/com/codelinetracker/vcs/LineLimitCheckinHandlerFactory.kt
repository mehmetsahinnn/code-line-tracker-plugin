package com.codelinetracker.vcs

import com.codelinetracker.model.DiffResult
import com.codelinetracker.service.GitDiffExecutor
import com.codelinetracker.settings.TrackerSettings
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandler.ReturnResult
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

/** Asks for confirmation before committing more changed lines than the configured limit. */
class LineLimitCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
        LineLimitCheckinHandler(panel)
}

private class LineLimitCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val settings = TrackerSettings.getInstance()
        if (!settings.warnOnCommit) return ReturnResult.COMMIT

        val project = panel.project
        val basePath = project.basePath ?: return ReturnResult.COMMIT
        val changes = panel.selectedChanges
        if (changes.isEmpty()) return ReturnResult.COMMIT

        val diff = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<DiffResult, RuntimeException> { GitDiffExecutor.diffProject(project) },
            "Counting Changed Lines",
            false,
            project
        )
        val total = committedLines(diff, changes, basePath)
        if (total <= settings.lineLimit) return ReturnResult.COMMIT

        return if (confirm(project, total, settings.lineLimit)) ReturnResult.COMMIT else ReturnResult.CANCEL
    }

    private fun committedLines(diff: DiffResult, changes: Collection<Change>, basePath: String): Int {
        val paths = changes.mapNotNull { relativePath(it, basePath) }.toSet()
        return diff.perFileStats.filterKeys { it in paths }.values.sumOf { it.addedLines + it.deletedLines }
    }

    private fun confirm(project: Project, total: Int, limit: Int): Boolean {
        val answer = Messages.showYesNoDialog(
            project,
            "This commit changes $total lines, which is ${total - limit} over your limit of $limit.\n\n" +
                "Tip: open the Code Line Tracker popup in the status bar and use \"Fit to limit\" " +
                "to split it into smaller commits.",
            "Commit Exceeds Line Limit",
            "Commit Anyway",
            "Cancel",
            Messages.getWarningIcon()
        )
        return answer == Messages.YES
    }
}

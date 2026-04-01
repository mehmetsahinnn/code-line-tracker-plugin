package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.widget.LineStatsWidget
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener

@Service(Service.Level.PROJECT)
class CommitTrackerService(private val project: Project) : Disposable {

    @Volatile
    var currentDiff: DiffResult = DiffResult()
        private set

    var isCommitModeActive = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun init() {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == "Commit") {
                        activateCommitMode()
                    }
                }

                @Suppress("DEPRECATION")
                override fun stateChanged() {
                    checkCommitWindowState()
                }
            }
        )

        project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    if (isCommitModeActive) {
                        refresh()
                    }
                    cleanupEmptyTempList()
                }
            }
        )

        ApplicationManager.getApplication().invokeLater {
            checkCommitWindowState()
        }
    }

    private fun checkCommitWindowState() {
        val commitWindow = ToolWindowManager.getInstance(project).getToolWindow("Commit")
        val isVisible = commitWindow?.isVisible == true
        if (isVisible && !isCommitModeActive) {
            activateCommitMode()
        } else if (!isVisible && isCommitModeActive) {
            deactivateCommitMode()
        }
    }

    private fun activateCommitMode() {
        isCommitModeActive = true
        refresh()
    }

    private fun deactivateCommitMode() {
        isCommitModeActive = false
        restoreExcludedFiles()
        currentDiff = DiffResult()
        notifyListeners()
    }

    private fun restoreExcludedFiles() {
        ApplicationManager.getApplication().invokeLater {
            findWidget()?.restoreTempChangelist()
        }
    }

    private fun cleanupEmptyTempList() {
        ApplicationManager.getApplication().invokeLater {
            findWidget()?.cleanupIfEmpty()
        }
    }

    private fun findWidget(): LineStatsWidget? {
        return try {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return null
            statusBar.getWidget(LineStatsWidget.WIDGET_ID) as? LineStatsWidget
        } catch (_: Exception) { null }
    }

    fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()

            ApplicationManager.getApplication().executeOnPooledThread {
                val result = GitDiffExecutor.diffAll(project.basePath)
                currentDiff = result
                ApplicationManager.getApplication().invokeLater {
                    notifyListeners()
                }
            }
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): CommitTrackerService {
            return project.getService(CommitTrackerService::class.java)
        }
    }
}

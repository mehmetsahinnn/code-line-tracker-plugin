package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.codelinetracker.widget.LineStatsWidget
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val REFRESH_THROTTLE_MS = 500L

@Service(Service.Level.PROJECT)
class CommitTrackerService(private val project: Project) : Disposable {

    @Volatile
    var currentDiff: DiffResult = DiffResult()
        private set

    @Volatile
    var isCommitModeActive = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val scheduler: ScheduledExecutorService =
        AppExecutorUtil.createBoundedScheduledExecutorService("CodeLineTracker-Commit-${project.name}", 1)
    @Volatile
    private var pendingRefresh: ScheduledFuture<*>? = null

    fun init() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == "Commit") activateCommitMode()
                }

                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    checkCommitWindowState()
                }
            }
        )
        connection.subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    if (isCommitModeActive) scheduleRefresh()
                    cleanupEmptyTempList()
                }
            }
        )

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) checkCommitWindowState()
        }
    }

    private fun checkCommitWindowState() {
        if (project.isDisposed) return
        val commitWindow = ToolWindowManager.getInstance(project).getToolWindow("Commit")
        val isVisible = commitWindow?.isVisible == true
        if (isVisible && !isCommitModeActive) activateCommitMode()
        else if (!isVisible && isCommitModeActive) deactivateCommitMode()
    }

    private fun activateCommitMode() {
        isCommitModeActive = true
        scheduleRefresh()
    }

    private fun deactivateCommitMode() {
        isCommitModeActive = false
        restoreExcludedFiles()
        currentDiff = DiffResult()
        notifyListeners()
    }

    private fun restoreExcludedFiles() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) findWidget()?.restoreTempChangelist()
        }
    }

    private fun cleanupEmptyTempList() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) findWidget()?.cleanupIfEmpty()
        }
    }

    private fun findWidget(): LineStatsWidget? {
        return try {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return null
            statusBar.getWidget(LineStatsWidget.WIDGET_ID) as? LineStatsWidget
        } catch (_: Exception) { null }
    }

    fun refresh() = scheduleRefresh()

    private fun scheduleRefresh() {
        if (project.isDisposed) return
        pendingRefresh?.cancel(false)
        pendingRefresh = try {
            scheduler.schedule({ doRefresh() }, REFRESH_THROTTLE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null }
    }

    private fun doRefresh() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fdm = FileDocumentManager.getInstance()
            fdm.unsavedDocuments.forEach { fdm.saveDocument(it) }

            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val result = GitDiffExecutor.diffAll(project.basePath)
                currentDiff = result
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) notifyListeners()
                }
            }
        }
    }

    fun addChangeListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeChangeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun notifyListeners() {
        listeners.forEach {
            try { it() } catch (e: Exception) { LOG.warn(e) }
        }
    }

    override fun dispose() {
        pendingRefresh?.cancel(false)
        scheduler.shutdownNow()
        listeners.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(CommitTrackerService::class.java)
        fun getInstance(project: Project): CommitTrackerService {
            return project.getService(CommitTrackerService::class.java)
        }
    }
}

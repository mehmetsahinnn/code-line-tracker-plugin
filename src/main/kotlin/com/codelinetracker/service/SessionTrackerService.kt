package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicLong

private const val DEBOUNCE_MS = 1500L
private const val PERIODIC_REFRESH_MS = 30_000L

@Service(Service.Level.PROJECT)
class SessionTrackerService(private val project: Project) : Disposable {

    @Volatile
    var currentDiff: DiffResult = DiffResult()
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private var periodicTimer: Timer? = null
    private val debounceToken = AtomicLong(0)

    fun init() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    scheduleRefresh()
                }
            }, this
        )

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    scheduleRefresh()
                }
            }
        )

        periodicTimer = Timer("CodeLineTracker-Periodic", true).apply {
            schedule(object : TimerTask() {
                override fun run() { refresh() }
            }, 0L, PERIODIC_REFRESH_MS)
        }
    }

    private fun scheduleRefresh() {
        val token = debounceToken.incrementAndGet()
        Timer("CodeLineTracker-Debounce", true).schedule(object : TimerTask() {
            override fun run() {
                if (debounceToken.get() == token) {
                    refresh()
                }
            }
        }, DEBOUNCE_MS)
    }

    fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()

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
        periodicTimer?.cancel()
        periodicTimer = null
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): SessionTrackerService {
            return project.getService(SessionTrackerService::class.java)
        }
    }
}

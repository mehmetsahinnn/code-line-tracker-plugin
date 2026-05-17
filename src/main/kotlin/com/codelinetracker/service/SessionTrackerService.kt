package com.codelinetracker.service

import com.codelinetracker.model.DiffResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val DEBOUNCE_MS = 1500L
private const val PERIODIC_REFRESH_MS = 30_000L

@Service(Service.Level.PROJECT)
class SessionTrackerService(private val project: Project) : Disposable {

    @Volatile
    var currentDiff: DiffResult = DiffResult()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val scheduler: ScheduledExecutorService =
        AppExecutorUtil.createBoundedScheduledExecutorService("CodeLineTracker-${project.name}", 1)
    private val pendingDebounce = AtomicReference<ScheduledFuture<*>?>(null)
    private var periodicHandle: ScheduledFuture<*>? = null

    fun init() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed) return
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!ProjectFileIndex.getInstance(project).isInContent(file)) return
                    scheduleRefresh()
                }
            }, this
        )

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (project.isDisposed) return
                    val idx = ProjectFileIndex.getInstance(project)
                    val touches = events.any { ev ->
                        val f = ev.file ?: return@any false
                        idx.isInContent(f)
                    }
                    if (touches) scheduleRefresh()
                }
            }
        )

        periodicHandle = scheduler.scheduleWithFixedDelay(
            { refresh() }, 0L, PERIODIC_REFRESH_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun scheduleRefresh() {
        if (project.isDisposed) return
        val next = try {
            scheduler.schedule({ refresh() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            return
        }
        pendingDebounce.getAndSet(next)?.cancel(false)
    }

    fun refresh() {
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

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach {
            try { it() } catch (e: Exception) { LOG.warn(e) }
        }
    }

    override fun dispose() {
        periodicHandle?.cancel(false)
        pendingDebounce.getAndSet(null)?.cancel(false)
        scheduler.shutdownNow()
        listeners.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(SessionTrackerService::class.java)
        fun getInstance(project: Project): SessionTrackerService {
            return project.getService(SessionTrackerService::class.java)
        }
    }
}

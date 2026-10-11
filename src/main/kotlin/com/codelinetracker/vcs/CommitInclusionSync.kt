package com.codelinetracker.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesViewWorkflowManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import java.lang.reflect.InvocationTargetException

/**
 * Mirrors the popup's file selection onto the checkboxes of the Commit tool window.
 *
 * IntelliJ has no public API for this. The commit workflow handler that owns the checkboxes is a
 * Kotlin-internal class, so its `setCommitState` is called reflectively. Every call is guarded:
 * if the method is missing or behaves differently, the checkboxes are left alone and the
 * changelist-based exclusion still applies.
 */
object CommitInclusionSync {

    private val LOG = Logger.getInstance(CommitInclusionSync::class.java)

    /**
     * Checks exactly [selected] among the [managed] changes shown in the popup. Changes the popup
     * does not show (ignored files, unversioned files) keep whatever state the user gave them.
     */
    fun apply(project: Project, managed: Collection<Change>, selected: Collection<Change>) {
        try {
            val handler: AbstractCommitWorkflowHandler<*, *> =
                ChangesViewWorkflowManager.getInstance(project).commitWorkflowHandler ?: return

            val ui = handler.ui
            val managedSet = managed.toSet()
            val untouched: List<Any> = ui.getIncludedChanges().filter { it !in managedSet } + ui.getIncludedUnversionedFiles()

            val setCommitState = handler.javaClass.getMethod(
                "setCommitState",
                LocalChangeList::class.java,
                Collection::class.java,
                Boolean::class.javaPrimitiveType
            )
            val defaultList = ChangeListManager.getInstance(project).defaultChangeList
            setCommitState.invoke(handler, defaultList, untouched + selected, true)
        } catch (e: ReflectiveOperationException) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            LOG.info("Commit checkbox sync is not supported in this IDE version", cause)
        } catch (e: LinkageError) {
            LOG.info("Commit checkbox sync is not supported in this IDE version", e)
        } catch (e: RuntimeException) {
            LOG.warn("Could not update commit checkboxes", e)
        }
    }
}

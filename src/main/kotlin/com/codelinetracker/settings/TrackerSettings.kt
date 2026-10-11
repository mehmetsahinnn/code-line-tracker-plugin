package com.codelinetracker.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.ui.JBColor
import java.awt.Color

const val DEFAULT_LINE_LIMIT = 500

val DEFAULT_EXCLUDE_PATTERNS = listOf(
    "**/package-lock.json",
    "**/yarn.lock",
    "**/pnpm-lock.yaml",
    "**/*.lock",
    "**/*.min.js",
    "**/*.min.css",
    "**/*.map",
    "**/generated/**",
    "**/__snapshots__/**",
).joinToString("\n")

/** Share of the limit at which the counter turns from green to yellow. */
private const val NEAR_LIMIT_RATIO = 0.7

@Service(Service.Level.APP)
@State(name = "CodeLineTrackerSettings", storages = [Storage("codeLineTracker.xml")])
class TrackerSettings : PersistentStateComponent<TrackerSettings.State> {

    class State {
        var limitEnabled: Boolean = true
        var lineLimit: Int = DEFAULT_LINE_LIMIT
        var warnOnCommit: Boolean = true
        var excludePatterns: String = DEFAULT_EXCLUDE_PATTERNS
        /** Branch the "Branch" view compares against; empty means detect origin/main, main, master... */
        var baseBranch: String = ""
        /** Line limit for a whole branch / pull request; 0 turns it off */
        var branchLimit: Int = 0
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val limitEnabled: Boolean get() = myState.limitEnabled && myState.lineLimit > 0
    val lineLimit: Int get() = myState.lineLimit
    val warnOnCommit: Boolean get() = limitEnabled && myState.warnOnCommit

    val excludePatterns: List<String>
        get() = myState.excludePatterns.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

    val baseBranch: String? get() = myState.baseBranch.trim().ifEmpty { null }
    val branchLimit: Int get() = myState.branchLimit

    /** Level of a commit-sized change against the line limit */
    fun levelOf(totalLines: Int): LimitLevel =
        if (limitEnabled) levelOf(totalLines, lineLimit) else LimitLevel.NONE

    /** Level of a whole branch against the branch limit */
    fun branchLevelOf(totalLines: Int): LimitLevel =
        if (branchLimit > 0) levelOf(totalLines, branchLimit) else LimitLevel.NONE

    private fun levelOf(totalLines: Int, limit: Int): LimitLevel = when {
        totalLines > limit -> LimitLevel.OVER
        totalLines >= limit * NEAR_LIMIT_RATIO -> LimitLevel.NEAR
        else -> LimitLevel.OK
    }

    companion object {
        fun getInstance(): TrackerSettings =
            ApplicationManager.getApplication().getService(TrackerSettings::class.java)
    }
}

enum class LimitLevel(val color: Color?) {
    NONE(null),
    OK(JBColor(Color(0x59A869), Color(0x6A8759))),
    NEAR(JBColor(Color(0xD9A343), Color(0xE5C07B))),
    OVER(JBColor(Color(0xDB5860), Color(0xF75464))),
}

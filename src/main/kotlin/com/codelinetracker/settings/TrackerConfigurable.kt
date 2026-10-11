package com.codelinetracker.settings

import com.codelinetracker.service.CommitTrackerService
import com.codelinetracker.service.SessionTrackerService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent

class TrackerConfigurable : Configurable {

    private val limitEnabled = JBCheckBox("Warn when changes exceed a line limit")
    private val lineLimit = JBIntSpinner(DEFAULT_LINE_LIMIT, 1, 1_000_000, 50)
    private val warnOnCommit = JBCheckBox("Ask for confirmation when committing more lines than the limit")
    private val excludePatterns = JBTextArea(8, 40)
    private val baseBranch = JBTextField(20)
    private val branchLimit = JBIntSpinner(0, 0, 1_000_000, 100)

    override fun getDisplayName(): String = "Code Line Tracker"

    override fun createComponent(): JComponent {
        limitEnabled.addActionListener { updateEnabled() }

        val patternsScroll = JBScrollPane(excludePatterns)
        patternsScroll.preferredSize = Dimension(400, 160)

        val resetPatterns = JButton("Reset to Defaults")
        resetPatterns.addActionListener { excludePatterns.text = DEFAULT_EXCLUDE_PATTERNS }

        val patternsHint = JBLabel(
            "One glob pattern per line, relative to the project root. Lines starting with # are ignored."
        )
        patternsHint.foreground = UIUtil.getContextHelpForeground()

        baseBranch.emptyText.text = "Detect automatically (origin/main, main, master...)"
        val branchHint = JBLabel("The Branch view in the popup compares your branch with this one. Set the limit to 0 to turn it off.")
        branchHint.foreground = UIUtil.getContextHelpForeground()

        return FormBuilder.createFormBuilder()
            .addComponent(limitEnabled)
            .addLabeledComponent("Line limit (added + deleted):", lineLimit)
            .addComponent(warnOnCommit)
            .addSeparator()
            .addLabeledComponent("Base branch for the Branch view:", baseBranch)
            .addLabeledComponent("Branch / pull request line limit:", branchLimit)
            .addComponent(branchHint)
            .addSeparator()
            .addLabeledComponentFillVertically("Files to ignore when counting:", patternsScroll)
            .addComponent(patternsHint)
            .addComponent(resetPatterns)
            .panel
    }

    private fun updateEnabled() {
        lineLimit.isEnabled = limitEnabled.isSelected
        warnOnCommit.isEnabled = limitEnabled.isSelected
    }

    override fun isModified(): Boolean {
        val s = TrackerSettings.getInstance().getState()
        return limitEnabled.isSelected != s.limitEnabled ||
            lineLimit.number != s.lineLimit ||
            warnOnCommit.isSelected != s.warnOnCommit ||
            excludePatterns.text != s.excludePatterns ||
            baseBranch.text.trim() != s.baseBranch ||
            branchLimit.number != s.branchLimit
    }

    override fun apply() {
        val s = TrackerSettings.getInstance().getState()
        s.limitEnabled = limitEnabled.isSelected
        s.lineLimit = lineLimit.number
        s.warnOnCommit = warnOnCommit.isSelected
        s.excludePatterns = excludePatterns.text
        s.baseBranch = baseBranch.text.trim()
        s.branchLimit = branchLimit.number

        // Recount so the new limit and ignore patterns show up right away
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            SessionTrackerService.getInstance(project).refresh()
            val commitService = CommitTrackerService.getInstance(project)
            if (commitService.isCommitModeActive) commitService.refresh()
        }
    }

    override fun reset() {
        val s = TrackerSettings.getInstance().getState()
        limitEnabled.isSelected = s.limitEnabled
        lineLimit.number = s.lineLimit
        warnOnCommit.isSelected = s.warnOnCommit
        excludePatterns.text = s.excludePatterns
        baseBranch.text = s.baseBranch
        branchLimit.number = s.branchLimit
        updateEnabled()
    }
}

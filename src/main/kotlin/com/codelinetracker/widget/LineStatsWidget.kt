package com.codelinetracker.widget

import com.codelinetracker.model.BranchDiff
import com.codelinetracker.model.DiffResult
import com.codelinetracker.model.FileLineStat
import com.codelinetracker.service.CommitSplitter
import com.codelinetracker.service.CommitTrackerService
import com.codelinetracker.service.GitDiffExecutor
import com.codelinetracker.service.SessionTrackerService
import com.codelinetracker.settings.LimitLevel
import com.codelinetracker.settings.TrackerSettings
import com.codelinetracker.vcs.CommitInclusionSync
import com.codelinetracker.vcs.relativePath
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicButtonUI

class LineStatsWidget(project: Project) : EditorBasedWidget(project), CustomStatusBarWidget {

    companion object {
        const val WIDGET_ID = "CodeLineTrackerWidget"
        const val TEMP_LIST_NAME = "[CLT] Temporarily Excluded from Commit"
        private const val POPUP_WIDTH = 420
        private const val PADDING = 16
        private const val CONTENT_WIDTH = POPUP_WIDTH - 2 * PADDING
        private const val ROW_HEIGHT = 28
        private const val NUMBER_WIDTH = 44
        private val LOG = Logger.getInstance(LineStatsWidget::class.java)
    }

    private enum class Tab(val title: String) { CHANGES("Changes"), BRANCH("Branch") }

    private val green = JBColor(Color(0x3E8A4E), Color(0x6AAB73))
    private val red = JBColor(Color(0xC7333F), Color(0xF75464))
    private val link = JBColor(Color(0x2F6FDB), Color(0x548AF7))
    private val muted = JBColor(Color(0x6C707E), Color(0x9DA0A8))
    private val tabTrack = JBColor(Color(0xEBECF0), Color(0x1E1F22))
    private val tabActive = JBColor(Color(0xFFFFFF), Color(0x43454A))

    private val textPanel = TextPanel.WithIconAndArrows()
    private val sessionListener: () -> Unit = { onDataChanged() }
    private val commitListener: () -> Unit = { onDataChanged() }

    private var activePopup: JBPopup? = null
    /** Holds the current tab's content so switching tabs swaps it inside the open popup */
    private var popupRoot: JPanel? = null
    private var currentTab = Tab.CHANGES
    /** Set by the refresh button: redraw the Changes tab once the recount arrives */
    private var redrawOnNextUpdate = false

    init {
        textPanel.text = "+0 −0"
        textPanel.toolTipText = "Code Line Tracker"
        textPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        textPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                togglePopup()
            }
        })
    }

    override fun ID(): String = WIDGET_ID

    override fun getComponent(): JComponent = textPanel

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        SessionTrackerService.getInstance(project).addChangeListener(sessionListener)
        CommitTrackerService.getInstance(project).addChangeListener(commitListener)
        updateDisplay()
    }

    override fun dispose() {
        activePopup?.cancel()
        restoreTempChangelist()
        try {
            SessionTrackerService.getInstance(project).removeChangeListener(sessionListener)
            CommitTrackerService.getInstance(project).removeChangeListener(commitListener)
        } catch (e: Exception) {
            LOG.debug(e)
        }
        super<EditorBasedWidget>.dispose()
    }

    private fun getActiveDiff(): Pair<DiffResult, Boolean> {
        val commitService = CommitTrackerService.getInstance(project)
        return if (commitService.isCommitModeActive) {
            Pair(commitService.currentDiff, true)
        } else {
            Pair(SessionTrackerService.getInstance(project).currentDiff, false)
        }
    }

    private fun onDataChanged() {
        updateDisplay()
        if (redrawOnNextUpdate) {
            redrawOnNextUpdate = false
            SwingUtilities.invokeLater { if (currentTab == Tab.CHANGES) showTab(Tab.CHANGES) }
        }
    }

    private fun updateDisplay() {
        SwingUtilities.invokeLater {
            val (diff, isCommitMode) = getActiveDiff()
            val total = diff.addedLines + diff.deletedLines
            val settings = TrackerSettings.getInstance()
            val level = settings.levelOf(total)
            val counts = "+${diff.addedLines} −${diff.deletedLines}"

            // With a limit the widget shows progress against it; added/deleted move to the tooltip
            textPanel.text = if (level == LimitLevel.NONE) counts else "$total / ${settings.lineLimit}"
            textPanel.icon = level.color?.let { ColorIcon(JBUI.scale(8), it) }

            val mode = if (isCommitMode) "Commit" else "Session"
            val limitInfo = if (level == LimitLevel.NONE) "" else " · ${limitText(total, settings.lineLimit)}"
            textPanel.toolTipText = "Code Line Tracker ($mode) — ${diff.filesChanged} files · $counts$limitInfo"
            textPanel.revalidate()
            textPanel.repaint()
        }
    }

    /** Checks the popup's selected files in the Commit tool window and unchecks the others */
    private fun syncCommitCheckboxes(checks: List<Pair<JCheckBox, FileLineStat>>) {
        val basePath = project.basePath ?: return
        val selectedPaths = checks.filter { it.first.isSelected }.mapTo(HashSet()) { it.second.filePath }
        val managedPaths = checks.mapTo(HashSet()) { it.second.filePath }
        // Run after the changelist moves above have been applied
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val managed = ChangeListManager.getInstance(project).allChanges
                .filter { relativePath(it, basePath) in managedPaths }
            val selected = managed.filter { relativePath(it, basePath) in selectedPaths }
            CommitInclusionSync.apply(project, managed, selected)
        }
    }

    private fun limitText(total: Int, limit: Int): String =
        if (total > limit) "${total - limit} over" else "${limit - total} left"

    // --- Changelist helpers ---

    private fun isInExcludedList(filePath: String): Boolean {
        val clManager = ChangeListManager.getInstance(project)
        val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return false
        val basePath = project.basePath ?: return false
        return tempList.changes.any { change ->
            relativePath(change, basePath) == filePath
        }
    }

    private fun moveToExcludedList(filePath: String) {
        val clManager = ChangeListManager.getInstance(project)
        val basePath = project.basePath ?: return
        val defaultList = clManager.defaultChangeList

        val change = defaultList.changes.find { relativePath(it, basePath) == filePath } ?: return

        val tempList = clManager.findChangeList(TEMP_LIST_NAME)
            ?: clManager.addChangeList(TEMP_LIST_NAME, "Files temporarily excluded by Code Line Tracker. They will be restored automatically.")
        clManager.moveChangesTo(tempList, change)
    }

    private fun moveToDefaultList(filePath: String) {
        val clManager = ChangeListManager.getInstance(project)
        val basePath = project.basePath ?: return
        val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return
        val defaultList = clManager.defaultChangeList

        val change = tempList.changes.find { relativePath(it, basePath) == filePath } ?: return
        clManager.moveChangesTo(defaultList, change)

        ApplicationManager.getApplication().invokeLater {
            val current = clManager.findChangeList(TEMP_LIST_NAME) ?: return@invokeLater
            if (current.changes.isEmpty()) {
                clManager.removeChangeList(current)
            }
        }
    }

    // --- Popup lifecycle ---

    private fun togglePopup() {
        activePopup?.let {
            if (!it.isDisposed) { it.cancel(); return }
        }
        openPopup()
    }

    private fun openPopup() {
        val root = object : JPanel(BorderLayout()) {
            override fun getPreferredSize(): Dimension =
                super.getPreferredSize().also { it.width = JBUI.scale(POPUP_WIDTH) }
        }
        root.background = UIUtil.getPanelBackground()
        root.border = JBUI.Borders.empty(12, PADDING, PADDING, PADDING)
        popupRoot = root
        currentTab = Tab.CHANGES
        root.add(changesContent(), BorderLayout.CENTER)

        val popup = PopupFactoryImpl.getInstance()
            .createComponentPopupBuilder(root, null)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                if (activePopup === popup) {
                    activePopup = null
                    popupRoot = null
                }
            }
        })

        activePopup = popup
        // The widget lives at the bottom edge of the frame; open upwards so the popup
        // doesn't get pushed on top of the status bar.
        val popupHeight = popup.content.preferredSize.height
        popup.show(RelativePoint(textPanel, Point(0, -popupHeight - JBUI.scale(4))))
    }

    /** Replaces the open popup's content with [tab] without closing it */
    private fun showTab(tab: Tab) {
        if (popupRoot == null) return
        currentTab = tab
        when (tab) {
            Tab.CHANGES -> setPopupContent(changesContent())
            Tab.BRANCH -> {
                setPopupContent(branchContent(null, loading = true))
                val baseBranch = TrackerSettings.getInstance().baseBranch
                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = GitDiffExecutor.diffBranch(project, baseBranch)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed && currentTab == Tab.BRANCH) setPopupContent(branchContent(result, loading = false))
                    }
                }
            }
        }
    }

    private fun setPopupContent(content: JComponent) {
        val root = popupRoot ?: return
        root.removeAll()
        root.add(content, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
        repackAboveWidget()
    }

    /** Resizes the open popup to its content and keeps its bottom edge just above the widget. */
    private fun repackAboveWidget() {
        val popup = activePopup?.takeIf { !it.isDisposed && it.isVisible } ?: return
        val x = popup.locationOnScreen.x
        val widgetTop = textPanel.locationOnScreen.y
        val gap = JBUI.scale(4)

        // pack() caps the height at the room below the popup's current top edge, and the popup
        // sits right above the status bar. Move it up to where the new size needs it first.
        val root = popupRoot ?: return
        val border = popup.content.insets
        val wanted = root.preferredSize.height + border.top + border.bottom
        popup.setLocation(Point(x, widgetTop - wanted - gap))
        popup.pack(true, true)
        popup.setLocation(Point(x, widgetTop - popup.size.height - gap))
    }

    // --- Changes tab ---

    private fun changesContent(): JComponent {
        val (diff, isCommitMode) = getActiveDiff()
        val settings = TrackerSettings.getInstance()
        val total = diff.addedLines + diff.deletedLines
        val level = settings.levelOf(total)

        val content = column()
        content.add(header(Tab.CHANGES) {
            redrawOnNextUpdate = true
            if (isCommitMode) CommitTrackerService.getInstance(project).refresh()
            else SessionTrackerService.getInstance(project).refresh()
        })
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        content.add(summary(diff, level, settings.lineLimit))

        val allFiles = diff.perFileStats.values.toList()
        if (allFiles.isEmpty()) {
            content.add(Box.createVerticalStrut(JBUI.scale(14)))
            content.add(textLabel("No changes detected", JBFont.label(), muted))
            return content
        }

        var sortByName = false
        val checks = mutableListOf<Pair<JCheckBox, FileLineStat>>()

        val allCheckbox = checkBox("Files (${allFiles.size})")
        allCheckbox.font = JBFont.label().asBold()
        allCheckbox.foreground = muted

        val fitLink = linkLabel("Fit to limit", link)
        fitLink.toolTipText = "<html>Select files that fit within ${settings.lineLimit} lines.<br>" +
            "Related files, such as a class and its test, are kept together.</html>"
        val sortLink = linkLabel("Most changed ▾", muted)

        val filesHeader = baselineRow()
        filesHeader.add(allCheckbox, cell(0, weight = 1.0))
        if (level == LimitLevel.OVER) filesHeader.add(fitLink, cell(1, leftGap = 12))
        filesHeader.add(sortLink, cell(2, leftGap = 12, trailing = true))

        val listPanel = column()

        // Explains what "Fit to limit" grouped or left out; hidden until it is used
        val planLabel = textLabel("", JBFont.medium(), muted)
        planLabel.isVisible = false

        val commitBtn = CommitButton()
        commitBtn.alignmentX = Component.LEFT_ALIGNMENT
        commitBtn.addActionListener { focusCommitPanel() }

        fun updateSelection() {
            val selected = checks.filter { it.first.isSelected }.map { it.second }
            val selectedTotal = selected.sumOf { it.addedLines + it.deletedLines }
            allCheckbox.isSelected = selected.size == allFiles.size

            commitBtn.isEnabled = selected.isNotEmpty()
            commitBtn.text = if (selected.isEmpty()) {
                "Select files to commit"
            } else {
                val files = if (selected.size == 1) "1 file" else "${selected.size} files"
                "Commit $files · $selectedTotal lines"
            }
            commitBtn.overLimit = settings.levelOf(selectedTotal) == LimitLevel.OVER
        }

        // Only on user actions: opening the popup must not override choices made in the Commit tab
        fun onSelectionChanged() {
            updateSelection()
            syncCommitCheckboxes(checks)
        }

        fun setIncluded(cb: JCheckBox, f: FileLineStat, included: Boolean) {
            if (cb.isSelected == included) return
            cb.isSelected = included
            if (included) moveToDefaultList(f.filePath) else moveToExcludedList(f.filePath)
        }

        fun rebuildList() {
            val sorted = if (sortByName) {
                allFiles.sortedBy { it.fileName.lowercase() }
            } else {
                allFiles.sortedByDescending { it.addedLines + it.deletedLines }
            }

            val priorState = checks.associate { it.second.filePath to it.first.isSelected }
            checks.clear()
            listPanel.removeAll()

            for (f in sorted) {
                val cb = checkBox(null)
                cb.isSelected = priorState[f.filePath] ?: !isInExcludedList(f.filePath)
                cb.addActionListener {
                    if (cb.isSelected) moveToDefaultList(f.filePath) else moveToExcludedList(f.filePath)
                    onSelectionChanged()
                }
                checks.add(Pair(cb, f))
                listPanel.add(fileRow(f, cb))
            }

            listPanel.revalidate()
            listPanel.repaint()
        }

        rebuildList()
        updateSelection()

        allCheckbox.addActionListener {
            val include = allCheckbox.isSelected
            for ((cb, fileStat) in checks) setIncluded(cb, fileStat, include)
            onSelectionChanged()
        }

        onClick(sortLink) {
            sortByName = !sortByName
            sortLink.text = if (sortByName) "A-Z ▾" else "Most changed ▾"
            rebuildList()
        }

        onClick(fitLink) {
            val plan = CommitSplitter.plan(allFiles, settings.lineLimit)
            for ((cb, fileStat) in checks) setIncluded(cb, fileStat, fileStat.filePath in plan.keep)
            onSelectionChanged()
            planLabel.text = planSummary(plan)
            planLabel.isVisible = planLabel.text.isNotEmpty()
            popupRoot?.revalidate()
            repackAboveWidget()
        }

        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        content.add(filesHeader)
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        content.add(fileScroll(listPanel, allFiles.size))
        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(planLabel)
        content.add(Box.createVerticalStrut(JBUI.scale(6)))

        // --- Footer: commit button and ignored-files hint ---
        val footer = JPanel(BorderLayout(JBUI.scale(12), 0))
        footer.isOpaque = false
        footer.alignmentX = Component.LEFT_ALIGNMENT
        footer.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
        footer.add(commitBtn, BorderLayout.CENTER)
        if (diff.ignoredFiles > 0) footer.add(ignoredHint(diff.ignoredFiles), BorderLayout.EAST)
        content.add(footer)
        return content
    }

    // --- Branch tab ---

    private fun branchContent(result: BranchDiff?, loading: Boolean): JComponent {
        val content = column()
        content.add(header(Tab.BRANCH) { showTab(Tab.BRANCH) })
        content.add(Box.createVerticalStrut(JBUI.scale(16)))

        if (loading) {
            content.add(textLabel("Comparing with the base branch…", JBFont.label(), muted))
            return content
        }
        if (result == null) {
            content.add(textLabel("No base branch found. Set one in Settings → Tools → Code Line Tracker.", JBFont.label(), muted))
            return content
        }

        val branchLine = baselineRow()
        branchLine.add(textLabel(result.branch, mono(12), UIUtil.getLabelForeground()), cell(0))
        branchLine.add(textLabel("vs", JBFont.medium(), muted), cell(1, leftGap = 6))
        branchLine.add(textLabel(result.base, mono(12), UIUtil.getLabelForeground()), cell(2, leftGap = 6, weight = 1.0))
        content.add(branchLine)
        content.add(Box.createVerticalStrut(JBUI.scale(12)))

        val settings = TrackerSettings.getInstance()
        val diff = result.diff
        val total = diff.addedLines + diff.deletedLines
        content.add(summary(diff, settings.branchLevelOf(total), settings.branchLimit))

        val files = diff.perFileStats.values.sortedByDescending { it.addedLines + it.deletedLines }
        content.add(Box.createVerticalStrut(JBUI.scale(16)))
        if (files.isEmpty()) {
            content.add(textLabel("No changes compared with ${result.base}", JBFont.label(), muted))
            return content
        }

        val filesHeader = baselineRow()
        filesHeader.add(textLabel("Files (${files.size}) · committed and uncommitted", JBFont.label().asBold(), muted), cell(0, weight = 1.0))
        filesHeader.minimumSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
        filesHeader.preferredSize = Dimension(JBUI.scale(CONTENT_WIDTH), JBUI.scale(ROW_HEIGHT))
        content.add(filesHeader)
        content.add(Box.createVerticalStrut(JBUI.scale(4)))

        val listPanel = column()
        for (f in files) listPanel.add(fileRow(f, null))
        content.add(fileScroll(listPanel, files.size))

        if (diff.ignoredFiles > 0) {
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            content.add(ignoredHint(diff.ignoredFiles))
        }
        return content
    }

    // --- Building blocks ---

    /** Changes | Branch switch on the left, refresh icon on the right */
    private fun header(active: Tab, onRefresh: () -> Unit): JComponent {
        val tabs = RoundedPanel(tabTrack, 8)
        tabs.layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        tabs.border = JBUI.Borders.empty(2)
        for (tab in Tab.values()) {
            val pill = RoundedPanel(if (tab == active) tabActive else null, 6)
            pill.layout = BorderLayout()
            pill.border = JBUI.Borders.empty(4, 14)
            val label = JBLabel(tab.title)
            label.font = JBFont.label()
            label.foreground = if (tab == active) UIUtil.getLabelForeground() else muted
            pill.add(label, BorderLayout.CENTER)
            if (tab != active) {
                pill.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                onClick(pill) { showTab(tab) }
            }
            tabs.add(pill)
        }

        val refresh = JBLabel(AllIcons.Actions.Refresh)
        refresh.toolTipText = "Refresh"
        refresh.border = JBUI.Borders.empty(4)
        refresh.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        onClick(refresh, onRefresh)

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.alignmentX = Component.LEFT_ALIGNMENT
        header.add(tabs, BorderLayout.WEST)
        header.add(refresh, BorderLayout.EAST)
        header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
        return header
    }

    /** "575 / 500  +530 −45 … 75 over" on one baseline, with a progress bar underneath when a limit applies */
    private fun summary(diff: DiffResult, level: LimitLevel, limit: Int): JComponent {
        val total = diff.addedLines + diff.deletedLines
        val levelColor = level.color

        val row = baselineRow()
        var col = 0
        row.add(textLabel(total.toString(), mono(20).asBold(), UIUtil.getLabelForeground()), cell(col++))
        if (levelColor != null) row.add(textLabel("/ $limit", mono(13), muted), cell(col++, leftGap = 6))
        row.add(textLabel("+${diff.addedLines}", mono(12).asBold(), green), cell(col++, leftGap = 12))
        row.add(textLabel("−${diff.deletedLines}", mono(12).asBold(), red), cell(col++, leftGap = 8, weight = 1.0))
        if (levelColor != null) row.add(textLabel(limitText(total, limit), JBFont.medium(), levelColor), cell(col, trailing = true))

        if (levelColor == null) return row

        val panel = column()
        panel.add(row)
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))
        panel.add(LimitBar(total.toDouble() / limit, levelColor))
        return panel
    }

    private fun fileRow(f: FileLineStat, checkbox: JCheckBox?): JComponent {
        val row = JPanel(GridBagLayout())
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.preferredSize = Dimension(JBUI.scale(CONTENT_WIDTH), JBUI.scale(ROW_HEIGHT))
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))

        val name = textLabel(f.fileName, JBFont.label(), UIUtil.getLabelForeground())
        name.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        name.toolTipText = f.filePath

        // Unchecked files are dimmed so the selection reads at a glance
        fun restingColor(): Color = if (checkbox == null || checkbox.isSelected) UIUtil.getLabelForeground() else muted
        name.foreground = restingColor()
        checkbox?.addItemListener { name.foreground = restingColor() }
        name.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(ev: MouseEvent) {
                openFile(f.filePath)
                activePopup?.cancel()
            }
            override fun mouseEntered(ev: MouseEvent) { name.foreground = link }
            override fun mouseExited(ev: MouseEvent) { name.foreground = restingColor() }
        })

        var col = 0
        if (checkbox != null) row.add(checkbox, cell(col++))
        // Same gap as the select-all checkbox uses before its text, so names line up with "Files (N)"
        val nameGap = if (checkbox != null) checkbox.iconTextGap else 0
        row.add(name, cell(col++, leftGap = nameGap, weight = 1.0, scaleGap = false))
        row.add(numberLabel("+${f.addedLines}", green), cell(col++, leftGap = 8))
        row.add(numberLabel("−${f.deletedLines}", red), cell(col, leftGap = 4, trailing = true))
        return row
    }

    private fun numberLabel(text: String, color: Color): JBLabel {
        val label = textLabel(text, mono(12), color)
        label.horizontalAlignment = SwingConstants.RIGHT
        label.preferredSize = Dimension(JBUI.scale(NUMBER_WIDTH), label.preferredSize.height)
        return label
    }

    private fun ignoredHint(count: Int): JBLabel {
        val hint = textLabel("$count ignored", JBFont.medium(), muted)
        hint.icon = AllIcons.General.Information
        hint.toolTipText = "$count changed file(s) match an ignore pattern and are not counted. " +
            "Change the patterns in Settings → Tools → Code Line Tracker."
        return hint
    }

    private fun fileScroll(listPanel: JPanel, fileCount: Int): JBScrollPane {
        val scroll = JBScrollPane(listPanel)
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        scroll.alignmentX = Component.LEFT_ALIGNMENT
        scroll.border = JBUI.Borders.empty()
        val height = JBUI.scale(minOf(fileCount, 10) * ROW_HEIGHT)
        scroll.preferredSize = Dimension(JBUI.scale(CONTENT_WIDTH), height)
        scroll.maximumSize = Dimension(Int.MAX_VALUE, height)
        return scroll
    }

    private fun column(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT
        return panel
    }

    /** A row whose cells share a text baseline, so mixed font sizes line up */
    private fun baselineRow(): JPanel {
        val row = JPanel(GridBagLayout())
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    private fun cell(
        x: Int,
        leftGap: Int = 0,
        weight: Double = 0.0,
        trailing: Boolean = false,
        scaleGap: Boolean = true,
    ): GridBagConstraints {
        val c = GridBagConstraints()
        c.gridx = x
        c.gridy = 0
        c.weightx = weight
        c.anchor = if (trailing) GridBagConstraints.BASELINE_TRAILING else GridBagConstraints.BASELINE_LEADING
        c.insets = Insets(0, if (scaleGap) JBUI.scale(leftGap) else leftGap, 0, 0)
        return c
    }

    private fun checkBox(text: String?): JCheckBox {
        val cb = JCheckBox(text)
        cb.isOpaque = false
        cb.border = JBUI.Borders.empty()
        cb.alignmentX = Component.LEFT_ALIGNMENT
        return cb
    }

    private fun textLabel(text: String, font: Font, color: Color): JBLabel {
        val label = JBLabel(text)
        label.font = font
        label.foreground = color
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun linkLabel(text: String, color: Color): JBLabel {
        val label = textLabel(text, JBFont.medium(), color)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        return label
    }

    private fun onClick(component: JComponent, action: () -> Unit) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(ev: MouseEvent) = action()
        })
    }

    /** The editor font (JetBrains Mono by default), so numbers line up like they do in code */
    private fun mono(size: Int): JBFont {
        val family = EditorColorsManager.getInstance().globalScheme.editorFontName
        return JBUI.Fonts.create(family, size)
    }

    private fun planSummary(plan: CommitSplitter.Plan): String {
        val lines = mutableListOf<String>()
        if (plan.keptTogether.isNotEmpty()) {
            lines += "Kept together: " + describeGroups(plan.keptTogether)
        }
        if (plan.tooLarge.isNotEmpty()) {
            lines += "Over the limit on their own, commit separately: " + describeGroups(plan.tooLarge)
        }
        if (lines.isEmpty()) return ""
        return "<html><div style='width:${JBUI.scale(CONTENT_WIDTH)}px'>" +
            lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</div></html>"
    }

    private fun describeGroups(groups: List<List<FileLineStat>>): String {
        val shown = groups.take(3).joinToString("; ") { group ->
            group.joinToString(" + ") { it.fileName } + " (${CommitSplitter.size(group)})"
        }
        return if (groups.size > 3) "$shown; +${groups.size - 3} more" else shown
    }

    private fun focusCommitPanel() {
        activePopup?.cancel()
        ToolWindowManager.getInstance(project).getToolWindow("Commit")?.show()
    }

    fun restoreTempChangelist() {
        try {
            val clManager = ChangeListManager.getInstance(project)
            val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return
            val defaultList = clManager.defaultChangeList

            if (tempList.changes.isNotEmpty()) {
                clManager.moveChangesTo(defaultList, *tempList.changes.toTypedArray())
            }
            clManager.removeChangeList(tempList)
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    fun cleanupIfEmpty() {
        try {
            val clManager = ChangeListManager.getInstance(project)
            val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return
            if (tempList.changes.isEmpty()) {
                clManager.removeChangeList(tempList)
            }
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    private fun openFile(filePath: String) {
        val basePath = project.basePath ?: return
        val absPath = if (filePath.startsWith("/")) filePath else "$basePath/$filePath"
        val vf = LocalFileSystem.getInstance().findFileByIoFile(File(absPath)) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }
}

/**
 * Filled button: blue normally, red when the selection is over the limit, white text on both.
 * Painted by hand because the IDE's button style has no red variant, and both states
 * should look like the same button.
 */
private class CommitButton : JButton() {

    private val blue = JBColor(Color(0x3574F0), Color(0x3574F0))
    private val red = JBColor(Color(0xC7333F), Color(0xC7333F))
    private val disabledFill = JBColor(Color(0xDFE1E5), Color(0x43454A))
    private val disabledText = JBColor(Color(0x8C8F94), Color(0x8C8F94))

    var overLimit = false
        set(value) {
            field = value
            repaint()
        }

    init {
        font = JBFont.label().asBold()
        foreground = Color.WHITE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isRolloverEnabled = true
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
    }

    // Keep the plain UI after theme changes too; the IDE's own button UI would paint over ours
    override fun updateUI() {
        setUI(BasicButtonUI())
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(0, 12)
    }

    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().also { it.height = JBUI.scale(34) }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val base = when {
                !isEnabled -> disabledFill
                overLimit -> red
                else -> blue
            }
            g2.color = if (isEnabled && (model.isPressed || model.isRollover)) ColorUtil.darker(base, 1) else base
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)

            // Text drawn here too: the basic UI engraves disabled text, which looks out of place
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = if (isEnabled) Color.WHITE else disabledText
            val fm = g2.fontMetrics
            val label = text.orEmpty()
            val x = (width - fm.stringWidth(label)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(label, x, y)
        } finally {
            g2.dispose()
        }
    }
}

/** Panel with a rounded background; transparent when [fill] is null */
private class RoundedPanel(private val fill: Color?, private val arc: Int) : JPanel() {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val color = fill ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val a = JBUI.scale(arc)
            g2.fillRoundRect(0, 0, width, height, a, a)
        } finally {
            g2.dispose()
        }
    }
}

/** Thin rounded bar showing how much of the line limit is used */
private class LimitBar(fraction: Double, private val color: Color) : JComponent() {

    private val fraction = fraction.coerceIn(0.0, 1.0)
    private val track = JBColor(Color(0xDFE1E5), Color(0x393B40))

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(0, JBUI.scale(4))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(4))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height
            g2.color = track
            g2.fillRoundRect(0, 0, width, h, h, h)
            g2.color = color
            g2.fillRoundRect(0, 0, (width * fraction).toInt(), h, h, h)
        } finally {
            g2.dispose()
        }
    }
}

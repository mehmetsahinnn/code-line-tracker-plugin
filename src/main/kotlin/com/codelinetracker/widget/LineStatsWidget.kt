package com.codelinetracker.widget

import com.codelinetracker.model.DiffResult
import com.codelinetracker.model.FileLineStat
import com.codelinetracker.service.CommitTrackerService
import com.codelinetracker.service.SessionTrackerService
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*

class LineStatsWidget(project: Project) : EditorBasedWidget(project), CustomStatusBarWidget {

    companion object {
        const val WIDGET_ID = "CodeLineTrackerWidget"
        private const val POPUP_WIDTH = 450
        const val TEMP_LIST_NAME = "[CLT] Temporarily Excluded from Commit"
    }

    private val textPanel = TextPanel.WithIconAndArrows()
    private val sessionListener: () -> Unit = { updateDisplay() }
    private val commitListener: () -> Unit = { updateDisplay() }
    private var activePopup: JBPopup? = null

    init {
        textPanel.text = "Total : 0 || +0 -0"
        textPanel.toolTipText = "Code Line Tracker"
        textPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        textPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                togglePopup(e)
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
        } catch (_: Exception) {}
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

    private fun updateDisplay() {
        SwingUtilities.invokeLater {
            val (diff, isCommitMode) = getActiveDiff()
            val total = diff.addedLines + diff.deletedLines
            textPanel.text = "Total : $total || +${diff.addedLines} -${diff.deletedLines}"
            val mode = if (isCommitMode) "Commit" else "Session"
            textPanel.toolTipText = "$mode — ${diff.filesChanged} files | $total || +${diff.addedLines} -${diff.deletedLines}"
        }
    }

    private fun togglePopup(e: MouseEvent) {
        activePopup?.let {
            if (!it.isDisposed) { it.cancel(); activePopup = null; return }
        }
        showDetailsPopup(e)
    }

    // --- Changelist helpers ---

    private fun isInExcludedList(filePath: String): Boolean {
        val clManager = ChangeListManager.getInstance(project)
        val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return false
        val basePath = project.basePath ?: return false
        return tempList.changes.any { change ->
            matchPath(change, basePath) == filePath
        }
    }

    private fun moveToExcludedList(filePath: String) {
        val clManager = ChangeListManager.getInstance(project)
        val basePath = project.basePath ?: return
        val defaultList = clManager.defaultChangeList

        val change = defaultList.changes.find { matchPath(it, basePath) == filePath } ?: return

        val tempList = clManager.findChangeList(TEMP_LIST_NAME)
            ?: clManager.addChangeList(TEMP_LIST_NAME, "Files temporarily excluded by Code Line Tracker. They will be restored automatically.")
        clManager.moveChangesTo(tempList, change)
    }

    private fun moveToDefaultList(filePath: String) {
        val clManager = ChangeListManager.getInstance(project)
        val basePath = project.basePath ?: return
        val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return
        val defaultList = clManager.defaultChangeList

        val change = tempList.changes.find { matchPath(it, basePath) == filePath } ?: return
        clManager.moveChangesTo(defaultList, change)

        if (tempList.changes.size <= 1) {
            clManager.removeChangeList(tempList)
        }
    }

    private fun matchPath(change: com.intellij.openapi.vcs.changes.Change, basePath: String): String {
        val vf = change.virtualFile
        if (vf != null) return vf.path.removePrefix("$basePath/")
        val after = change.afterRevision?.file?.path
        if (after != null) return after.removePrefix("$basePath/")
        val before = change.beforeRevision?.file?.path
        if (before != null) return before.removePrefix("$basePath/")
        return ""
    }

    // --- Popup ---

    private fun showDetailsPopup(e: MouseEvent) {
        val (diff, isCommitMode) = getActiveDiff()
        val modeName = if (isCommitMode) "Commit Mode" else "Session Mode"

        val green = JBColor(Color(0x59A869), Color(0x6A8759))
        val red = JBColor(Color(0xDB5860), Color(0xCC7832))
        val link = JBColor(Color(0x589DF6), Color(0x589DF6))
        val totalColor = JBColor(Color(0x6C707E), Color(0xA9B7C6))

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(12, 16, 12, 16)
        content.background = UIUtil.getPanelBackground()

        // --- Header ---
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.alignmentX = Component.LEFT_ALIGNMENT
        header.maximumSize = Dimension(POPUP_WIDTH, 20)

        val title = JBLabel("Code Line Tracker")
        title.font = title.font.deriveFont(Font.BOLD, 14f)
        header.add(title, BorderLayout.WEST)

        val refreshBtn = JBLabel("Refresh")
        refreshBtn.foreground = link
        refreshBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        refreshBtn.font = refreshBtn.font.deriveFont(12f)
        refreshBtn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(ev: MouseEvent) {
                if (isCommitMode) CommitTrackerService.getInstance(project).refresh()
                else SessionTrackerService.getInstance(project).refresh()
                activePopup?.cancel()
            }
        })
        header.add(refreshBtn, BorderLayout.EAST)
        content.add(header)

        val mode = JBLabel(modeName)
        mode.foreground = if (isCommitMode) green else JBColor.GRAY
        mode.font = mode.font.deriveFont(11f)
        mode.alignmentX = Component.LEFT_ALIGNMENT
        content.add(mode)

        content.add(Box.createVerticalStrut(10))
        content.add(sep())
        content.add(Box.createVerticalStrut(8))

        // --- Total ---
        val totalLines = diff.addedLines + diff.deletedLines
        val totalRow = totalRow("Total : $totalLines", totalColor, "+${diff.addedLines}", green, "-${diff.deletedLines}", red)
        content.add(totalRow)

        // --- File list ---
        val files = diff.perFileStats.values.sortedByDescending { it.addedLines + it.deletedLines }

        if (files.isNotEmpty()) {
            content.add(Box.createVerticalStrut(8))
            content.add(sep())
            content.add(Box.createVerticalStrut(6))

            val filesHeader = JBLabel("Changed Files (${files.size})")
            filesHeader.font = filesHeader.font.deriveFont(Font.BOLD, 12f)
            filesHeader.alignmentX = Component.LEFT_ALIGNMENT
            content.add(filesHeader)
            content.add(Box.createVerticalStrut(6))

            val selectedLabel = JBLabel()
            selectedLabel.font = selectedLabel.font.deriveFont(Font.BOLD, 12f)
            selectedLabel.alignmentX = Component.LEFT_ALIGNMENT

            val checks = mutableListOf<Pair<JCheckBox, FileLineStat>>()

            val listPanel = JPanel()
            listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
            listPanel.isOpaque = false

            for (f in files) {
                val row = JPanel(BorderLayout(4, 0))
                row.isOpaque = false
                row.alignmentX = Component.LEFT_ALIGNMENT
                row.maximumSize = Dimension(POPUP_WIDTH - 32, 26)
                row.border = JBUI.Borders.empty(1, 0)

                val cb = JCheckBox()
                cb.isOpaque = false
                cb.isSelected = !isInExcludedList(f.filePath)
                row.add(cb, BorderLayout.WEST)

                val name = JBLabel(f.fileName)
                name.font = name.font.deriveFont(12f)
                name.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                name.toolTipText = f.filePath
                name.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(ev: MouseEvent) {
                        openFile(f.filePath)
                        activePopup?.cancel()
                    }
                    override fun mouseEntered(ev: MouseEvent) { name.foreground = link }
                    override fun mouseExited(ev: MouseEvent) { name.foreground = UIUtil.getLabelForeground() }
                })
                row.add(name, BorderLayout.CENTER)

                val nums = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
                nums.isOpaque = false
                val al = JBLabel("+${f.addedLines}")
                al.font = al.font.deriveFont(Font.BOLD, 11f)
                al.foreground = green
                val dl = JBLabel("-${f.deletedLines}")
                dl.font = dl.font.deriveFont(Font.BOLD, 11f)
                dl.foreground = red
                nums.add(al)
                nums.add(dl)
                row.add(nums, BorderLayout.EAST)

                checks.add(Pair(cb, f))
                listPanel.add(row)
            }

            fun updateSel() {
                val sel = checks.filter { it.first.isSelected }.map { it.second }
                val sa = sel.sumOf { it.addedLines }
                val sd = sel.sumOf { it.deletedLines }
                val st = sa + sd
                selectedLabel.text = "Selected (${sel.size}/${files.size}) : $st || +$sa -$sd"
            }

            for ((cb, fileStat) in checks) {
                cb.addActionListener {
                    if (cb.isSelected) {
                        moveToDefaultList(fileStat.filePath)
                    } else {
                        moveToExcludedList(fileStat.filePath)
                    }
                    updateSel()
                }
            }
            updateSel()

            val listHeight = minOf(files.size * 28, 280)
            val scroll = JBScrollPane(listPanel)
            scroll.alignmentX = Component.LEFT_ALIGNMENT
            scroll.border = JBUI.Borders.empty()
            scroll.preferredSize = Dimension(POPUP_WIDTH - 32, listHeight)
            content.add(scroll)

            content.add(Box.createVerticalStrut(8))
            content.add(sep())
            content.add(Box.createVerticalStrut(6))
            content.add(selectedLabel)

            // --- Commit Selected button ---
            content.add(Box.createVerticalStrut(10))
            val commitBtn = JButton("Commit Selected")
            commitBtn.alignmentX = Component.LEFT_ALIGNMENT
            commitBtn.maximumSize = Dimension(POPUP_WIDTH, 32)
            commitBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            commitBtn.addActionListener { focusCommitPanel() }
            content.add(commitBtn)
        } else {
            content.add(Box.createVerticalStrut(8))
            val noFiles = JBLabel("No changes detected")
            noFiles.foreground = JBColor.GRAY
            noFiles.alignmentX = Component.LEFT_ALIGNMENT
            content.add(noFiles)
        }

        val popup = PopupFactoryImpl.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        activePopup = popup
        popup.showUnderneathOf(textPanel)
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
        } catch (_: Exception) {}
    }

    fun cleanupIfEmpty() {
        try {
            val clManager = ChangeListManager.getInstance(project)
            val tempList = clManager.findChangeList(TEMP_LIST_NAME) ?: return
            if (tempList.changes.isEmpty()) {
                clManager.removeChangeList(tempList)
            }
        } catch (_: Exception) {}
    }

    private fun openFile(filePath: String) {
        val basePath = project.basePath ?: return
        val absPath = if (filePath.startsWith("/")) filePath else "$basePath/$filePath"
        val vf = LocalFileSystem.getInstance().findFileByIoFile(File(absPath)) ?: return
        OpenFileDescriptor(project, vf).navigate(true)
    }

    private fun totalRow(label: String, lc: Color, at: String, ac: Color, dt: String, dc: Color): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(POPUP_WIDTH, 24)

        val n = JBLabel(label)
        n.font = n.font.deriveFont(Font.BOLD, 13f)
        n.foreground = lc
        row.add(n, BorderLayout.WEST)

        val s = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        s.isOpaque = false
        val sep = JBLabel("||"); sep.font = sep.font.deriveFont(Font.BOLD, 13f); sep.foreground = lc
        val a = JBLabel(at); a.font = a.font.deriveFont(Font.BOLD, 13f); a.foreground = ac
        val d = JBLabel(dt); d.font = d.font.deriveFont(Font.BOLD, 13f); d.foreground = dc
        s.add(sep); s.add(a); s.add(d)
        row.add(s, BorderLayout.EAST)
        return row
    }

    private fun sep(): JSeparator {
        val s = JSeparator(SwingConstants.HORIZONTAL)
        s.alignmentX = Component.LEFT_ALIGNMENT
        s.maximumSize = Dimension(POPUP_WIDTH, 1)
        return s
    }
}

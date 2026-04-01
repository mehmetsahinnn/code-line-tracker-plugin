package com.codelinetracker.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class TrackerStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        SessionTrackerService.getInstance(project).init()
        CommitTrackerService.getInstance(project).init()
    }
}

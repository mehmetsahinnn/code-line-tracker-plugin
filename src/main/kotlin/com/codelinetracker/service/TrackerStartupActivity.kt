package com.codelinetracker.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TrackerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SessionTrackerService.getInstance(project).init()
        CommitTrackerService.getInstance(project).init()
    }
}

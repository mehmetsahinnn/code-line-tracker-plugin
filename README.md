# Code Line Tracker

An IntelliJ IDEA plugin that shows how many lines you have changed, right in the status bar, and helps you keep every commit under a line limit.

It started from a simple team rule: **no commit over 500 changed lines**. Code Line Tracker counts your added and deleted lines as you type, warns you when you get close to the limit, and helps you split a large change into smaller commits without separating related files.

- Works with IntelliJ IDEA and other JetBrains IDEs, **2023.3 to 2026.2** (builds 233 to 262)
- Requires a Git project (it uses the bundled Git plugin and the `git` command)
- Everything runs locally; nothing is sent anywhere

---

## Contents

- [Features](#features)
- [Getting started](#getting-started)
- [The status bar widget](#the-status-bar-widget)
- [The popup](#the-popup)
  - [Changes tab](#changes-tab)
  - [Fit to limit](#fit-to-limit)
  - [Branch tab](#branch-tab)
- [Commit check](#commit-check)
- [Settings](#settings)
- [How lines are counted](#how-lines-are-counted)
- [FAQ and troubleshooting](#faq-and-troubleshooting)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Releasing a new version](#releasing-a-new-version)

---

## Features

| | |
|---|---|
| **Live line counter** | Added and deleted lines update as you type, including unsaved edits. |
| **Line limit** | Set a per-commit limit (default 500). A green, yellow or red dot shows how close you are. |
| **Commit check** | Committing more lines than the limit asks for confirmation first. |
| **Fit to limit** | One click selects the files that fit within the limit. A class and its test are always kept together. |
| **Commit tab sync** | Checking or unchecking files in the popup does the same in IntelliJ's Commit tool window. |
| **Branch view** | Shows how big your whole branch is compared with `main`: roughly the size of your pull request. |
| **Ignore patterns** | Lock files, minified files, source maps, snapshots and generated code are not counted. |
| **Changelist decoration** | Each changelist in the Changes view shows its own line totals. |

---

## Getting started

1. Install the plugin from the JetBrains Marketplace (**Settings → Plugins → Marketplace → "Code Line Tracker"**), or from a `.zip` with **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Open a project that uses Git.
3. Look at the bottom-right status bar: the widget shows your current change size.
4. Click it to open the popup.

No setup is needed. To change the limit or the ignored files, open **Settings → Tools → Code Line Tracker**.

---

## The status bar widget

| State | Shown as | Meaning |
|---|---|---|
| 🟢 Under 70% of the limit | `180 / 500` | Plenty of room |
| 🟡 70% up to the limit | `410 / 500` | Getting close: a good time to commit |
| 🔴 Over the limit | `575 / 500` | Split the change before committing |
| Limit turned off | `+530 −45` | Plain added / deleted counts |

Hover over the widget to see the mode, the number of changed files, the added and deleted lines, and how many lines are left.

The widget works in two modes and switches between them automatically:

- **Session mode** (default): all uncommitted changes in the project.
- **Commit mode**: active while the Commit tool window is open. It recounts whenever your changelists change.

---

## The popup

Click the widget to open the popup. It has two tabs, **Changes** and **Branch**, and a refresh button (⟳) in the top-right corner.

### Changes tab

```
┌──────────────────────────────────────────┐
│  [Changes] Branch                     ⟳  │
│                                          │
│  575 / 500  +530 −45            75 over  │
│  ████████████████████████████████████    │
│                                          │
│  ☑ Files (6)   Fit to limit  Most changed│
│  ☑ UserService.kt              +210  −12 │
│  ☑ UserServiceTest.kt          +140   −3 │
│  ☐ OrderController.kt           +98  −20 │
│  …                                       │
│  ┌────────────────────────────────────┐  │
│  │      Commit 5 files · 497 lines    │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

- **Summary row:** the total number of changed lines, your limit, the added / deleted split and how many lines are left (or over). The bar fills up as you approach the limit.
- **File list:** every changed file with its own counts. Click a file name to open it. Sort by **Most changed** (default) or **A-Z**.
- **Checkboxes:** choose which files go into the next commit. The checkbox next to **Files (N)** selects or clears all of them.
  - Unchecked files are moved to a temporary changelist called **"[CLT] Temporarily Excluded from Commit"** and are also unchecked in the Commit tool window. They are moved back automatically when you close the Commit tool window.
- **Commit button:** shows how many files and lines you have selected and opens the Commit tool window. It is **blue** while the selection fits within the limit and **red** when it does not.
- **ⓘ ignored:** appears when some changed files match an ignore pattern; hover for details.

### Fit to limit

When your change is over the limit, a **Fit to limit** link appears above the file list. Clicking it selects the largest set of files that stays within the limit, so you can commit them first and the rest afterwards.

Related files are **never split across commits**. Files are grouped by name:

| Kept together | Example |
|---|---|
| A class and its tests | `UserService.kt` + `UserServiceTest.kt`, `UserServiceTests.java`, `UserServiceIT.java`, `UserServiceIntegrationTest.kt` |
| Go and Python test conventions | `user_service.go` + `user_service_test.go`; `user_service.py` + `test_user_service.py` |
| A component and its companions | `Button.tsx` + `Button.test.tsx` + `Button.module.css` + `Button.stories.tsx` |

Very common file names (`index`, `utils`, `config`, `types`, `main` and similar) are only grouped with files in the **same folder**, so unrelated `index.ts` files across the project are not joined.

After using Fit to limit, the popup lists which files were kept together, and which groups are over the limit on their own and need a commit of their own.

> **Note:** the grouping is based on file names only. The plugin cannot see code dependencies, for example a new class that another changed file starts using. Treat the selection as a suggestion and adjust the checkboxes if needed.

### Branch tab

The **Branch** tab compares your current branch with the branch it was created from, roughly what your pull request will contain:

- Committed changes on the branch **and** uncommitted / unsaved work are included.
- The base branch is detected automatically, in this order: the remote's default branch (`origin/HEAD`), `origin/main`, `origin/master`, `main`, `master`. You can set it yourself in Settings, for example `develop`.
- If you set a **branch / pull request line limit** in Settings, the tab shows a progress bar for it as well.

---

## Commit check

When you commit more lines than your limit, IntelliJ asks before committing:

> **Commit Exceeds Line Limit**
> This commit changes 620 lines, which is 120 over your limit of 500.
> [Commit Anyway] [Cancel]

Only the files selected for that commit are counted. You can turn the check off in Settings.

---

## Settings

**Settings → Tools → Code Line Tracker**

| Setting | Default | Description |
|---|---|---|
| Warn when changes exceed a line limit | On | Turns the limit, the colors and Fit to limit on or off. |
| Line limit (added + deleted) | `500` | Maximum lines per commit. |
| Ask for confirmation when committing more lines than the limit | On | The [commit check](#commit-check). |
| Base branch for the Branch view | *(empty: detect automatically)* | For example `origin/develop`. |
| Branch / pull request line limit | `0` (off) | Optional limit for the whole branch. |
| Files to ignore when counting | See below | One glob pattern per line, relative to the project root. Lines starting with `#` are ignored. |

Default ignore patterns:

```
**/package-lock.json
**/yarn.lock
**/pnpm-lock.yaml
**/*.lock
**/*.min.js
**/*.min.css
**/*.map
**/generated/**
**/__snapshots__/**
```

Settings are stored per IDE installation (in `codeLineTracker.xml` in the IDE's configuration folder) and apply to all projects.

---

## How lines are counted

- **What counts:** lines added plus lines deleted, the same numbers `git diff --numstat` reports. A changed line counts as one deletion and one addition.
- **Against what:** your working tree compared with `HEAD`, including staged and unstaged changes.
- **Unsaved edits:** files you have edited but not saved are compared in memory, so the counter updates as you type. The plugin **never saves your files** for you (earlier versions did, which closed the code completion popup).
- **New files:** counted once they are added to Git. Untracked files are not included.
- **Binary files:** not counted.
- **Renamed files:** currently counted as a deletion plus an addition.
- **When it refreshes:** about 1.5 seconds after you stop typing, when files change on disk, and every 30 seconds.

---

## FAQ and troubleshooting

**The widget is not in the status bar.**
Right-click the status bar and make sure **Code Line Tracker** is checked.

**The numbers look too high.**
Check whether generated, lock or formatted files are in the list. Add patterns for them under *Files to ignore when counting*. Large renames are also counted in full (see [How lines are counted](#how-lines-are-counted)).

**The Branch tab says "No base branch found".**
The project has no `main` or `master` branch, or no `origin` remote. Set the base branch in Settings.

**Checkboxes in the Commit tool window do not follow the popup.**
IntelliJ has no public API for this, so the plugin uses an internal one. If it is not available in your IDE version, files are still moved to the "[CLT] Temporarily Excluded from Commit" changelist, but their checkboxes stay as they are. Search `idea.log` (**Help → Show Log in Finder/Explorer**) for `Commit checkbox sync` and open an issue with what you find.

**A "[CLT] Temporarily Excluded from Commit" changelist is left over.**
It is removed when you close the Commit tool window or the project. You can also delete it yourself; the files move back to your default changelist.

---

## Building from source

Requirements: **JDK 17** and an internet connection (Gradle downloads the IntelliJ Platform on the first build).

```bash
git clone https://github.com/mehmetsahinnn/code-line-tracker-plugin.git
cd code-line-tracker-plugin
./gradlew buildPlugin
```

The plugin is written to `build/distributions/Code Line Tracker-<version>.zip`. Install it with **Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart the IDE.

Other useful tasks:

| Task | What it does |
|---|---|
| `./gradlew runIde` | Starts a sandbox IDE with the plugin installed |
| `./gradlew verifyPlugin` | Checks compatibility against IntelliJ IDEA 2026.2 with the Plugin Verifier |
| `./gradlew bumpVersion` | Increases the patch version in `gradle.properties` |

> After every **successful** `buildPlugin`, the patch version in `gradle.properties` is increased automatically for the next build. The plugin you just built keeps the old number. Run `git checkout gradle.properties` if you do not want to keep that change.

Toolchain: Gradle 9.6.1, Kotlin 2.4.10, IntelliJ Platform Gradle Plugin 2.18.1. The plugin is compiled against IntelliJ IDEA 2023.3 so that it runs on every supported version.

---

## Project structure

```
src/main/kotlin/com/codelinetracker/
├── model/
│   └── LineChangeStats.kt          DiffResult, FileLineStat, BranchDiff
├── service/
│   ├── GitDiffExecutor.kt          Runs git, diffs unsaved documents in memory, branch diff
│   ├── SessionTrackerService.kt    Recounts on edits, file changes and every 30 s
│   ├── CommitTrackerService.kt     Commit mode while the Commit tool window is open
│   ├── ExclusionFilter.kt          Applies the ignore patterns
│   ├── CommitSplitter.kt           Fit to limit: grouping related files, choosing what fits
│   └── TrackerStartupActivity.kt   Starts the services when a project opens
├── settings/
│   ├── TrackerSettings.kt          Stored settings and limit levels (green / yellow / red)
│   └── TrackerConfigurable.kt      Settings → Tools → Code Line Tracker page
├── vcs/
│   ├── LineLimitCheckinHandlerFactory.kt   Confirmation before committing over the limit
│   ├── CommitInclusionSync.kt      Mirrors popup selection to the Commit tool window
│   ├── LineChangeListDecorator.kt  Line totals next to each changelist
│   └── ChangePaths.kt              Maps IntelliJ changes to diff paths
└── widget/
    ├── LineStatsWidget.kt          Status bar widget and the popup
    └── LineStatsWidgetFactory.kt
src/main/resources/META-INF/plugin.xml   Extensions, description and change notes
```

---

## Releasing a new version

1. Make sure `pluginVersion` in `gradle.properties` is **higher** than the version on the Marketplace (and than any version you installed locally).
2. Add a `<h3>` entry for it at the top of `<change-notes>` in `plugin.xml`.
3. Run `./gradlew buildPlugin` (optionally `./gradlew verifyPlugin`).
4. Upload `build/distributions/Code Line Tracker-<version>.zip` to the [JetBrains Marketplace](https://plugins.jetbrains.com/).
5. Commit and push the version change.

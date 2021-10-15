package org.jlleitschuh.gradle.ktlint

import org.eclipse.jgit.lib.RepositoryBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.intellij.lang.annotations.Language
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import javax.inject.Inject

internal const val FILTER_INCLUDE_PROPERTY_NAME = "internalKtlintGitFilter"

@Language("Bash")
internal val shShebang =
    """
    #!/bin/sh

    """.trimIndent()

internal const val startHookSection = "######## KTLINT-GRADLE HOOK START ########\n"
internal const val endHookSection = "######## KTLINT-GRADLE HOOK END ########\n"

private fun generateGradleCommand(
    taskName: String,
    gradleRootDirPrefix: String
): String {
    val gradleCommand = if (gradleRootDirPrefix.isNotEmpty()) {
        "./$gradleRootDirPrefix/gradlew -p ./$gradleRootDirPrefix"
    } else {
        "./gradlew"
    }
    return "$gradleCommand --quiet $taskName -P$FILTER_INCLUDE_PROPERTY_NAME=\"${'$'}CHANGED_FILES\""
}

private fun generateGitCommand(
    gradleRootDirPrefix: String
): String = if (gradleRootDirPrefix.isEmpty()) {
    "git --no-pager diff --name-status --no-color --cached"
} else {
    "git --no-pager diff --name-status --no-color --cached -- $gradleRootDirPrefix/"
}

private fun postCheck(
    shouldUpdateCommit: Boolean
): String = if (shouldUpdateCommit) {
    """
    echo "${'$'}CHANGED_FILES" | while read -r file; do
        if [ -f ${'$'}file ]; then
            git add ${'$'}file
        fi
    done
    """.trimIndent()
} else {
    ""
}

internal const val NF = "\$NF"
internal const val gradleCommandExitCode = "\$gradleCommandExitCode"

@Language("Sh")
internal fun generateGitHook(
    taskName: String,
    shouldUpdateCommit: Boolean,
    gradleRootDirPrefix: String
) =
    """

    CHANGED_FILES="${'$'}(${generateGitCommand(gradleRootDirPrefix)} | awk '$1 != "D" && $NF ~ /\.kts?$/ { print $NF }')"

    if [ -z "${'$'}CHANGED_FILES" ]; then
        echo "No Kotlin staged files."
        exit 0
    fi;

    echo "Running ktlint over these files:"
    echo "${'$'}CHANGED_FILES"

    diff=.git/unstaged-ktlint-git-hook.diff
    git diff --color=never > ${'$'}diff
    if [ -s ${'$'}diff ]; then
      git apply -R ${'$'}diff
    fi

    ${generateGradleCommand(taskName, gradleRootDirPrefix)}
    $gradleCommandExitCode=$?

    echo "Completed ktlint run."
    ${postCheck(shouldUpdateCommit)}

    if [ -s ${'$'}diff ]; then
      git apply --ignore-whitespace ${'$'}diff
    fi
    rm ${'$'}diff
    unset diff

    echo "Completed ktlint hook."
    exit $gradleCommandExitCode

    """.trimIndent()

internal fun KtlintPlugin.PluginHolder.addGitHookTasks() {
    if (target.rootProject == target) {
        addInstallGitHookFormatTask()
        addInstallGitHookCheckTask()
    }
}

private fun KtlintPlugin.PluginHolder.addInstallGitHookFormatTask() {
    target.tasks.register(
        INSTALL_GIT_HOOK_FORMAT_TASK,
        KtlintInstallGitHookTask::class.java
    ) {
        it.description = "Adds git hook to run ktlintFormat on changed files"
        it.group = HELP_GROUP
        it.taskName.set(FORMAT_PARENT_TASK_NAME)
        // Format git hook will automatically add back updated files to git commit
        it.shouldUpdateCommit.set(true)
        it.hookName.set("pre-commit")
    }
}

private fun KtlintPlugin.PluginHolder.addInstallGitHookCheckTask() {
    target.tasks.register(
        INSTALL_GIT_HOOK_CHECK_TASK,
        KtlintInstallGitHookTask::class.java
    ) {
        it.description = "Adds git hook to run ktlintCheck on changed files"
        it.group = HELP_GROUP
        it.taskName.set(CHECK_PARENT_TASK_NAME)
        it.shouldUpdateCommit.set(false)
        it.hookName.set("pre-commit")
    }
}

open class KtlintInstallGitHookTask @Inject constructor(
    objectFactory: ObjectFactory,
    projectLayout: ProjectLayout
) : DefaultTask() {
    @get:Input
    internal val taskName: Property<String> = objectFactory.property(String::class.java)

    @get:Input
    internal val shouldUpdateCommit: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(false)

    @get:Input
    internal val hookName: Property<String> = objectFactory.property(String::class.java)

    private val projectDir = projectLayout.projectDirectory.asFile

    private val rootDirectory = project.rootDir

    @TaskAction
    fun installHook() {
        val repo = RepositoryBuilder().findGitDir(projectDir).setMustExist(false).build()
        if (!repo.objectDatabase.exists()) {
            logger.warn("No git folder was found!")
            return
        }

        logger.info(".git directory path: ${repo.directory}")
        val gitHookDirectory = repo.directory.resolve("hooks")
        if (!gitHookDirectory.exists()) {
            logger.info("git hooks directory doesn't exist, creating one")
            gitHookDirectory.mkdir()
        }

        val gitHookFile = repo.directory.resolve("hooks/${hookName.get()}")
        logger.info("Hook file: $gitHookFile")
        if (!gitHookFile.exists()) {
            gitHookFile.createNewFile()
            gitHookFile.setExecutable(true)
        }
        val gradleRootDirPrefix = rootDirectory.relativeTo(repo.workTree).path

        if (gitHookFile.length() == 0L) {
            gitHookFile.writeText(
                "$shShebang$startHookSection${generateGitHook(
                    taskName.get(),
                    shouldUpdateCommit.get(),
                    gradleRootDirPrefix
                )}$endHookSection"
            )
            return
        }

        var hookContent = gitHookFile.readText()
        if (hookContent.contains(startHookSection)) {
            val startTagIndex = hookContent.indexOf(startHookSection)
            val endTagIndex = hookContent.indexOf(endHookSection)
            hookContent = hookContent.replaceRange(
                startTagIndex,
                endTagIndex,
                "$startHookSection${generateGitHook(
                    taskName.get(),
                    shouldUpdateCommit.get(),
                    gradleRootDirPrefix
                )}"
            )
            gitHookFile.writeText(hookContent)
        } else {
            gitHookFile.appendText(
                "$startHookSection${generateGitHook(
                    taskName.get(),
                    shouldUpdateCommit.get(),
                    gradleRootDirPrefix
                )}$endHookSection"
            )
        }
    }
}

internal fun BaseKtLintCheckTask.applyGitFilter() {
    val projectRelativePath = project.rootDir.toPath()
        .relativize(project.projectDir.toPath())
        .toString()
    val filesToInclude = (project.property(FILTER_INCLUDE_PROPERTY_NAME) as String)
        .split('\n')
        .filter { it.startsWith(projectRelativePath) }
        .map { it.replace("\\", "/") }

    if (filesToInclude.isNotEmpty()) {
        include { fileTreeElement ->
            if (fileTreeElement.isDirectory) {
                true
            } else {
                filesToInclude.any {
                    fileTreeElement
                        .file
                        .absolutePath
                        .replace("\\", "/")
                        .endsWith(it)
                }
            }
        }
    } else {
        exclude("*")
    }
}

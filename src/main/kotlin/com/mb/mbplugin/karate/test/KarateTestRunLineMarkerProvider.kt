package com.mb.mbplugin.karate.test

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.cucumber.psi.GherkinTag
import org.jetbrains.plugins.cucumber.psi.GherkinTokenTypes
import org.jetbrains.plugins.cucumber.psi.GherkinScenario
import com.mb.mbplugin.settings.KarateTestRunnerSettings
import java.io.File
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

/**
 * Provides run test gutter icons for scenario tags in Gherkin feature files.
 * This replaces the cucumber test run configuration but doesn't execute anything yet.
 */
class KarateTestRunLineMarkerProvider : LineMarkerProvider {
    
    companion object {
        private val TEST_RUN_ICON: Icon = IconLoader.getIcon("/icons/test-run.svg", KarateTestRunLineMarkerProvider::class.java)
    }
    
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process TAG tokens that are children of GherkinTag
        if (element.elementType != GherkinTokenTypes.TAG || element.parent !is GherkinTag) {
            return null
        }
        
        val tagElement = element.parent as GherkinTag
        
        // For now, let's show the icon for ALL tags to test if it works
        val tagText = element.text
        if (tagText.isBlank() || !tagText.startsWith("@")) {
            return null
        }
        
        val tooltipText = "Create and prepare TestRunner.java with tag '$tagText'"
        
        return LineMarkerInfo(
            element,
            element.textRange,
            TEST_RUN_ICON,
            { tooltipText },
            { mouseEvent, _ ->
                val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(listOf("Run Test", "Debug Test"))
                    .setTitle("Karate Test Options")
                    .setItemChosenCallback { choice ->
                        when (choice) {
                            "Run Test" -> writeTestRunInfo(tagElement, tagText, debug = false)
                            "Debug Test" -> writeTestRunInfo(tagElement, tagText, debug = true)
                        }
                    }
                    .createPopup()

                if (mouseEvent != null) {
                    val component = mouseEvent.component
                    val point = mouseEvent.point
                    val screenPoint = java.awt.Point(point)
                    javax.swing.SwingUtilities.convertPointToScreen(screenPoint, component)
                    screenPoint.y += 10 // show just below the gutter icon
                    popup.showInScreenCoordinates(component, screenPoint)
                }
            },
            GutterIconRenderer.Alignment.CENTER,
            { "Click to run or debug Karate test for $tagText" }
        )


    }
    
    /**
     * Check if the given tag is followed by a scenario
     */
    private fun isTagFollowedByScenario(tagElement: GherkinTag): Boolean {
        // Find the next scenario element after this tag
        val scenario = findNextScenario(tagElement)
        return scenario != null
    }
    
    /**
     * Find the next scenario after the given tag element
     */
    private fun findNextScenario(tagElement: GherkinTag): GherkinScenario? {
        var current: PsiElement? = tagElement.nextSibling
        
        while (current != null) {
            // Skip whitespace and other tags
            if (current is GherkinScenario) {
                return current
            }
            // If we encounter another tag block or other major element, stop looking
            if (current is GherkinTag && current != tagElement) {
                break
            }
            current = current.nextSibling
        }
        
        // Try a different approach using tree traversal
        return PsiTreeUtil.getNextSiblingOfType(tagElement, GherkinScenario::class.java)
    }
    
    /**
     * Find the name of the scenario associated with this tag
     */
    private fun findAssociatedScenarioName(tagElement: GherkinTag): String? {
        val scenario = findNextScenario(tagElement)
        return scenario?.scenarioName
    }
    
    /**
     * Write test run information to TestRunner.java file and execute the test
     */
    private fun writeTestRunInfo(tagElement: GherkinTag, tagText: String, debug: Boolean = false) {
        // Collect all PSI-related data immediately while we're in the correct thread context
        val project = tagElement.project
        val psiFile = tagElement.containingFile
        val virtualFile = psiFile?.virtualFile
        
        if (virtualFile == null) {
            println("Error: Could not get virtual file")
            return
        }
        
        // Get the path from source root and project base path immediately
        val filePath = getPathFromSourceRoot(project, virtualFile)
        val projectBasePath = project.basePath
        
        // Get the configurable test runner file path from settings
        val settings = KarateTestRunnerSettings.getInstance(project)
        val configuredPath = settings.testRunnerFilePath
        
        // Now run the file writing in a background thread with all data collected
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (projectBasePath != null) {
                    val testRunnerFile = File(projectBasePath, configuredPath)
                    
                    // Create the directory structure if it doesn't exist
                    testRunnerFile.parentFile?.mkdirs()
                    
                    // Extract package name from the configured path
                    val packageName = extractPackageFromPath(configuredPath)
                    val className = testRunnerFile.nameWithoutExtension
                    
                    // TestRunner.java template content
                    val template = if (settings.testRunnerTemplate.isNotEmpty()) {
                        settings.testRunnerTemplate
                    } else {
                        // Default template if settings is empty
                        """
package {{PACKAGE_NAME}};

import com.intuit.karate.junit5.Karate;
import org.junit.jupiter.api.BeforeAll;
import com.karate.mock.MockServerRunner;

class {{CLASS_NAME}} {
    @Karate.Test
    Karate testMockApi() {
        return Karate.run("classpath:{{FILE_PATH}}")
                .tags("{{TAG_TEXT}}")
                .karateEnv("local")
                .relativeTo(getClass());
    }
}
""".trimIndent()
                    }
                    
                    // Replace template variables with actual values
                    val testRunnerContent = template
                        .replace("{{PACKAGE_NAME}}", packageName)
                        .replace("{{CLASS_NAME}}", className)
                        .replace("{{FILE_PATH}}", filePath)
                        .replace("{{TAG_TEXT}}", tagText)
                    
                    // Write the content to the file
                    testRunnerFile.writeText(testRunnerContent)
                    
                    println("${testRunnerFile.name} created/updated with tag: $tagText and path: $filePath")
                    println("File location: ${testRunnerFile.absolutePath}")
                    
                    // Wait 1 second then run the test
                    CompletableFuture.runAsync {
                        Thread.sleep(1000) // Wait 1 second
                        
                        // Execute the test on EDT
                        ApplicationManager.getApplication().invokeLater {
                            ApplicationManager.getApplication().runReadAction {
                                runJUnitTest(project, testRunnerFile,debug)
                            }
                        }
                    }
                } else {
                    println("Error: Could not get project base path")
                }
                
            } catch (e: Exception) {
                println("Error creating TestRunner.java: ${e.message}")
            }
        }
    }
    
    /**
     * Extract package name from file path
     * e.g., "src/test/java/com/karate/runner/TestRunner.java" -> "com.karate.runner"
     */
    private fun extractPackageFromPath(filePath: String): String {
        val normalizedPath = filePath.replace('\\', '/')
        
        // Find the java directory
        val javaIndex = normalizedPath.lastIndexOf("/java/")
        if (javaIndex != -1) {
            val packagePath = normalizedPath.substring(javaIndex + 6) // Skip "/java/"
            val lastSlashIndex = packagePath.lastIndexOf('/')
            if (lastSlashIndex != -1) {
                return packagePath.substring(0, lastSlashIndex).replace('/', '.')
            }
        }
        
        // Fallback to default package
        return "com.karate.runner"
    }
    
    /**
     * Run JUnit test for the created TestRunner file
     */
    private fun runJUnitTest(project: Project, testRunnerFile: File, debug: Boolean = false) {
        try {
            // Refresh VFS to ensure new file is visible
            VirtualFileManager.getInstance().syncRefresh()

            val virtualFile = VirtualFileManager.getInstance()
                .findFileByUrl("file://${testRunnerFile.absolutePath.replace('\\', '/')}")
            if (virtualFile == null) {
                println("Error: Could not find virtual file for ${testRunnerFile.absolutePath}")
                return
            }

            // Execute on EDT
            ApplicationManager.getApplication().invokeLater {
                try {
                    val runManager = RunManager.getInstance(project)
                    val junitConfigType = JUnitConfigurationType.getInstance()
                    val junitFactory = junitConfigType.factory

                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile !is PsiJavaFile) {
                        println("Error: File is not a Java file")
                        openFileInEditor(project, virtualFile)
                        return@invokeLater
                    }

                    val testClass = psiFile.classes.firstOrNull()
                    if (testClass == null) {
                        println("Error: No class found in Java file")
                        openFileInEditor(project, virtualFile)
                        return@invokeLater
                    }

                    val junitConfig = JUnitConfiguration("Karate Test - ${testClass.name}", project, junitFactory)
                    val junitData = junitConfig.persistentData
                    junitData.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                    junitData.MAIN_CLASS_NAME = testClass.qualifiedName

                    // Add JVM parameters for Karate compatibility
                    val currentVmParams = junitData.VM_PARAMETERS ?: ""
                    val compatibilityParams = listOf(
                        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                        "--add-opens", "java.base/sun.misc=ALL-UNNAMED",
                        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                        "--add-opens", "java.base/java.util=ALL-UNNAMED",
                        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
                        "--add-opens", "java.base/sun.security.util=ALL-UNNAMED",
                        "--add-exports", "java.base/sun.misc=ALL-UNNAMED",
                        "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
                        "-Dpolyglot.js.nashorn-compat=false",
                        "-Dpolyglot.engine.AllowExperimentalOptions=true",
                        "-Dgraalvm.locatorDisabled=true"
                    ).joinToString(" ")

                    junitData.VM_PARAMETERS = if (currentVmParams.isBlank()) {
                        "-ea $compatibilityParams"
                    } else {
                        "$currentVmParams $compatibilityParams"
                    }

                    junitConfig.setShortenCommandLine(com.intellij.execution.ShortenCommandLine.ARGS_FILE)

                    val module = ReadAction.compute<com.intellij.openapi.module.Module?, Throwable> {
                        ModuleUtil.findModuleForFile(virtualFile, project)
                    }
                    if (module != null) {
                        junitConfig.setModule(module)
                    }

                    val settings = runManager.createConfiguration(junitConfig, junitFactory)
                    settings.name = "Karate Test - ${testClass.name}"

                    runManager.addConfiguration(settings)
                    runManager.selectedConfiguration = settings

                    // ⚡ Run or Debug depending on popup choice
                    val executor = if (debug)
                        com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance()
                    else
                        com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance()

                    ProgramRunnerUtil.executeConfiguration(settings, executor)

                    println(
                        if (debug)
                            "Debugging Karate Test: ${testClass.qualifiedName}"
                        else
                            "Running Karate Test: ${testClass.qualifiedName}"
                    )

                } catch (e: Exception) {
                    println("Error creating/executing JUnit config: ${e.message}")
                    e.printStackTrace()
                    openFileInEditor(project, virtualFile)
                }
            }

        } catch (e: Exception) {
            println("Error processing test file: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Open the test file in the editor as a fallback
     */
    private fun openFileInEditor(project: Project, virtualFile: VirtualFile) {
        try {
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            fileEditorManager.openFile(virtualFile, true)
            println("Opened test file in editor: ${virtualFile.path}")
        } catch (e: Exception) {
            println("Error opening file in editor: ${e.message}")
        }
    }
    
    /**
     * Get the path of the file relative to source root
     */
    private fun getPathFromSourceRoot(project: Project, virtualFile: VirtualFile): String {
        val projectRootManager = ProjectRootManager.getInstance(project)
        val sourceRoots = projectRootManager.contentSourceRoots
        
        for (sourceRoot in sourceRoots) {
            if (virtualFile.path.startsWith(sourceRoot.path)) {
                return virtualFile.path.substring(sourceRoot.path.length + 1).replace('\\', '/')
            }
        }
        
        // If not under a source root, use relative path from project base
        val projectBasePath = project.basePath
        return if (projectBasePath != null && virtualFile.path.startsWith(projectBasePath)) {
            virtualFile.path.substring(projectBasePath.length + 1).replace('\\', '/')
        } else {
            virtualFile.name
        }
    }
}
package com.mb.mbplugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(name = "KarateTestRunnerSettings", storages = [Storage("karateTestRunnerSettings.xml")])
@Service(Service.Level.PROJECT)
class KarateTestRunnerSettings : PersistentStateComponent<KarateTestRunnerSettings.State> {

    data class RunnerConfig(
        var name: String = "Default",
        var testRunnerFilePath: String = "src/test/java/com/karate/runner/TestRunner.java",
        var testRunnerTemplate: String = DEFAULT_TEMPLATE
    )

    data class State(
        var currentConfig: String = "Default",
        var configs: MutableList<RunnerConfig> = mutableListOf(RunnerConfig())
    )

    private var myState = State()

    companion object {
        const val DEFAULT_TEMPLATE = """
package {{PACKAGE_NAME}};

import com.intuit.karate.junit5.Karate;

class {{CLASS_NAME}} {
    @Karate.Test
    Karate testMockApi() {
        return Karate.run("classpath:{{FILE_PATH}}")
                .tags("{{TAG_TEXT}}")
                .karateEnv("local")
                .relativeTo(getClass());
    }
}
"""

        fun getInstance(project: Project): KarateTestRunnerSettings =
            project.service<KarateTestRunnerSettings>()
    }

    fun getCurrentConfig(): RunnerConfig =
        myState.configs.find { it.name == myState.currentConfig } ?: myState.configs.first()

    fun setCurrentConfigName(name: String) {
        myState.currentConfig = name
    }

    fun addConfig(name: String) {
        if (myState.configs.none { it.name == name })
            myState.configs.add(RunnerConfig(name))
    }

    fun removeConfig(name: String) {
        myState.configs.removeIf { it.name == name }
        if (myState.currentConfig == name)
            myState.currentConfig = myState.configs.firstOrNull()?.name ?: "Default"
    }

    override fun getState() = myState
    override fun loadState(state: State) {
        myState = state
    }
}

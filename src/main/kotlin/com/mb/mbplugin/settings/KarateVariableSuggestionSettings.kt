package com.mb.mbplugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Settings service to track whether variable suggestion is enabled
 */
@Service(Service.Level.PROJECT)
@State(
    name = "KarateVariableSuggestionSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class KarateVariableSuggestionSettings : PersistentStateComponent<KarateVariableSuggestionSettings.State> {
    
    data class State(
        var enabled: Boolean = false,
        var patterns: MutableList<String> = mutableListOf(
            "^\\s*(?:\\*|Given|When|Then|And|But)\\s+def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=(?!\\s*\\{)",
            "^\\s*(?:\\*|Given|When|Then|And|But)\\s+set\\s+([a-zA-Z_][a-zA-Z0-9_.]+)\\s*=",
            "^\\s*(?:\\*|Given|When|Then|And|But)\\s+create\\s+variable\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+with\\s+value",
            "^\\s*(?:\\*|Given|When|Then|And|But)\\s+def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*\\{",
            "karate\\.set\\s*\\(\\s*['\"]([a-zA-Z_][a-zA-Z0-9_]*)['\"]\\s*,"
        ),
        var sourceFiles: MutableList<String> = mutableListOf()
    )
    
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }
    
    var isEnabled: Boolean
        get() = myState.enabled
        set(value) {
            myState.enabled = value
        }
    
    var patterns: MutableList<String>
        get() = myState.patterns
        set(value) {
            myState.patterns = value
        }

    var sourceFiles: MutableList<String>
        get() = myState.sourceFiles
        set(value) {
            myState.sourceFiles = value
        }
    
    companion object {
        fun getInstance(project: Project): KarateVariableSuggestionSettings =
            project.getService(KarateVariableSuggestionSettings::class.java)
    }
}

package com.mb.mbplugin.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "JiraSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class JiraSettings : PersistentStateComponent<JiraSettings.State> {
    
    data class State(
        var jiraBaseUrl: String = "https://jira.mbbank.com.vn/",
        var jiraProjectPatterns: String = "MBA-*,BIZ-*,MS-*"
    )
    
    private var myState = State()
    
    var jiraBaseUrl: String
        get() = myState.jiraBaseUrl
        set(value) {
            myState.jiraBaseUrl = value
        }
    
    var jiraProjectPatterns: String
        get() = myState.jiraProjectPatterns
        set(value) {
            myState.jiraProjectPatterns = value
        }
        
    /**
     * Parses the jiraProjectPatterns string into a list of project prefixes.
     * Expected format: "MBA-*, MATS-*, ABC-*" (comma-separated)
     * Returns list of project prefixes without the asterisk: ["MBA-", "MATS-", "ABC-"]
     */
    fun getProjectPrefixes(): List<String> {
        // If no patterns are set, return an empty list
        if (jiraProjectPatterns.isBlank()) {
            return emptyList()
        }
        
        return jiraProjectPatterns
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith("*")) it.dropLast(1) else it }
    }
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }
    
    companion object {
        fun getInstance(project: Project): JiraSettings = 
            project.service<JiraSettings>()
    }
}
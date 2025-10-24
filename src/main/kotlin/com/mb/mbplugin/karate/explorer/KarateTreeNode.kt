package com.mb.mbplugin.karate.explorer

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Base class for tree nodes in the Karate Tree Explorer
 */
sealed class KarateTreeNode(
    val displayName: String,
    val psiElement: PsiElement?,
    val icon: Icon?
) {
    override fun toString(): String = displayName
}

/**
 * Root node representing the entire feature file
 */
class FeatureNode(
    displayName: String,
    psiElement: PsiElement?
) : KarateTreeNode(displayName, psiElement, AllIcons.FileTypes.Any_type)

/**
 * Node representing a scenario in a feature file
 */
class ScenarioNode(
    displayName: String,
    psiElement: PsiElement?,
    val tag: String?
) : KarateTreeNode(displayName, psiElement, AllIcons.Nodes.Method)

/**
 * Node representing a step in a scenario
 */
class StepNode(
    displayName: String,
    psiElement: PsiElement?,
    val stepText: String
) : KarateTreeNode(displayName, psiElement, AllIcons.Nodes.Property)

/**
 * Node representing a call read statement
 */
class CallReadNode(
    displayName: String,
    psiElement: PsiElement?,
    val featurePath: String,
    val scenarioTag: String?,
    val fullCallText: String
) : KarateTreeNode(displayName, psiElement, AllIcons.Actions.Download)

/**
 * Node representing a loading placeholder
 */
class LoadingNode(
    displayName: String = "Loading..."
) : KarateTreeNode(displayName, null, AllIcons.Process.Step_1)

/**
 * Node representing an error or missing reference
 */
class ErrorNode(
    displayName: String,
    val errorMessage: String
) : KarateTreeNode(displayName, null, AllIcons.General.Error)

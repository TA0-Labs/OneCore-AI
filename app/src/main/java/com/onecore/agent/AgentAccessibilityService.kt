package com.onecore.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun dumpScreenHierarchy(): String {
        val root = rootInActiveWindow ?: return "Erreur: Aucun écran actif accessible."
        val sb = StringBuilder()
        traverseNode(root, sb, 0)
        return sb.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.substringAfter("/") ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            sb.append("$indent[${node.className?.toString()?.substringAfterLast('.')}] ")
            if (text.isNotEmpty()) sb.append("text=\"$text\" ")
            if (desc.isNotEmpty()) sb.append("desc=\"$desc\" ")
            if (viewId.isNotEmpty()) sb.append("id=\"$viewId\" ")
            sb.append("clickable=${node.isClickable} bounds=(${bounds.centerX()},${bounds.centerY()})\n")
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), sb, depth + 1)
        }
    }

    fun clickByText(targetText: String): String {
        val root = rootInActiveWindow ?: return "Écran inaccessible"
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return "Succès: Clic effectué sur '$targetText'"
                }
                current = current.parent
            }
        }
        return "Élément '$targetText' introuvable ou non cliquable."
    }

    fun tapCoordinates(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
        return "Geste tactile envoyé en ($x, $y)"
    }
}

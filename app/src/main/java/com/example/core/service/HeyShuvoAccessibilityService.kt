package com.example.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Enables Hey Shuvo hands-free voice actions across ALL displays and third-party apps:
 * Back, Home, Recents, Notifications, Quick Settings, Scroll Up/Down, and tapping on-screen buttons by label.
 */
class HeyShuvoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-light: only acts when user explicitly issues a voice command.
    }

    override fun onInterrupt() {
        // No continuous feedback stream to interrupt.
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    fun performGlobalNav(action: Int): Boolean {
        return try {
            performGlobalAction(action)
        } catch (_: Exception) {
            false
        }
    }

    fun scrollCurrentWindow(down: Boolean): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrolledNode = findAndScrollNode(root, down)
            if (scrolledNode) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dm = resources.displayMetrics
            val cx = dm.widthPixels * 0.5f
            val startY = if (down) dm.heightPixels * 0.74f else dm.heightPixels * 0.28f
            val endY = if (down) dm.heightPixels * 0.26f else dm.heightPixels * 0.74f
            val swipePath = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0L, 280L))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findAndScrollNode(node: AccessibilityNodeInfo, down: Boolean): Boolean {
        if (node.isScrollable) {
            val action = if (down) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndScrollNode(child, down)) return true
        }
        return false
    }

    fun clickOnScreenByText(query: String): Boolean {
        val clean = query.trim()
        if (clean.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val directMatches = root.findAccessibilityNodeInfosByText(clean).orEmpty()
        for (candidate in directMatches) {
            if (performClickUpHierarchy(candidate)) return true
        }
        return searchAndClickRecursive(root, clean.lowercase())
    }

    private fun searchAndClickRecursive(node: AccessibilityNodeInfo, queryLower: String): Boolean {
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if ((text.isNotBlank() && queryLower in text) || (desc.isNotBlank() && queryLower in desc)) {
            if (performClickUpHierarchy(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClickRecursive(child, queryLower)) return true
        }
        return false
    }

    private fun performClickUpHierarchy(start: AccessibilityNodeInfo?): Boolean {
        var cur = start
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    companion object {
        @Volatile
        var activeInstance: HeyShuvoAccessibilityService? = null
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val expected = "${context.packageName}/${HeyShuvoAccessibilityService::class.java.name}"
                val expectedShort = "${context.packageName}/.core.service.HeyShuvoAccessibilityService"
                enabledServices.contains(expected, ignoreCase = true) ||
                    enabledServices.contains(expectedShort, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }
}

package com.example.timelinter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

class BrowserUrlAccessibilityService : AccessibilityService() {
    
    companion object {
        private val currentHostname = AtomicReference<String?>()
        
        fun getCurrentHostname(): String? {
            return currentHostname.get()
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = arrayOf(
                "com.android.chrome",
                "com.chrome.beta", 
                "com.chrome.dev",
                "com.chrome.canary",
                "org.mozilla.firefox",
                "com.opera.browser",
                "com.microsoft.emmx",
                "com.brave.browser",
                "com.duckduckgo.mobile.android",
                "com.sec.android.app.sbrowser",
                "com.UCMobile.intl",
                "com.uc.browser.en",
                "com.tencent.mtt",
                "com.baidu.browser.apps",
                "com.qihoo.browser",
                "com.ksmobile.cb",
                "com.samsung.android.app.sbrowser",
                "com.samsung.android.sbrowser"
            )
        }
        serviceInfo = info
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { accessibilityEvent ->
            when (accessibilityEvent.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    extractUrlFromNode(rootInActiveWindow)
                }
            }
        }
    }
    
    private fun extractUrlFromNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        
        // Look for URL in various possible locations
        val url = findUrlInNode(node)
        if (url != null) {
            val hostname = extractHostname(url)
            if (hostname != null) {
                currentHostname.set(hostname)
            }
        }
        
        // Recursively search child nodes
        for (i in 0 until node.childCount) {
            extractUrlFromNode(node.getChild(i))
        }
    }
    
    private fun findUrlInNode(node: AccessibilityNodeInfo): String? {
        // Check content description
        node.contentDescription?.let { content ->
            val contentStr = content.toString()
            if (isValidUrl(contentStr)) return contentStr
        }
        
        // Check text
        node.text?.let { text ->
            val textStr = text.toString()
            if (isValidUrl(textStr)) return textStr
        }
        
        // Check hint text
        node.hintText?.let { hint ->
            val hintStr = hint.toString()
            if (isValidUrl(hintStr)) return hintStr
        }
        
        return null
    }
    
    private fun isValidUrl(text: String): Boolean {
        return text.startsWith("http://") || text.startsWith("https://") || 
               text.contains(".") && (text.contains("www.") || text.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")))
    }
    
    private fun extractHostname(url: String): String? {
        return try {
            val cleanUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                "https://$url"
            }
            
            val uri = java.net.URI(cleanUrl)
            uri.host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onInterrupt() {
        // Service interrupted
    }
}

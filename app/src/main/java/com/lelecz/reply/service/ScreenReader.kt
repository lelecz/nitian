package com.lelecz.reply.service

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 屏幕直读：遍历无障碍控件树，提取当前聊天界面的消息上下文。
 * 优先找 RecyclerView / ListView（消息列表），收集其文本节点。
 */
object ScreenReader {

    /** 提取到的聊天上下文 */
    data class ChatSnapshot(
        val packageName: String,
        val lines: List<String>,   // 从上到下：旧 → 新
        val latest: String
    )

    /** 支持的聊天软件包名关键词 */
    private val CHAT_PACKAGES = listOf(
        "mobileqq",        // QQ
        "tencent.mobileqq", // QQ 国际版/其他
        "weixin",          // 微信
        "wechat",          // 微信海外版
        "aweme",           // 抖音
        "ugc.aweme",       // 抖音
        "gifmaker",        // 快手
        "kuaishou"         // 快手其他版本
    )

    /** 判断是否为支持的聊天软件包名（公开，供 Service 层复用） */
    fun isChatPackage(pkg: String): Boolean {
        // 排除 QQ 音乐等非聊天应用
        if (pkg.contains("qqmusic", true)) return false
        return CHAT_PACKAGES.any { pkg.contains(it, true) }
    }

    /**
     * 从无障碍根节点提取聊天上下文。
     * @return null = 当前不是可识别的聊天界面（无消息列表）
     */
    fun readChat(root: AccessibilityNodeInfo): ChatSnapshot? {
        val pkg = root.packageName?.toString() ?: return null
        if (!isChatPackage(pkg)) return null

        // 找消息列表容器
        val list = findListContainer(root) ?: return null

        val lines = mutableListOf<String>()
        collectText(list, lines, depth = 0)

        // 清洗：去空、去过短、去输入框占位符
        val cleaned = lines
            .map { it.trim() }
            .filter { it.length >= 2 && !it.contains("输入") && !it.contains("发消息") }
            .distinct()

        if (cleaned.isEmpty()) return null

        val latest = cleaned.last()
        return ChatSnapshot(pkg, cleaned.takeLast(10), latest)
    }

    private fun findListContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 优先找滚动容器（RecyclerView/ListView）
        val scroll = findNode(root) { n ->
            val cn = n.className?.toString() ?: ""
            (cn.contains("RecyclerView", true) || cn.contains("ListView", true) ||
                n.isScrollable) &&
                n.childCount > 0
        }
        if (scroll != null) return scroll

        // 兜底：找文本最多的容器
        return findNode(root) { n -> n.childCount > 3 && n.text == null }
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, pred)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank()) {
            out.add(t)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out, depth + 1)
            child.recycle()
        }
    }
}

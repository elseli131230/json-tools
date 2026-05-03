package com.cn.`else`.jsontools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.text.DefaultHighlighter
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.JScrollBar

/**
 * JSON Tools 主面板，3 个独立 tab：
 *  - 格式化（[buildFormatTab]）：单文本区 + 格式化 / 复制 / 清空 + 树形视图切换
 *  - JSON 对比（[buildCompareTab]）：左右两侧 JTextPane + 对比 / 上一处 / 下一处 / 交换 / 清空
 *
 * 性能要点：
 * - 单框用 [JBTextArea]（PlainDocument）而非 JTextPane，避免样式文档在大文本下的卡顿。
 * - JTextPane 粘贴：在后台线程构建 [DefaultStyledDocument]，EDT 仅做引用替换，消除粘贴卡顿。
 * - diff 渲染：一次性插入全文 + 按段 setCharacterAttributes，避免逐行 insertString。
 * - 格式化 / 对比 / 树重建都在后台线程跑，UI 只在最后一次性刷新。
 * - 格式化与对比标签均提供查找栏：在关键词输入框按 Enter / Shift+Enter 或按钮，选中匹配并滚动到可见。
 *
 * 树展开/折叠：每次按钮点击都基于"当前实际可见深度"重新计算下一步，不依赖外部状态字段，
 * 因此用户手动展开/折叠某些节点后，「展开一层 / 折叠一层」也能继续正常工作。
 */
class JsonToolsPanel {
    private fun msg(key: String, vararg params: Any): String = JsonToolsBundle.message(key, *params)
    private data class LanguageOption(val locale: Locale?, val text: String) {
        override fun toString(): String = text
    }

    // ---------------- 输入区 ----------------

    private val singlePane: JBTextArea = createJsonTextArea()
    // 对比输入区：用 JBTextArea，保证大 JSON 粘贴速度
    private val leftInputPane: JBTextArea = createJsonTextArea()
    private val rightInputPane: JBTextArea = createJsonTextArea()
    // 对比结果区：用 JTextPane，仅在点击“对比”后展示高亮结果
    private val leftDiffPane: JTextPane = createJsonPane().apply { isEditable = false }
    private val rightDiffPane: JTextPane = createJsonPane().apply { isEditable = false }

    // ---------------- 状态栏 ----------------

    private val singleStatus = JBLabel(" ")
    private val leftStatus = JBLabel(" ")
    private val rightStatus = JBLabel(" ")
    private val compareStatus = JBLabel(" ")
    private val treeStatus = JBLabel(" ")

    // ---------------- 查找（格式化 / 对比） ----------------

    private val formatFindField = JBTextField().apply { columns = 20 }
    private val formatFindCase = JBCheckBox()
    private val compareFindField = JBTextField().apply { columns = 20 }
    private val compareFindCase = JBCheckBox()
    /** 对比页最近一次获得焦点的文本区（输入或 diff 结果）。 */
    private var lastFocusedCompareText: JTextComponent = leftInputPane

    // ---------------- 树形视图状态 ----------------

    private val jsonTree = JTree(DefaultMutableTreeNode(msg("tree.empty"))).apply {
        // root 隐藏：JSON 顶层容器本身没必要单独展示一行；用户看到的就是顶层 key/索引
        isRootVisible = false
        showsRootHandles = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        cellRenderer = JsonTreeCellRenderer()
    }
    /** 树整体的最大深度（叶子相对根的深度），用于计算「全部展开」目标。 */
    private var treeMaxDepth = 0
    /** 上一次成功构建树时使用的源文本；用于切回 tab 时的避重做。null 表示需要重建。 */
    private var lastTreeSource: String? = null

    // ---------------- 对比 / Diff 状态 ----------------

    private data class TaggedRange(val start: Int, val end: Int)
    private data class DiffEvent(val leftRange: TaggedRange?, val rightRange: TaggedRange?)
    private data class RenderResult(
        val taggedRanges: List<Pair<JsonDiffer.Tag, TaggedRange>>,
        val sameOffsets: IntArray
    )

    private var allDiffEvents: List<DiffEvent> = emptyList()
    private var leftSameOffsets: IntArray = IntArray(0)
    private var rightSameOffsets: IntArray = IntArray(0)
    private var currentDiffIdx = -1
    private var compareShowingDiff = false
    private var compareLeftCardLayout: java.awt.CardLayout? = null
    private var compareRightCardLayout: java.awt.CardLayout? = null
    private var compareLeftCardPanel: JPanel? = null
    private var compareRightCardPanel: JPanel? = null
    private val comparePrimaryButton = JButton(msg("button.compare"), AllIcons.Actions.Diff)
    private var compareLeftInputScroll: JBScrollPane? = null
    private var compareRightInputScroll: JBScrollPane? = null
    private var compareLeftDiffScroll: JBScrollPane? = null
    private var compareRightDiffScroll: JBScrollPane? = null
    private var compareScrollSyncing = false
    private var leftSelectionHighlightTag: Any? = null
    private var rightSelectionHighlightTag: Any? = null
    @Volatile private var singleFormatTicket = 0L
    @Volatile private var treeBuildTicket = 0L
    @Volatile private var compareTicket = 0L
    @Volatile private var sideFormatTicket = 0L

    // ---------------- 容器 / 后台 ----------------

    private val tabbedPane = JBTabbedPane()
    private val rootPanel = JPanel(BorderLayout())
    private val languageLabel = JBLabel("")
    private val languageCombo = JComboBox<LanguageOption>()
    private var languageComboUpdating = false
    private val formatCardLayout = java.awt.CardLayout()
    private val formatContentPanel = JPanel(formatCardLayout)
    private val formatTreeToggleButton = JButton(msg("button.treeView"), AllIcons.Actions.ShowAsTree)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "json-tools-worker").apply { isDaemon = true }
    }
    private var formatShowingTree = false

    val component: JComponent

    init {
        restoreSavedLanguagePreference()
        rebuildTabs()
        installLanguageSwitcher()
        installCompareSelectionSync()
        installCompareFindFocusTracking()
        installFindFieldShortcuts(formatFindField) { forward -> findInFormatPane(forward) }
        installFindFieldShortcuts(compareFindField) { forward -> findInComparePane(forward) }

        component = rootPanel.apply {
            add(buildGlobalToolbar(), BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
            preferredSize = Dimension(900, 600)
        }
    }

    private fun buildGlobalToolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            border = JBUI.Borders.empty(0, 4)
            languageCombo.maximumRowCount = 16
            add(languageLabel)
            add(languageCombo)
        }
    }

    private fun installLanguageSwitcher() {
        refreshLanguageControls()
        languageCombo.addActionListener {
            if (languageComboUpdating) return@addActionListener
            val selected = languageCombo.selectedItem as? LanguageOption ?: return@addActionListener
            JsonToolsBundle.setLocale(selected.locale)
            persistLanguagePreference(selected.locale)
            refreshLanguageControls()
            rebuildTabs()
            compareStatus.foreground = OK_GREEN
            compareStatus.text = msg("status.languageSwitched", selected.text)
        }
    }

    private fun restoreSavedLanguagePreference() {
        val raw = PropertiesComponent.getInstance().getValue(PREF_LANGUAGE_TAG) ?: return
        if (raw == FOLLOW_IDE_VALUE) {
            JsonToolsBundle.setLocale(null)
            return
        }
        val locale = Locale.forLanguageTag(raw)
        if (locale.language.isBlank()) {
            JsonToolsBundle.setLocale(null)
            return
        }
        JsonToolsBundle.setLocale(locale)
    }

    private fun persistLanguagePreference(locale: Locale?) {
        val value = locale?.toLanguageTag() ?: FOLLOW_IDE_VALUE
        PropertiesComponent.getInstance().setValue(PREF_LANGUAGE_TAG, value)
    }

    private fun refreshLanguageControls() {
        languageComboUpdating = true
        try {
            languageLabel.text = msg("language.label")
            val options = listOf(
                LanguageOption(null, msg("language.auto")),
                LanguageOption(Locale.SIMPLIFIED_CHINESE, msg("language.zh")),
                LanguageOption(Locale.ENGLISH, msg("language.en")),
                LanguageOption(Locale.JAPANESE, msg("language.ja")),
                LanguageOption(Locale.KOREAN, msg("language.ko")),
                LanguageOption(Locale.FRENCH, msg("language.fr")),
                LanguageOption(Locale.GERMAN, msg("language.de")),
                LanguageOption(Locale("es"), msg("language.es")),
                LanguageOption(Locale("pt"), msg("language.pt")),
                LanguageOption(Locale("ru"), msg("language.ru")),
                LanguageOption(Locale("hi"), msg("language.hi")),
                LanguageOption(Locale("tr"), msg("language.tr")),
                LanguageOption(Locale("ar"), msg("language.ar"))
            )
            languageCombo.removeAllItems()
            options.forEach { languageCombo.addItem(it) }
            val current = JsonToolsBundle.currentLocale().language
            val selectedIndex = if (!JsonToolsBundle.hasLocaleOverride()) 0
            else options.indexOfFirst { it.locale?.language == current }.takeIf { it >= 0 } ?: 0
            languageCombo.selectedIndex = selectedIndex
        } finally {
            languageComboUpdating = false
        }
    }

    private fun rebuildTabs() {
        val oldIdx = tabbedPane.selectedIndex.coerceAtLeast(0)
        tabbedPane.removeAll()
        tabbedPane.addTab(msg("tab.format"), buildFormatTab())
        tabbedPane.addTab(msg("tab.compare"), buildCompareTab())
        tabbedPane.selectedIndex = oldIdx.coerceAtMost(tabbedPane.tabCount - 1)
    }

    /**
     * 对比结果视图双击选中联动：
     * - 在左侧双击选中文本 -> 右侧自动定位并高亮同样文本
     * - 在右侧双击选中文本 -> 左侧同理
     */
    private fun installCompareSelectionSync() {
        leftDiffPane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) syncSelectionToOther(leftDiffPane, rightDiffPane)
            }
        })
        rightDiffPane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) syncSelectionToOther(rightDiffPane, leftDiffPane)
            }
        })
    }

    private fun syncSelectionToOther(source: JTextPane, target: JTextPane) {
        val sel = source.selectedText?.trim() ?: return
        if (sel.isBlank()) return
        // 太短容易误命中，避免闪烁；用户可多选几个字符再双击
        if (sel.length < 2) return

        val srcText = source.text
        val dstText = target.text
        if (dstText.isEmpty()) return

        val srcStart = source.selectionStart.coerceAtLeast(0)
        val srcRange = srcText.length.coerceAtLeast(1)
        val dstRange = dstText.length.coerceAtLeast(1)
        val anchor = ((srcStart.toDouble() / srcRange) * dstRange).toInt()
        val targetStart = findNearestOccurrence(dstText, sel, anchor) ?: return
        val targetEnd = (targetStart + sel.length).coerceAtMost(dstText.length)

        target.requestFocusInWindow()
        target.select(targetStart, targetEnd)
        applySelectionHighlight(source, source.selectionStart, source.selectionEnd)
        applySelectionHighlight(target, targetStart, targetEnd)
        val rect = target.modelToView2D(targetStart) ?: return
        val viewport = java.awt.Rectangle(
            rect.x.toInt(),
            (rect.y - 40).toInt().coerceAtLeast(0),
            rect.width.toInt().coerceAtLeast(1),
            (rect.height + 80).toInt()
        )
        target.scrollRectToVisible(viewport)
    }

    /** 在 [text] 里找 [needle]，优先返回离 [anchor] 最近的一处。 */
    private fun findNearestOccurrence(text: String, needle: String, anchor: Int): Int? {
        var idx = text.indexOf(needle)
        if (idx < 0) return null
        var best = idx
        var bestDist = kotlin.math.abs(idx - anchor)
        while (idx >= 0) {
            val d = kotlin.math.abs(idx - anchor)
            if (d < bestDist) {
                bestDist = d
                best = idx
            }
            idx = text.indexOf(needle, idx + 1)
        }
        return best
    }

    /**
     * 为联动选区加更醒目的高亮（深色主题下明显亮于默认 selection）。
     * 仅作用于对比结果的双击联动，不影响全局主题。
     */
    private fun applySelectionHighlight(pane: JTextPane, start: Int, end: Int) {
        if (end <= start) return
        val highlighter = pane.highlighter
        try {
            if (pane === leftDiffPane && leftSelectionHighlightTag != null) {
                highlighter.removeHighlight(leftSelectionHighlightTag)
                leftSelectionHighlightTag = null
            }
            if (pane === rightDiffPane && rightSelectionHighlightTag != null) {
                highlighter.removeHighlight(rightSelectionHighlightTag)
                rightSelectionHighlightTag = null
            }
            val tag = highlighter.addHighlight(start, end, BRIGHT_SELECTION_PAINTER)
            if (pane === leftDiffPane) leftSelectionHighlightTag = tag
            if (pane === rightDiffPane) rightSelectionHighlightTag = tag
        } catch (_: Exception) {
            // ignore
        }
    }

    // ============================================================
    //                       Tab 1：格式化
    // ============================================================

    private fun buildFormatTab(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JButton(msg("button.format"), AllIcons.Actions.Refresh).apply {
                toolTipText = msg("tooltip.formatCurrent")
                addActionListener {
                    if (formatShowingTree) showTextInsideFormatTab()
                    doFormatSingle()
                }
            })
            add(JButton(msg("button.copy"), AllIcons.Actions.Copy).apply {
                toolTipText = msg("tooltip.copyCurrent")
                addActionListener { copyPaneToClipboard(singlePane, singleStatus) }
            })
            add(JButton(msg("button.clear")).apply {
                addActionListener {
                    setPlainText(singlePane, "")
                    singleStatus.foreground = OK_GREEN
                    singleStatus.text = " "
                    lastTreeSource = null
                }
            })
            formatTreeToggleButton.text = msg("button.treeView")
            formatTreeToggleButton.toolTipText = msg("tooltip.switchTree")
            resetActionListeners(formatTreeToggleButton)
            formatTreeToggleButton.addActionListener { showTreeInsideFormatTab() }
            add(formatTreeToggleButton)
        }
        val topBar = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.WEST)
            singleStatus.border = JBUI.Borders.emptyLeft(12)
            singleStatus.foreground = OK_GREEN
            add(singleStatus, BorderLayout.CENTER)
            border = JBUI.Borders.empty(2, 4, 2, 4)
        }
        formatContentPanel.add(buildFormatTextCard(), FORMAT_CARD_TEXT)
        formatContentPanel.add(buildFormatTreeCard(), FORMAT_CARD_TREE)
        formatCardLayout.show(formatContentPanel, FORMAT_CARD_TEXT)
        val header = JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(buildFormatFindBar(), BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(formatContentPanel, BorderLayout.CENTER)
        }
    }

    private fun buildFormatFindBar(): JComponent {
        formatFindCase.text = msg("find.caseSensitive")
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            border = JBUI.Borders.empty(0, 4, 4, 4)
            add(JBLabel(msg("label.find")))
            add(formatFindField)
            add(JButton(msg("button.findPrev")).apply {
                margin = Insets(2, 8, 2, 8)
                toolTipText = msg("tooltip.findPrev")
                addActionListener { findInFormatPane(forward = false) }
            })
            add(JButton(msg("button.findNext")).apply {
                margin = Insets(2, 8, 2, 8)
                toolTipText = msg("tooltip.findNext")
                addActionListener { findInFormatPane(forward = true) }
            })
            add(formatFindCase)
        }
    }

    private fun buildCompareFindBar(): JComponent {
        compareFindCase.text = msg("find.caseSensitive")
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            border = JBUI.Borders.empty(0, 4, 4, 4)
            add(JBLabel(msg("label.find")))
            add(compareFindField)
            add(JButton(msg("button.findPrev")).apply {
                margin = Insets(2, 8, 2, 8)
                toolTipText = msg("tooltip.findPrev")
                addActionListener { findInComparePane(forward = false) }
            })
            add(JButton(msg("button.findNext")).apply {
                margin = Insets(2, 8, 2, 8)
                toolTipText = msg("tooltip.findNext")
                addActionListener { findInComparePane(forward = true) }
            })
            add(compareFindCase)
        }
    }

    private fun buildFormatTextCard(): JComponent {
        val scroll = wrapInScrollPane(singlePane)
        return JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun buildFormatTreeCard(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JButton(msg("button.collapseAll"), AllIcons.Actions.Collapseall).apply {
                toolTipText = msg("tooltip.collapseAll")
                addActionListener { expandToDepth(1) }
            })
            add(JButton(msg("button.collapse")).apply {
                toolTipText = msg("tooltip.collapseOne")
                addActionListener { expandToDepth(currentVisibleDepth() - 1) }
            })
            add(JButton(msg("button.expand")).apply {
                toolTipText = msg("tooltip.expandOne")
                addActionListener { expandToDepth(currentVisibleDepth() + 1) }
            })
            add(JButton(msg("button.expandAll"), AllIcons.Actions.Expandall).apply {
                toolTipText = msg("tooltip.expandAll")
                addActionListener { expandToDepth(treeMaxDepth) }
            })
        }
        val treeTools = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 6, 2, 6)
            )
            add(JBLabel(msg("label.treeTools")).apply {
                font = font.deriveFont(Font.BOLD)
                foreground = JBColor.GRAY
            }, BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
        }
        val scroll = JBScrollPane(jsonTree)
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(treeStatus, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(treeTools, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun showTreeInsideFormatTab() {
        autoBuildTreeFromSinglePane()
        formatCardLayout.show(formatContentPanel, FORMAT_CARD_TREE)
        formatShowingTree = true
    }

    private fun showTextInsideFormatTab() {
        formatCardLayout.show(formatContentPanel, FORMAT_CARD_TEXT)
        formatShowingTree = false
    }

    private fun doFormatSingle() {
        val myTicket = ++singleFormatTicket
        val source = singlePane.text
        if (source.isBlank()) {
            singleStatus.foreground = OK_GREEN
            singleStatus.text = msg("status.pasteJson")
            return
        }
        singleStatus.foreground = OK_GREEN
        singleStatus.text = msg("status.processing")
        worker.execute {
            val parsed = JsonFormatter.parse(source)
            val pretty = if (parsed.ok) JsonFormatter.pretty(parsed.element!!) else null
            SwingUtilities.invokeLater {
                if (myTicket != singleFormatTicket) return@invokeLater
                if (!parsed.ok) {
                    singleStatus.foreground = JBColor.RED
                    singleStatus.text = msg("status.formatError", parsed.error ?: "")
                    return@invokeLater
                }
                setPlainText(singlePane, pretty!!)
                singleStatus.foreground = OK_GREEN
                singleStatus.text = msg("status.formattedDone", pretty.length)
                // 文本变了，下次进 树形视图 标签时强制重建
                lastTreeSource = null
            }
        }
    }

    // ============================================================
    //                       Tab 2：树形视图
    // ============================================================

    /**
     * 切换到树形视图 tab 时调用：取「格式化」标签的当前文本，解析并重建 [jsonTree]。
     * 若与上次重建源文本相同则跳过（避免大 JSON 反复解析）。
     */
    private fun autoBuildTreeFromSinglePane() {
        val myTicket = ++treeBuildTicket
        val source = singlePane.text
        if (source.isBlank()) {
            jsonTree.model = DefaultTreeModel(DefaultMutableTreeNode(msg("tree.empty")))
            treeMaxDepth = 0
            treeStatus.foreground = OK_GREEN
            treeStatus.text = msg("status.treeInputHint")
            lastTreeSource = ""
            return
        }
        if (source == lastTreeSource) return // 内容未变，复用现有树

        treeStatus.foreground = OK_GREEN
        treeStatus.text = msg("status.parsing")
        val captured = source
        worker.execute {
            val parsed = JsonFormatter.parse(captured)
            SwingUtilities.invokeLater {
                if (myTicket != treeBuildTicket) return@invokeLater
                if (!parsed.ok) {
                    jsonTree.model = DefaultTreeModel(DefaultMutableTreeNode(msg("tree.parseFailed")))
                    treeMaxDepth = 0
                    treeStatus.foreground = JBColor.RED
                    treeStatus.text = msg("status.formatError", parsed.error ?: "")
                    lastTreeSource = null // 下次再试
                    return@invokeLater
                }
                val root = buildTreeNode(null, parsed.element!!)
                treeMaxDepth = calcMaxDepth(root, 0)
                jsonTree.model = DefaultTreeModel(root)
                expandToDepth(2.coerceAtMost(treeMaxDepth)) // 默认展开两层
                treeStatus.foreground = OK_GREEN
                treeStatus.text = msg("status.treeBuilt", treeMaxDepth)
                lastTreeSource = captured
            }
        }
    }

    /** 递归把 [JsonElement] 转换成 [DefaultMutableTreeNode]。 */
    private fun buildTreeNode(key: String?, element: JsonElement): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(JsonNodeData(key, element))
        when (element) {
            is JsonObject -> element.entrySet().forEach { (k, v) -> node.add(buildTreeNode(k, v)) }
            is JsonArray  -> element.forEachIndexed { i, v -> node.add(buildTreeNode("[$i]", v)) }
            else -> { /* 叶子节点 */ }
        }
        return node
    }

    /** 树最大深度：根（无论是否可见）算 0，根的直接子节点算 1，依此类推。 */
    private fun calcMaxDepth(node: DefaultMutableTreeNode, depth: Int): Int {
        if (node.childCount == 0) return depth
        return (0 until node.childCount).maxOf {
            calcMaxDepth(node.getChildAt(it) as DefaultMutableTreeNode, depth + 1)
        }
    }

    /**
     * 计算当前实际可见的深度。
     *
     * 定义：从根开始，沿着「已展开」的内部节点链路一直走，能看到的最深一层的深度。
     * 用于「展开 / 折叠」按钮：以此为基准 ±1，避免依赖外部 state 字段（用户手动操作后会失效）。
     */
    private fun currentVisibleDepth(): Int {
        val root = jsonTree.model.root as? DefaultMutableTreeNode ?: return 0
        var max = 0
        fun walk(node: DefaultMutableTreeNode, path: TreePath, depth: Int) {
            if (node.childCount == 0) return
            if (jsonTree.isExpanded(path)) {
                max = maxOf(max, depth + 1)
                for (i in 0 until node.childCount) {
                    val c = node.getChildAt(i) as DefaultMutableTreeNode
                    walk(c, path.pathByAddingChild(c), depth + 1)
                }
            }
        }
        walk(root, TreePath(root), 0)
        return max
    }

    /**
     * 把整棵树调整到「展开到指定深度 [targetDepth]」的状态：
     * - 节点深度 < targetDepth 的内部节点 → 展开
     * - 节点深度 >= targetDepth 的内部节点 → 折叠
     *
     * 实现两遍：
     * 1. 自顶向下，先展开所有应该展开的节点（这样深层节点的 path 才会因父节点已展开而被识别为有效）。
     * 2. 自底向上，再折叠应该折叠的节点（先折叠子，再折叠父，避免父折叠后子的状态被忽略）。
     */
    private fun expandToDepth(targetDepth: Int) {
        if (treeMaxDepth <= 0) return
        val depth = targetDepth.coerceIn(0, treeMaxDepth)
        val root = jsonTree.model.root as? DefaultMutableTreeNode ?: return
        val rootPath = TreePath(root)

        // 第一遍：自顶向下展开
        fun expandWalk(node: DefaultMutableTreeNode, path: TreePath, d: Int) {
            if (node.childCount == 0) return
            if (d < depth) {
                if (!jsonTree.isExpanded(path)) jsonTree.expandPath(path)
                for (i in 0 until node.childCount) {
                    val c = node.getChildAt(i) as DefaultMutableTreeNode
                    expandWalk(c, path.pathByAddingChild(c), d + 1)
                }
            }
        }
        // 第二遍：自底向上折叠
        fun collapseWalk(node: DefaultMutableTreeNode, path: TreePath, d: Int) {
            if (node.childCount == 0) return
            for (i in 0 until node.childCount) {
                val c = node.getChildAt(i) as DefaultMutableTreeNode
                collapseWalk(c, path.pathByAddingChild(c), d + 1)
            }
            if (d >= depth) {
                if (jsonTree.isExpanded(path)) jsonTree.collapsePath(path)
            }
        }
        expandWalk(root, rootPath, 0)
        collapseWalk(root, rootPath, 0)

        treeStatus.foreground = OK_GREEN
        treeStatus.text = when {
            depth == 0            -> msg("status.collapsedAll")
            depth >= treeMaxDepth -> msg("status.expandedAll", treeMaxDepth)
            else                  -> msg("status.expandedDepth", depth, treeMaxDepth)
        }
    }

    // ============================================================
    //                       Tab 3：JSON 对比
    // ============================================================

    private fun buildCompareTab(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            comparePrimaryButton.text = if (compareShowingDiff) msg("button.returnEdit") else msg("button.compare")
            comparePrimaryButton.icon = if (compareShowingDiff) AllIcons.Actions.Edit else AllIcons.Actions.Diff
            comparePrimaryButton.toolTipText = if (compareShowingDiff) msg("button.returnEdit") else msg("tooltip.compareSemantic")
            resetActionListeners(comparePrimaryButton)
            comparePrimaryButton.addActionListener {
                if (compareShowingDiff) showCompareInputMode() else doCompare()
            }
            add(comparePrimaryButton)
            add(JButton(msg("button.previous"), AllIcons.Actions.PreviousOccurence).apply {
                addActionListener { navigateDiff(forward = false) }
            })
            add(JButton(msg("button.next"), AllIcons.Actions.NextOccurence).apply {
                addActionListener { navigateDiff(forward = true) }
            })
            add(JButton(msg("button.swap")).apply {
                addActionListener { swapSides() }
            })
            add(JButton(msg("button.clear")).apply {
                addActionListener { clearCompare() }
            })
        }
        val topBar = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.WEST)
            compareStatus.border = JBUI.Borders.emptyLeft(12)
            add(compareStatus, BorderLayout.CENTER)
            border = JBUI.Borders.empty(2, 4, 2, 4)
        }
        val compareHeader = JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(buildCompareFindBar(), BorderLayout.SOUTH)
        }
        val left = buildCompareSide(msg("label.leftJson"), leftInputPane, leftDiffPane, leftStatus)
        val right = buildCompareSide(msg("label.rightJson"), rightInputPane, rightDiffPane, rightStatus)
        val splitter = JBSplitter(false, "com.cn.else.jsontools.splitter", 0.5f).apply {
            firstComponent = left
            secondComponent = right
            dividerWidth = 9
            setHonorComponentsMinimumSize(false)
        }
        // 两种视图都联动滚动：输入区联动 + 结果区联动
        installCompareScrollSync()
        return JPanel(BorderLayout()).apply {
            add(compareHeader, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun buildCompareSide(
        title: String,
        inputPane: JBTextArea,
        diffPane: JTextPane,
        status: JBLabel
    ): JComponent {
        val sideCardLayout = java.awt.CardLayout()
        val sideCardPanel = JPanel(sideCardLayout)
        val inputScroll = wrapInScrollPane(inputPane)
        val diffScroll = wrapInScrollPane(diffPane)
        sideCardPanel.add(inputScroll, COMPARE_CARD_INPUT)
        sideCardPanel.add(diffScroll, COMPARE_CARD_DIFF)
        if (inputPane === leftInputPane) {
            compareLeftCardLayout = sideCardLayout
            compareLeftCardPanel = sideCardPanel
            compareLeftInputScroll = inputScroll
            compareLeftDiffScroll = diffScroll
        } else {
            compareRightCardLayout = sideCardLayout
            compareRightCardPanel = sideCardPanel
            compareRightInputScroll = inputScroll
            compareRightDiffScroll = diffScroll
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) })
            add(JButton(msg("button.format"), AllIcons.Actions.Refresh).apply {
                toolTipText = msg("tooltip.formatSide")
                margin = Insets(2, 6, 2, 6)
                isFocusable = false
                addActionListener { formatPaneInPlace(inputPane, status) }
            })
            add(JButton(msg("button.copy"), AllIcons.Actions.Copy).apply {
                margin = Insets(2, 6, 2, 6)
                isFocusable = false
                addActionListener {
                    val source = if (compareShowingDiff) diffPane else inputPane
                    copyPaneToClipboard(source, status)
                }
            })
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(status, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(sideCardPanel, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
            minimumSize = Dimension(50, 0)
        }
    }

    private fun doCompare() {
        val myTicket = ++compareTicket
        val leftText = leftInputPane.text
        val rightText = rightInputPane.text
        if (leftText.isBlank() || rightText.isBlank()) {
            compareStatus.foreground = OK_GREEN
            compareStatus.text = msg("status.pasteBothBeforeCompare")
            return
        }
        leftStatus.foreground = OK_GREEN; rightStatus.foreground = OK_GREEN
        leftStatus.text = " "; rightStatus.text = " "
        compareStatus.foreground = OK_GREEN
        compareStatus.text = msg("status.processingCompare")

        worker.execute {
            val lp = JsonFormatter.parse(leftText)
            val rp = JsonFormatter.parse(rightText)
            val diff = if (lp.ok && rp.ok) JsonDiffer.diff(lp.element!!, rp.element!!) else null
            SwingUtilities.invokeLater {
                if (myTicket != compareTicket) return@invokeLater
                if (!lp.ok) {
                    leftStatus.foreground = JBColor.RED
                    leftStatus.text = msg("status.formatError", lp.error ?: "")
                }
                if (!rp.ok) {
                    rightStatus.foreground = JBColor.RED
                    rightStatus.text = msg("status.formatError", rp.error ?: "")
                }
                if (diff == null) {
                    clearDiffRanges()
                    compareStatus.foreground = JBColor.RED
                    compareStatus.text = msg("status.fixJsonBeforeCompare")
                    return@invokeLater
                }
                leftStatus.foreground = OK_GREEN
                rightStatus.foreground = OK_GREEN
                val leftResult = renderDiff(leftDiffPane, diff.leftLines)
                val rightResult = renderDiff(rightDiffPane, diff.rightLines)

                // 构建统一 DiffEvent 列表：CHANGED 一一配对，REMOVED/ADDED 单侧
                val leftRemoved  = leftResult.taggedRanges.filter { it.first == JsonDiffer.Tag.REMOVED }.map { it.second }
                val leftChanged  = leftResult.taggedRanges.filter { it.first == JsonDiffer.Tag.CHANGED }.map { it.second }
                val rightAdded   = rightResult.taggedRanges.filter { it.first == JsonDiffer.Tag.ADDED }.map { it.second }
                val rightChanged = rightResult.taggedRanges.filter { it.first == JsonDiffer.Tag.CHANGED }.map { it.second }
                val events = mutableListOf<DiffEvent>()
                val pairs = minOf(leftChanged.size, rightChanged.size)
                repeat(pairs) { i -> events += DiffEvent(leftChanged[i], rightChanged[i]) }
                leftChanged.drop(pairs).forEach  { events += DiffEvent(it, null) }
                rightChanged.drop(pairs).forEach { events += DiffEvent(null, it) }
                leftRemoved.forEach { events += DiffEvent(it, null) }
                rightAdded.forEach  { events += DiffEvent(null, it) }
                allDiffEvents = events
                leftSameOffsets = leftResult.sameOffsets
                rightSameOffsets = rightResult.sameOffsets
                currentDiffIdx = -1

                if (!diff.hasDiff) {
                    compareStatus.foreground = OK_GREEN
                    compareStatus.text = msg("status.sameJson")
                } else {
                    val total = diff.addedCount + diff.removedCount + diff.changedCount
                    compareStatus.foreground = OK_GREEN
                    compareStatus.text = msg(
                        "status.diffCount",
                        total,
                        diff.addedCount,
                        diff.removedCount,
                        diff.changedCount
                    )
                }
                // 对比完成后直接进入结果视图；主按钮切换为“返回编辑”。
                showCompareDiffMode()
                // 有差异时默认跳到第一处（navigateDiff：currentDiffIdx 从 -1 前进即为 0）。
                if (diff.hasDiff && allDiffEvents.isNotEmpty()) {
                    navigateDiff(forward = true)
                }
            }
        }
    }

    private fun swapSides() {
        val l = leftInputPane.text
        val r = rightInputPane.text
        setPlainText(leftInputPane, r)
        setPlainText(rightInputPane, l)
        leftStatus.foreground = OK_GREEN
        rightStatus.foreground = OK_GREEN
        leftStatus.text = " "; rightStatus.text = " "
        clearDiffRanges()
        showCompareInputMode()
        compareStatus.foreground = OK_GREEN
        compareStatus.text = " "
    }

    private fun clearCompare() {
        setPlainText(leftInputPane, "")
        setPlainText(rightInputPane, "")
        setPlainText(leftDiffPane, "")
        setPlainText(rightDiffPane, "")
        leftStatus.foreground = OK_GREEN
        rightStatus.foreground = OK_GREEN
        leftStatus.text = " "; rightStatus.text = " "
        clearDiffRanges()
        showCompareInputMode()
        compareStatus.foreground = OK_GREEN
        compareStatus.text = " "
    }

    /**
     * 循环遍历 [allDiffEvents]：[forward]=true 跳下一处，false 跳上一处。
     * [currentDiffIdx] 始终指向"上次展示的事件"，-1 表示未开始。
     */
    private fun navigateDiff(forward: Boolean) {
        if (allDiffEvents.isEmpty()) {
            compareStatus.foreground = OK_GREEN
            compareStatus.text = msg("status.noDiff")
            return
        }
        val total = allDiffEvents.size
        currentDiffIdx = when {
            forward            -> (currentDiffIdx + 1).mod(total)
            currentDiffIdx < 0 -> total - 1
            else               -> (currentDiffIdx - 1 + total).mod(total)
        }
        val event = allDiffEvents[currentDiffIdx]
        if (!compareShowingDiff) showCompareDiffMode()
        event.leftRange?.let { scrollToRange(leftDiffPane, it) }
            ?: event.rightRange?.let { syncOtherPane(sourceIsLeft = false, sourceOffset = it.start) }
        event.rightRange?.let { scrollToRange(rightDiffPane, it) }
            ?: event.leftRange?.let { syncOtherPane(sourceIsLeft = true, sourceOffset = it.start) }
        compareStatus.foreground = OK_GREEN
        compareStatus.text = msg("status.diffProgress", currentDiffIdx + 1, total)
    }

    private fun showCompareDiffMode() {
        compareLeftCardLayout?.show(compareLeftCardPanel, COMPARE_CARD_DIFF)
        compareRightCardLayout?.show(compareRightCardPanel, COMPARE_CARD_DIFF)
        compareShowingDiff = true
        comparePrimaryButton.text = msg("button.returnEdit")
        comparePrimaryButton.icon = AllIcons.Actions.Edit
        comparePrimaryButton.toolTipText = msg("button.returnEdit")
    }

    private fun showCompareInputMode() {
        compareLeftCardLayout?.show(compareLeftCardPanel, COMPARE_CARD_INPUT)
        compareRightCardLayout?.show(compareRightCardPanel, COMPARE_CARD_INPUT)
        compareShowingDiff = false
        comparePrimaryButton.text = msg("button.compare")
        comparePrimaryButton.icon = AllIcons.Actions.Diff
        comparePrimaryButton.toolTipText = msg("tooltip.compareSemantic")
    }

    /** 对比页左右滚动条联动（双向），按比例同步，避免内容高度不同导致偏移过大。 */
    private fun installCompareScrollSync() {
        val li = compareLeftInputScroll?.verticalScrollBar
        val ri = compareRightInputScroll?.verticalScrollBar
        val ld = compareLeftDiffScroll?.verticalScrollBar
        val rd = compareRightDiffScroll?.verticalScrollBar
        if (li != null && ri != null) bindScrollbarsBidirectional(li, ri)
        if (ld != null && rd != null) bindScrollbarsBidirectional(ld, rd)
    }

    private fun bindScrollbarsBidirectional(a: JScrollBar, b: JScrollBar) {
        a.addAdjustmentListener { syncScrollByRatio(a, b) }
        b.addAdjustmentListener { syncScrollByRatio(b, a) }
    }

    private fun syncScrollByRatio(src: JScrollBar, dst: JScrollBar) {
        if (compareScrollSyncing) return
        compareScrollSyncing = true
        try {
            val srcRange = (src.maximum - src.visibleAmount).coerceAtLeast(1)
            val dstRange = (dst.maximum - dst.visibleAmount).coerceAtLeast(1)
            val ratio = src.value.toDouble() / srcRange.toDouble()
            val target = (ratio * dstRange).toInt().coerceIn(0, dstRange)
            if (dst.value != target) dst.value = target
        } finally {
            compareScrollSyncing = false
        }
    }

    private fun clearDiffRanges() {
        allDiffEvents = emptyList()
        leftSameOffsets = IntArray(0)
        rightSameOffsets = IntArray(0)
        currentDiffIdx = -1
    }

    // ============================================================
    //                       共享：复制 / 格式化 / 文本工具
    // ============================================================

    private fun formatPaneInPlace(pane: JTextComponent, status: JBLabel) {
        val myTicket = ++sideFormatTicket
        val source = pane.text
        if (source.isBlank()) {
            status.foreground = OK_GREEN
            status.text = msg("status.pasteJson")
            return
        }
        status.foreground = OK_GREEN
        status.text = msg("status.processing")
        worker.execute {
            val parsed = JsonFormatter.parse(source)
            val pretty = if (parsed.ok) JsonFormatter.pretty(parsed.element!!) else null
            // 关键优化：JTextPane 的文档构建（insertString 大文本）放后台线程做，
            // EDT 只做 document 引用替换，避免对比页签点击“格式化”时主线程卡顿。
            val preparedDoc = if (parsed.ok && pane is JTextPane && pretty != null) {
                buildDoc(pretty, pane.background)
            } else null
            SwingUtilities.invokeLater {
                if (myTicket != sideFormatTicket) return@invokeLater
                if (!parsed.ok) {
                    status.foreground = JBColor.RED
                    status.text = msg("status.formatError", parsed.error ?: "")
                    return@invokeLater
                }
                val formatted = pretty!!
                if (pane is JTextPane && preparedDoc != null) {
                    pane.document = preparedDoc
                    pane.caretPosition = 0
                } else {
                    setPlainText(pane, formatted)
                }
                status.foreground = OK_GREEN
                status.text = msg("status.formattedSide", formatted.length)
                clearDiffRanges()
            }
        }
    }

    private fun copyPaneToClipboard(pane: JTextComponent, status: JBLabel) {
        val text = pane.text
        if (text.isEmpty()) {
            status.foreground = OK_GREEN
            status.text = msg("status.noContentCopy")
            return
        }
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            status.foreground = OK_GREEN
            status.text = msg("status.copiedCount", text.length)
        } catch (ex: Exception) {
            status.foreground = OK_GREEN
            status.text = msg("status.copyFailed", ex.message ?: ex.javaClass.simpleName)
        }
    }

    private fun resetActionListeners(button: JButton) {
        button.actionListeners.forEach { button.removeActionListener(it) }
    }

    /**
     * 对 [JTextPane]：离屏构建新 [DefaultStyledDocument]，再整体替换，只触发一次 property change。
     * 对 [JBTextArea]：直接 setText（PlainDocument 本身已够快）。
     */
    private fun setPlainText(pane: JTextComponent, text: String) {
        if (pane is JTextPane) {
            pane.document = buildDoc(text, pane.background)
        } else {
            pane.text = text
        }
        pane.caretPosition = 0
    }

    /** 在调用方线程构建一个未附加到任何组件的 [DefaultStyledDocument]，可安全在后台线程执行。 */
    private fun buildDoc(text: String, bg: Color): DefaultStyledDocument {
        val doc = DefaultStyledDocument()
        val attr = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, JBColor.foreground())
            StyleConstants.setBackground(this, bg)
        }
        try { if (text.isNotEmpty()) doc.insertString(0, text, attr) } catch (_: Exception) {}
        return doc
    }

    /** 包进 [JBScrollPane]；点击 viewport 空白处也能把焦点落回 pane，光标到末尾。 */
    private fun wrapInScrollPane(pane: JTextComponent): JBScrollPane {
        val scroll = JBScrollPane(pane)
        scroll.viewport.background = pane.background
        scroll.viewport.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!pane.isEditable) return
                pane.requestFocusInWindow()
                pane.caretPosition = pane.document.length
            }
        })
        return scroll
    }

    /** 单框用 [JBTextArea]：纯文本 PlainDocument，大 JSON 下的插入/编辑都比 JTextPane 快很多。 */
    private fun createJsonTextArea(): JBTextArea {
        return JBTextArea().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            isEditable = true
            lineWrap = false
            tabSize = 2
        }
    }

    /** diff 展示用 [JTextPane]：需要分段上色。重写 getScrollableTracksViewportWidth 避免随宽度换行。 */
    private fun createJsonPane(): JTextPane {
        return object : JTextPane() {
            override fun getScrollableTracksViewportWidth(): Boolean = false
        }.apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            isEditable = true
            // 默认选中（鼠标拖选/双击）颜色：明显但不刺眼
            selectionColor = COLOR_SELECTION_BG
            selectedTextColor = COLOR_SELECTION_FG
        }
    }

    // ============================================================
    //                       diff 渲染 / 滚动
    // ============================================================

    /**
     * 渲染 diff。
     *
     * 性能关键：
     * 1. 先把所有行拼成一整段文本，并同步记录每段连续非 SAME 的 [start,end) 偏移。
     * 2. 文档一次性清空 + insertString（单次 O(N)）。
     * 3. 按记录到的"段"批量 setCharacterAttributes，一段一次；相邻同 tag 的多行合并成一段。
     */
    private fun renderDiff(
        pane: JTextPane,
        lines: List<JsonDiffer.DiffLine>
    ): RenderResult {
        val doc = pane.styledDocument

        val sb = StringBuilder(lines.sumOf { it.text.length + 1 })
        val ranges = mutableListOf<Pair<JsonDiffer.Tag, TaggedRange>>()
        val sameStarts = ArrayList<Int>(lines.size / 2 + 1)
        var runTag: JsonDiffer.Tag? = null
        var runStart = 0
        var runEnd = 0

        for ((i, line) in lines.withIndex()) {
            val lineStart = sb.length
            sb.append(line.text)
            if (i != lines.size - 1) sb.append('\n')
            val lineEnd = sb.length

            if (line.tag == JsonDiffer.Tag.SAME) {
                sameStarts.add(lineStart)
                if (runTag != null) {
                    ranges += runTag!! to TaggedRange(runStart, runEnd)
                    runTag = null
                }
            } else {
                if (runTag == line.tag) {
                    runEnd = lineEnd
                } else {
                    if (runTag != null) ranges += runTag!! to TaggedRange(runStart, runEnd)
                    runTag = line.tag
                    runStart = lineStart
                    runEnd = lineEnd
                }
            }
        }
        if (runTag != null) ranges += runTag!! to TaggedRange(runStart, runEnd)

        doc.remove(0, doc.length)
        val defaultAttr = SimpleAttributeSet()
        StyleConstants.setForeground(defaultAttr, JBColor.foreground())
        StyleConstants.setBackground(defaultAttr, pane.background)
        doc.insertString(0, sb.toString(), defaultAttr)

        val diffAttr = SimpleAttributeSet().apply {
            StyleConstants.setBackground(this, COLOR_DIFF_BG)
            StyleConstants.setForeground(this, COLOR_DIFF_FG)
        }
        for ((_, r) in ranges) {
            doc.setCharacterAttributes(r.start, r.end - r.start, diffAttr, true)
        }
        pane.caretPosition = 0
        return RenderResult(ranges, sameStarts.toIntArray())
    }

    private fun scrollToRange(pane: JTextPane, range: TaggedRange) {
        try {
            val len = pane.document.length
            val start = range.start.coerceIn(0, len)
            val end = range.end.coerceIn(start, len)
            pane.caretPosition = start
            pane.select(start, end)
            val rect = pane.modelToView2D(start) ?: return
            val target = java.awt.Rectangle(
                rect.x.toInt(),
                (rect.y - 40).toInt().coerceAtLeast(0),
                rect.width.toInt().coerceAtLeast(1),
                (rect.height + 80).toInt()
            )
            pane.scrollRectToVisible(target)
            SwingUtilities.invokeLater { pane.requestFocusInWindow() }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun scrollPaneToOffset(pane: JTextPane, offset: Int) {
        try {
            val len = pane.document.length
            val pos = offset.coerceIn(0, len)
            val rect = pane.modelToView2D(pos) ?: return
            val target = java.awt.Rectangle(
                rect.x.toInt(),
                (rect.y - 40).toInt().coerceAtLeast(0),
                rect.width.toInt().coerceAtLeast(1),
                (rect.height + 80).toInt()
            )
            pane.scrollRectToVisible(target)
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * 利用两侧 SAME 行的"骨架对应"把另一侧也滚到相近位置：
     * 渲染时左右 SAME 行（对象/数组括号、共同 key）按出现顺序一一对应，
     * 二分找到源侧 rank，跳到目标侧同 rank 的 SAME 偏移。
     */
    private fun syncOtherPane(sourceIsLeft: Boolean, sourceOffset: Int) {
        val srcArr = if (sourceIsLeft) leftSameOffsets else rightSameOffsets
        val dstArr = if (sourceIsLeft) rightSameOffsets else leftSameOffsets
        val dstPane = if (sourceIsLeft) rightDiffPane else leftDiffPane
        if (srcArr.isEmpty() || dstArr.isEmpty()) return

        var lo = 0
        var hi = srcArr.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (srcArr[mid] <= sourceOffset) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) idx = 0
        if (idx >= dstArr.size) idx = dstArr.size - 1
        scrollPaneToOffset(dstPane, dstArr[idx])
    }

    // ============================================================
    //                       查找
    // ============================================================

    private fun installCompareFindFocusTracking() {
        val listener = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                val c = e.component
                if (c === leftInputPane || c === rightInputPane || c === leftDiffPane || c === rightDiffPane) {
                    @Suppress("UNCHECKED_CAST")
                    lastFocusedCompareText = c as JTextComponent
                }
            }
        }
        leftInputPane.addFocusListener(listener)
        rightInputPane.addFocusListener(listener)
        leftDiffPane.addFocusListener(listener)
        rightDiffPane.addFocusListener(listener)
    }

    private fun installFindFieldShortcuts(field: JBTextField, action: (forward: Boolean) -> Unit) {
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                e.consume()
                action(!e.isShiftDown)
            }
        })
    }

    private fun findInFormatPane(forward: Boolean) {
        if (formatShowingTree) showTextInsideFormatTab()
        val q = formatFindField.text
        if (q.isEmpty()) return
        val ok = if (forward) {
            findNext(singlePane, q, formatFindCase.isSelected)
        } else {
            findPrevious(singlePane, q, formatFindCase.isSelected)
        }
        if (!ok) Toolkit.getDefaultToolkit().beep()
    }

    private fun findInComparePane(forward: Boolean) {
        val q = compareFindField.text
        if (q.isEmpty()) return
        var pane = mapDiffPaneToInput(resolveCompareSearchTarget())
        if (compareShowingDiff) {
            showCompareInputMode()
            lastFocusedCompareText = pane
        }
        val ok = if (forward) {
            findNext(pane, q, compareFindCase.isSelected)
        } else {
            findPrevious(pane, q, compareFindCase.isSelected)
        }
        if (!ok) Toolkit.getDefaultToolkit().beep()
    }

    /** diff 视图不可查找：映射到同侧原始 JSON 输入框。 */
    private fun mapDiffPaneToInput(pane: JTextComponent): JTextComponent = when (pane) {
        leftDiffPane -> leftInputPane
        rightDiffPane -> rightInputPane
        else -> pane
    }

    /** 优先当前键盘焦点所在的对比侧编辑区，否则使用最近一次聚焦的文本区。 */
    private fun resolveCompareSearchTarget(): JTextComponent {
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
        if (focus === leftInputPane || focus === rightInputPane || focus === leftDiffPane || focus === rightDiffPane) {
            @Suppress("UNCHECKED_CAST")
            return focus as JTextComponent
        }
        return lastFocusedCompareText
    }

    private fun findNext(pane: JTextComponent, query: String, caseSensitive: Boolean): Boolean {
        if (query.isEmpty()) return false
        val doc = pane.text
        val n = doc.length
        val m = query.length
        if (n < m) return false

        fun matchAt(i: Int): Boolean =
            doc.regionMatches(i, query, 0, m, ignoreCase = !caseSensitive)

        val selHi = maxOf(pane.selectionStart, pane.selectionEnd)
        val start = selHi.coerceIn(0, (n - m).coerceAtLeast(0))
        var i = start
        while (i <= n - m) {
            if (matchAt(i)) {
                selectAndScrollToMatch(pane, i, i + m)
                return true
            }
            i++
        }
        i = 0
        while (i < start) {
            if (matchAt(i)) {
                selectAndScrollToMatch(pane, i, i + m)
                return true
            }
            i++
        }
        return false
    }

    private fun findPrevious(pane: JTextComponent, query: String, caseSensitive: Boolean): Boolean {
        if (query.isEmpty()) return false
        val doc = pane.text
        val n = doc.length
        val m = query.length
        if (n < m) return false

        fun matchAt(i: Int): Boolean =
            doc.regionMatches(i, query, 0, m, ignoreCase = !caseSensitive)

        val selLo = minOf(pane.selectionStart, pane.selectionEnd)
        val startPos = (selLo - m).coerceIn(0, (n - m).coerceAtLeast(0))

        var i = startPos
        while (i >= 0) {
            if (matchAt(i)) {
                selectAndScrollToMatch(pane, i, i + m)
                return true
            }
            i--
        }
        i = n - m
        while (i > startPos) {
            if (matchAt(i)) {
                selectAndScrollToMatch(pane, i, i + m)
                return true
            }
            i--
        }
        return false
    }

    private fun selectAndScrollToMatch(pane: JTextComponent, start: Int, end: Int) {
        pane.requestFocusInWindow()
        pane.caretPosition = start
        pane.select(start, end)
        try {
            val rect = pane.modelToView2D(start)?.bounds ?: return
            pane.scrollRectToVisible(
                Rectangle(
                    rect.x,
                    (rect.y - 40).coerceAtLeast(0),
                    rect.width.coerceAtLeast(1),
                    rect.height + 80
                )
            )
        } catch (_: Exception) {
            // ignore
        }
        SwingUtilities.invokeLater { pane.requestFocusInWindow() }
    }

    // ============================================================
    //                       常量 & 辅助类
    // ============================================================

    companion object {
        private const val PREF_LANGUAGE_TAG = "com.cn.else.jsontools.language.tag"
        private const val FOLLOW_IDE_VALUE = "follow-ide"
        private const val FORMAT_CARD_TEXT = "FORMAT_TEXT"
        private const val FORMAT_CARD_TREE = "FORMAT_TREE"
        private const val COMPARE_CARD_INPUT = "COMPARE_INPUT"
        private const val COMPARE_CARD_DIFF = "COMPARE_DIFF"

        /**
         * 场景化配色（深色主题友好）：
         * - 差异底色：柔和珊瑚红，提示“不同”但不过曝
         * - 默认选中：偏蓝，符合编辑器习惯，便于识别鼠标操作焦点
         * - 联动选中：紫色半透明叠加，在“差异底色”之上仍可分辨“当前联动项”
         */
        private val COLOR_DIFF_BG = JBColor(Color(0xFF, 0xD2, 0xCC), Color(0x7A, 0x33, 0x2F))
        private val COLOR_DIFF_FG = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xFF, 0xE8, 0xE6))

        private val COLOR_SELECTION_BG = JBColor(Color(0x9F, 0xCD, 0xFF), Color(0x2C, 0x5E, 0x9E))
        private val COLOR_SELECTION_FG = JBColor(Color(0x00, 0x00, 0x00), Color(0xF5, 0xF9, 0xFF))

        // 半透明紫色覆盖层，避免“当前联动项”被大片差异色淹没
        private val BRIGHT_SELECTION_PAINTER =
            DefaultHighlighter.DefaultHighlightPainter(Color(0x8B, 0x5C, 0xFF, 120))

        /** 成功状态用的绿色（亮 / 暗主题各一）。 */
        private val OK_GREEN = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x81, 0xC7, 0x84))
    }

    /**
     * 树节点的用户数据：持有 key（对象属性名或数组下标，根节点为 null）和对应 [JsonElement]。
     * [toString] 输出节点显示文本，展开/折叠时通过 [JsonTreeCellRenderer] 动态切换样式。
     */
    private class JsonNodeData(val key: String?, val element: JsonElement) {
        /** 折叠时显示的完整摘要文本（包含元素数量）。 */
        fun collapsedLabel(): String = prefix() + when (element) {
            is JsonObject    -> "{${JsonToolsBundle.message("node.items", element.size())}}"
            is JsonArray     -> "[${JsonToolsBundle.message("node.items", element.size())}]"
            is JsonPrimitive -> if (element.isString) "\"${element.asString}\"" else element.asString
            else             -> "null"
        }

        /** 展开时显示的文本（对象/数组只显示括号，子项由子节点展示）。 */
        fun expandedLabel(): String = prefix() + when (element) {
            is JsonObject -> "{"
            is JsonArray  -> "["
            else          -> collapsedLabel().removePrefix(prefix())
        }

        private fun prefix(): String = when {
            key == null         -> ""
            key.startsWith("[") -> "$key: "    // 数组下标，如 [0]:
            else                -> "\"$key\": "
        }

        override fun toString() = collapsedLabel()
    }

    /** 根据节点展开状态显示不同的标签文本，并去掉默认图标。 */
    private inner class JsonTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            val data = (value as? DefaultMutableTreeNode)?.userObject as? JsonNodeData
            if (data != null) {
                text = if (!leaf && expanded) data.expandedLabel() else data.collapsedLabel()
                icon = null
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            }
            return this
        }
    }
}

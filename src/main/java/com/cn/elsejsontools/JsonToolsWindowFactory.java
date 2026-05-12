package com.cn.elsejsontools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

/**
 * Java factory: avoids Kotlin 2.x synthetic invokespecial bridges to {@link ToolWindowFactory}
 * defaults. {@code com.cn.else.jsontools} is not expressible in Java source ({@code else}), so
 * the panel is loaded by name.
 */
public final class JsonToolsWindowFactory implements ToolWindowFactory {

    private static final String PANEL_CLASS_NAME = "com.cn.else.jsontools.JsonToolsPanel";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        try {
            Object panel = Class.forName(PANEL_CLASS_NAME).getDeclaredConstructor().newInstance();
            JComponent component = (JComponent) panel.getClass().getMethod("getComponent").invoke(panel);
            Content content = ContentFactory.getInstance().createContent(component, "", false);
            toolWindow.getContentManager().addContent(content);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("JSON Tools pro max: failed to create tool window panel", e);
        }
    }
}

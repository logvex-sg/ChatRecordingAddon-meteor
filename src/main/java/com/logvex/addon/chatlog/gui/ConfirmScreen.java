package com.logvex.addon.chatlog.gui;

import com.logvex.addon.framework.gui.GuiTheme;
import com.logvex.addon.framework.gui.GuiThemes;
import com.logvex.addon.framework.gui.WindowScreen;
import com.logvex.addon.framework.gui.widgets.containers.WTable;

/**
 * A small modal confirmation used before destructive archive operations. Reuses the addon's window
 * chrome so it looks native.
 */
public class ConfirmScreen extends WindowScreen {
    protected final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private final String message;
    private final Runnable onConfirm;

    public ConfirmScreen(String title, String message, Runnable onConfirm) {
        super(GuiThemes.get(), title);
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    public void initWidgets() {
        add(theme.label(message)).expandX();

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.button("Cancel")).widget().action = this::dismiss;
        table.add(theme.button("Confirm")).widget().action = () -> {
            if (onConfirm != null) onConfirm.run();
            dismiss();
        };
    }

    private void dismiss() {
        if (mc != null) mc.setScreen(parent);
    }
}

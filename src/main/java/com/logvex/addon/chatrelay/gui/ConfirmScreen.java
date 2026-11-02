package com.logvex.addon.chatrelay.gui;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

/** Small modal confirmation window, used before destructive archive operations. */
public class ConfirmScreen extends WindowScreen {
    private final String message;
    private final Runnable onConfirm;

    public ConfirmScreen(GuiTheme theme, String title, String message, Runnable onConfirm) {
        super(theme, title);
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    public void initWidgets() {
        add(theme.label(message)).expandX();

        WHorizontalList buttons = add(theme.horizontalList()).expandX().widget();

        WButton confirm = buttons.add(theme.button("Confirm")).expandX().widget();
        confirm.action = () -> {
            close();
            if (onConfirm != null) onConfirm.run();
        };

        WButton cancel = buttons.add(theme.button("Cancel")).expandX().widget();
        cancel.action = this::close;
    }
}

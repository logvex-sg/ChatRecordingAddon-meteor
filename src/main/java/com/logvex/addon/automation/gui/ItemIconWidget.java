package com.logvex.addon.automation.gui;

import com.logvex.addon.framework.gui.widgets.WWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * A 16x16 item icon, used in the acquisition list so a material is recognisable at a glance.
 *
 * <p>It draws the stack the client already knows about; it never creates one, so an unknown item is
 * simply not drawn and the surrounding row's text label carries the name.
 */
final class ItemIconWidget extends WWidget {
    private final ItemStack stack;

    ItemIconWidget(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public void calculateSize() {
        width = 16;
        height = 16;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, double delta) {
        context.drawItem(stack, (int) Math.round(x), (int) Math.round(y));
        if (isMouseOver(mouseX, mouseY)) {
            context.drawItemTooltip(MinecraftClient.getInstance().textRenderer, stack,
                mouseX, mouseY);
        }
    }
}

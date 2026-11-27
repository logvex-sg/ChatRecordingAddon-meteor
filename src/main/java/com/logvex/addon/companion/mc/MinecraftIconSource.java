package com.logvex.addon.companion.mc;

import com.logvex.addon.companion.core.CompanionIconSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Optional;

/**
 * Serves each item's own texture to the companion app.
 *
 * <p>The picture is resolved the way the game resolves it: the item's baked model says which sprite
 * it uses, and that sprite names the texture file. Reading the file the sprite points at means the
 * app shows the same image the player sees, including when a resource pack replaces it. Drawing the
 * item into a framebuffer and reading the pixels back would be the other option, but it would tie the
 * result to the current GUI scale and window size, and it needs a render thread on every request.
 *
 * <p>Everything is read on the client thread and only for items that exist; an item with no model,
 * or a client outside a world, answers null and the app shows the item's name instead.
 */
public final class MinecraftIconSource implements CompanionIconSource {
    @Override
    public byte[] icon(String itemId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getItemRenderer() == null || mc.getResourceManager() == null) return null;

        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return null;
        Item item = Registries.ITEM.get(id);
        // get() answers AIR for an unregistered id, and AIR has no icon worth serving.
        if (item == null || item == Items.AIR) return null;

        Identifier texture = textureFor(mc, item);
        if (texture == null) return null;
        return read(mc, texture);
    }

    /** The texture file an item's model uses, or null when the model has no sprite. */
    private Identifier textureFor(MinecraftClient mc, Item item) {
        ItemRenderer renderer = mc.getItemRenderer();
        ItemStack stack = new ItemStack(item);
        BakedModel model = renderer.getModels().getModel(stack);
        if (model == null) return null;
        Sprite sprite = model.getParticleSprite();
        if (sprite == null) return null;
        SpriteContents contents = sprite.getContents();
        if (contents == null) return null;
        Identifier id = contents.getId();
        if (id == null) return null;
        // Sprite ids are already texture paths; make sure a bare name still resolves to a file.
        return id.getPath().endsWith(".png") ? id : id.withSuffixedPath(".png");
    }

    private byte[] read(MinecraftClient mc, Identifier texture) {
        Optional<Resource> resource = mc.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}

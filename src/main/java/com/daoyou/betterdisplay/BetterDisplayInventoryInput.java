package com.daoyou.betterdisplay;

import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

final class BetterDisplayInventoryInput {
    private BetterDisplayInventoryInput() {
    }

    static void init() {
        Predicate<InputEvent.MouseScrollingEvent> handleScroll = BetterDisplayInventoryInput::onScroll;
        InputEvent.MouseScrollingEvent.BUS.addListener(handleScroll);
    }

    private static boolean onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isSpectator()
            || minecraft.screen != null || minecraft.getOverlay() != null || event.getDeltaY() == 0
            || !(InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT))) {
            return false;
        }
        QuickHotbarInput.stop();
        BetterDisplayCombat.requestHotbarRow(event.getDeltaY() > 0);
        return true;
    }
}

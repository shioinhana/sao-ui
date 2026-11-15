package com.daoyou.betterdisplay;

import java.util.Arrays;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class QuickHotbarInput {
    private static final int REPEAT_TICKS = 4;
    private static final boolean[] CAPTURED_KEYS = new boolean[Inventory.getSelectionSize()];
    private static int activeSlot = -1;
    private static ItemStack activeItem = ItemStack.EMPTY;
    private static int repeatDelay;

    private QuickHotbarInput() {
    }

    // Call before Minecraft.handleKeybinds consumes clicks and changes the selected slot.
    static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !BetterDisplayCombat.hasBothHands(player)
            || minecraft.screen != null || minecraft.getOverlay() != null
            || !isConnected(minecraft) || !minecraft.isWindowActive() || !player.isAlive() || player.isSpectator()
            || (player.hasInfiniteMaterials() && (minecraft.options.keySaveHotbarActivator.isDown()
                || minecraft.options.keyLoadHotbarActivator.isDown()))) {
            stop();
            Arrays.fill(CAPTURED_KEYS, false);
            return;
        }

        KeyMapping[] keys = minecraft.options.keyHotbarSlots;
        Inventory inventory = player.getInventory();
        if (activeSlot >= 0) {
            if (!keys[activeSlot].isDown()
                || !ItemStack.isSameItemSameComponents(activeItem, inventory.getItem(activeSlot))) {
                stop();
            } else if (--repeatDelay == 0) {
                sendUse(minecraft);
            }
        }

        for (int slot = 0; slot < keys.length; slot++) {
            KeyMapping key = keys[slot];
            CAPTURED_KEYS[slot] &= key.isDown();
            ItemStack stack = inventory.getItem(slot);
            if (!key.consumeClick()) {
                continue;
            }
            while (key.consumeClick()) {
                // Discard queued repeats of this captured hotbar key.
            }
            if (!CAPTURED_KEYS[slot] && QuickHotbarUse.isSupported(stack)) {
                stop();
                activeSlot = slot;
                activeItem = stack.copyWithCount(1);
                sendUse(minecraft);
            }
            CAPTURED_KEYS[slot] = key.isDown();
        }
    }

    private static void sendUse(Minecraft minecraft) {
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BetterDisplayCombat.requestQuickSlot(activeSlot, hit.getBlockPos(), hit.getDirection());
        } else {
            BetterDisplayCombat.requestQuickSlot(activeSlot, null, null);
        }
        repeatDelay = REPEAT_TICKS;
    }

    static void stop() {
        if (activeSlot >= 0) {
            if (isConnected(Minecraft.getInstance())) {
                BetterDisplayCombat.requestQuickConsume(activeSlot, false);
            }
            activeSlot = -1;
            activeItem = ItemStack.EMPTY;
        }
    }

    private static boolean isConnected(Minecraft minecraft) {
        var connection = minecraft.getConnection();
        return connection != null && connection.getConnection().isConnected();
    }
}

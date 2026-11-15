package com.daoyou.betterdisplay;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.listener.Priority;

final class BetterDisplayInventory {
    private static final int HOTBAR_SIZE = Inventory.getSelectionSize();
    // Forge dispatches the before/after events synchronously inside ItemEntity.playerTouch.
    private static final ThreadLocal<Pickup> PICKUP = new ThreadLocal<>();

    private BetterDisplayInventory() {
    }

    static void init() {
        EntityItemPickupEvent.BUS.addListener(Priority.LOWEST, BetterDisplayInventory::beforePickup);
        PlayerEvent.ItemPickupEvent.BUS.addListener(Priority.HIGHEST, BetterDisplayInventory::afterPickup);
    }

    static void rotateRows(Inventory inventory, boolean scrollUp) {
        // The bottom backpack row is adjacent to the hotbar; preserve each item's column.
        Collections.rotate(inventory.getNonEquipmentItems(), scrollUp ? HOTBAR_SIZE : -HOTBAR_SIZE);
        inventory.setChanged();
    }

    static int insertIntoBackpack(Inventory inventory, ItemStack stack) {
        return insertIntoSlots(inventory, stack, HOTBAR_SIZE, Inventory.INVENTORY_SIZE);
    }

    private static void beforePickup(EntityItemPickupEvent event) {
        PICKUP.remove();
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getResult() != Result.DEFAULT) {
            return;
        }
        ItemStack item = event.getItem().getItem();
        Inventory inventory = player.getInventory();
        if (item.isEmpty() || !hasHotbarSpace(inventory, item)) {
            return;
        }
        int[] backpackCounts = new int[Inventory.INVENTORY_SIZE - HOTBAR_SIZE];
        for (int slot = HOTBAR_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
            backpackCounts[slot - HOTBAR_SIZE] = matchingCount(inventory.getItem(slot), item);
        }
        int offhandCount = matchingCount(inventory.getItem(Inventory.SLOT_OFFHAND), item);
        PICKUP.set(new Pickup(player.getUUID(), event.getItem().getUUID(), item.copyWithCount(1), backpackCounts, offhandCount));
    }

    private static void afterPickup(PlayerEvent.ItemPickupEvent event) {
        Pickup pickup = PICKUP.get();
        PICKUP.remove();
        if (pickup == null || !(event.getEntity() instanceof ServerPlayer player)
            || !pickup.player.equals(player.getUUID()) || !pickup.entity.equals(event.getOriginalEntity().getUUID())
            || event.getStack().isEmpty() || !ItemStack.isSameItemSameComponents(pickup.item, event.getStack())) {
            return;
        }

        Inventory inventory = player.getInventory();
        // Move only the counts added by this pickup. Vanilla handles permission, stats and leftover drops.
        int remaining = event.getStack().getCount();
        remaining -= movePickedItems(inventory, Inventory.SLOT_OFFHAND, pickup.offhandCount, pickup.item, remaining);
        for (int slot = HOTBAR_SIZE; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            if (!hasHotbarSpace(inventory, pickup.item)) {
                break;
            }
            remaining -= movePickedItems(inventory, slot, pickup.backpackCounts[slot - HOTBAR_SIZE], pickup.item, remaining);
        }
        if (remaining < event.getStack().getCount()) {
            inventory.setChanged();
        }
    }

    private static int movePickedItems(Inventory inventory, int slot, int countBefore, ItemStack item, int limit) {
        ItemStack source = inventory.getItem(slot);
        int added = Math.min(matchingCount(source, item) - countBefore, limit);
        if (added <= 0) {
            return 0;
        }
        int moved = insertIntoSlots(inventory, source.copyWithCount(added), 0, HOTBAR_SIZE);
        if (moved > 0) {
            source.shrink(moved);
            inventory.setItem(slot, source.isEmpty() ? ItemStack.EMPTY : source);
        }
        return moved;
    }

    private static boolean hasHotbarSpace(Inventory inventory, ItemStack item) {
        int capacity = inventory.getMaxStackSize(item);
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || (ItemStack.isSameItemSameComponents(existing, item) && existing.getCount() < capacity)) {
                return true;
            }
        }
        return false;
    }

    private static int matchingCount(ItemStack stack, ItemStack item) {
        return ItemStack.isSameItemSameComponents(stack, item) ? stack.getCount() : 0;
    }

    private static int insertIntoSlots(Inventory inventory, ItemStack stack, int first, int end) {
        int before = stack.getCount();
        int capacity = inventory.getMaxStackSize(stack);
        for (int slot = first; slot < end && !stack.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int amount = Math.min(stack.getCount(), capacity - existing.getCount());
                if (amount > 0) {
                    existing.grow(amount);
                    stack.shrink(amount);
                }
            }
        }
        for (int slot = first; slot < end && !stack.isEmpty(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack.split(capacity));
            }
        }
        return before - stack.getCount();
    }

    private record Pickup(UUID player, UUID entity, ItemStack item, int[] backpackCounts, int offhandCount) {
    }
}

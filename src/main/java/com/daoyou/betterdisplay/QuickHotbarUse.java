package com.daoyou.betterdisplay;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class QuickHotbarUse {
    private static final int REPEAT_TICKS = 4;
    private static final int HEARTBEAT_TIMEOUT_TICKS = 12;
    private static final Map<UUID, Consumption> CONSUMING = new HashMap<>();
    private static final Map<UUID, Long> LAST_INSTANT_USE = new HashMap<>();

    private QuickHotbarUse() {
    }

    static boolean isSupported(ItemStack stack) {
        return !stack.isEmpty() && (stack.has(DataComponents.CONSUMABLE)
            || stack.getItem() instanceof BlockItem
            || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)
            || stack.is(Items.EXPERIENCE_BOTTLE) || stack.is(Items.FIREWORK_ROCKET));
    }

    static void request(ServerPlayer player, int slot, BlockPos pos, Direction face) {
        if (!Inventory.isHotbarSlot(slot)) {
            return;
        }
        UUID id = player.getUUID();
        ItemStack stack = player.getInventory().getItem(slot);
        if (!canUse(player, stack)) {
            stop(player);
            return;
        }

        long now = player.level().getGameTime();
        Consumption current = CONSUMING.get(id);
        if (current != null && current.slot == slot && current.matches(stack)
            && now - current.lastRequest <= HEARTBEAT_TIMEOUT_TICKS) {
            current.lastRequest = now;
            return;
        }
        stop(player);

        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            if (consumable.canConsume(player, stack)) {
                int duration = ForgeEventFactory.onItemUseStart(player, stack, stack.getUseDuration(player));
                if (duration >= 0) {
                    CONSUMING.put(id, new Consumption(slot, stack, consumable, now + duration, now));
                }
            }
            return;
        }

        Long previous = LAST_INSTANT_USE.get(id);
        if (previous != null && now - previous < REPEAT_TICKS) {
            return;
        }
        BlockHitResult hit = null;
        if (stack.getItem() instanceof BlockItem) {
            hit = placementTarget(player, pos, face);
            if (hit == null) {
                return;
            }
        }
        LAST_INSTANT_USE.put(id, now);
        useOnce(player, slot, stack, hit);
    }

    static void tick(ServerPlayer player) {
        Consumption current = CONSUMING.get(player.getUUID());
        if (current == null) {
            return;
        }
        long now = player.level().getGameTime();
        Inventory inventory = player.getInventory();
        ItemStack stack = inventory.getItem(current.slot);
        if (now - current.lastRequest > HEARTBEAT_TIMEOUT_TICKS || !current.matches(stack)
            || !canUse(player, stack) || !current.consumable.canConsume(player, stack)) {
            stop(player);
            return;
        }
        int remainingTicks = (int) (current.finishTick - now);
        if (remainingTicks > 0) {
            if (current.consumable.shouldEmitParticlesAndSounds(remainingTicks)) {
                current.consumable.emitParticlesAndSounds(player.getRandom(), player, stack, 5);
            }
            return;
        }

        stop(player);
        ItemStack before = stack.copy();
        ItemStack result = stack.finishUsingItem(player.level(), player);
        ItemStack remainder = ForgeEventFactory.onItemUseFinish(player, before, 0, result);
        inventory.setItem(current.slot, remainder);
        syncInventory(player);
    }

    static void release(ServerPlayer player, int slot) {
        Consumption current = CONSUMING.get(player.getUUID());
        if (current != null && current.slot == slot) {
            stop(player);
        }
    }

    static void stop(ServerPlayer player) {
        CONSUMING.remove(player.getUUID());
    }

    static void clear(ServerPlayer player) {
        stop(player);
        LAST_INSTANT_USE.remove(player.getUUID());
    }

    private static boolean canUse(ServerPlayer player, ItemStack stack) {
        return BetterDisplayCombat.hasBothHands(player) && player.isAlive() && !player.isSpectator() && !player.isUsingItem()
            && player.containerMenu == player.inventoryMenu && isSupported(stack)
            && stack.isItemEnabled(player.level().enabledFeatures()) && !player.getCooldowns().isOnCooldown(stack);
    }

    private static BlockHitResult placementTarget(ServerPlayer player, BlockPos pos, Direction face) {
        ServerLevel level = player.level();
        if (pos == null || face == null || !player.isWithinBlockInteractionRange(pos, 1.0D)
            || !level.isInWorldBounds(pos) || !level.isLoaded(pos) || !level.mayInteract(player, pos)) {
            return null;
        }
        HitResult target = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (target instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(pos) && hit.getDirection() == face) {
            return hit;
        }
        // 数字键请求到达时，视角同步包可能还在路上。
        return new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false);
    }

    private static void useOnce(ServerPlayer player, int slot, ItemStack stack, BlockHitResult hit) {
        Inventory inventory = player.getInventory();
        // 原版使用逻辑会读手上的物品。仅在同步调用期间借用副手。
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        inventory.setItem(slot, ItemStack.EMPTY);
        inventory.setItem(Inventory.SLOT_OFFHAND, stack);
        try {
            InteractionResult result = hit == null
                ? player.gameMode.useItem(player, player.level(), stack, InteractionHand.OFF_HAND)
                : player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.OFF_HAND, hit);
            if (result instanceof InteractionResult.Success success
                && success.swingSource() != InteractionResult.SwingSource.NONE) {
                player.swing(InteractionHand.OFF_HAND, true);
            }
        } finally {
            if (player.isUsingItem()) {
                player.stopUsingItem();
            }
            ItemStack remainder = inventory.getItem(Inventory.SLOT_OFFHAND);
            inventory.setItem(Inventory.SLOT_OFFHAND, offhand);
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, remainder);
            } else if (!remainder.isEmpty()) {
                inventory.placeItemBackInInventory(remainder);
            }
            syncInventory(player);
        }
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
    }

    private static final class Consumption {
        private final int slot;
        private final ItemStack source;
        private final ItemStack original;
        private final Consumable consumable;
        private final long finishTick;
        private long lastRequest;

        private Consumption(int slot, ItemStack source, Consumable consumable, long finishTick, long lastRequest) {
            this.slot = slot;
            this.source = source;
            this.original = source.copy();
            this.consumable = consumable;
            this.finishTick = finishTick;
            this.lastRequest = lastRequest;
        }

        private boolean matches(ItemStack stack) {
            return !stack.isEmpty() && stack == source && ItemStack.isSameItemSameComponents(stack, original);
        }
    }
}

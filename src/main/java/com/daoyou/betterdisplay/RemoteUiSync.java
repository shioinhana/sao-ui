package com.daoyou.betterdisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/** Validates shared menus and sends their snapshots to nearby players on the server thread. */
public final class RemoteUiSync {
    public static final double BROADCAST_RADIUS = 32.0D;
    private static final double RADIUS_SQUARED = BROADCAST_RADIUS * BROADCAST_RADIUS;
    private static final int HEARTBEAT_INTERVAL_TICKS = 20;
    private static final int CLIENT_MENU_TIMEOUT_TICKS = 100;
    private static final int SHARED_OPTION_MASK = SaoMenuModel.SHARE_UI_STATE_ENABLED | SaoMenuModel.ARMOR_AVAILABLE
        | SaoMenuModel.WEAPON_AVAILABLE | SaoMenuModel.TOOL_AVAILABLE | SaoMenuModel.EQUIPMENT_MASK_PRESENT;
    private static final Map<UUID, SharedMenu> OPEN_MENUS = new HashMap<>();
    /** 会出现在面板上的装备槽位顺序固定，指纹依赖它。 */
    private static final EquipmentSlot[] FINGERPRINT_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private RemoteUiSync() {
    }

    public static void onServerTick(MinecraftServer server) {
        int tick = server.getTickCount();
        boolean heartbeat = tick % HEARTBEAT_INTERVAL_TICKS == 0;
        var entries = OPEN_MENUS.values().iterator();
        while (entries.hasNext()) {
            SharedMenu shared = entries.next();
            if (shared.level.getServer() != server) {
                continue;
            }
            if (!canShare(shared.source) || shared.source.level() != shared.level
                || tick - shared.lastUpdateTick >= CLIENT_MENU_TIMEOUT_TICKS) {
                clearViewers(shared);
                entries.remove();
            } else if (heartbeat || shared.pending || shared.equipmentFingerprint() != shared.publishedFingerprint) {
                publish(shared);
            } else {
                removeDistantViewers(shared);
            }
        }
    }

    public static void onClientUpdate(ServerPlayer sender, SaoMenuUpdatePacket packet) {
        SaoMenuState menu = packet.menu();
        if (menu == null || !canShare(sender) || !SaoMenuModel.validPath(menu.path())) {
            closeMenu(sender);
            return;
        }
        UUID id = sender.getUUID();
        SharedMenu shared = OPEN_MENUS.get(id);
        if (shared == null || shared.source != sender || shared.level != sender.level()) {
            closeMenu(sender);
            shared = new SharedMenu(sender, packet.anchorYaw(), packet.anchorPitch());
            OPEN_MENUS.put(id, shared);
        }
        int tick = shared.level.getServer().getTickCount();
        shared.lastUpdateTick = tick;
        if (!menu.equals(shared.menu)) {
            shared.menu = menu;
            // Coalesce multiple changes in one server tick. Close always clears viewers immediately.
            if (shared.lastPublishedTick == tick) {
                shared.pending = true;
            } else {
                publish(shared);
            }
        }
    }

    public static void onPlayerLogout(ServerPlayer player) {
        closeMenu(player);
        removeViewer(player, false);
    }

    public static void onPlayerChangedWorld(ServerPlayer player) {
        closeMenu(player);
        removeViewer(player, true);
    }

    public static void onServerStopped(MinecraftServer server) {
        OPEN_MENUS.values().removeIf(shared -> shared.level.getServer() == server);
    }

    private static void removeViewer(ServerPlayer player, boolean sendClear) {
        for (SharedMenu shared : OPEN_MENUS.values()) {
            if (shared.level.getServer() == player.level().getServer()
                && shared.viewers.removeIf(viewer -> viewer.getUUID().equals(player.getUUID())) && sendClear) {
                sendClear(player, shared.source.getUUID());
            }
        }
    }

    private static void closeMenu(ServerPlayer source) {
        SharedMenu shared = OPEN_MENUS.get(source.getUUID());
        if (shared != null && shared.level.getServer() == source.level().getServer()) {
            OPEN_MENUS.remove(source.getUUID());
            clearViewers(shared);
        }
    }

    private static void clearViewers(SharedMenu shared) {
        for (ServerPlayer viewer : shared.viewers) {
            sendClear(viewer, shared.source.getUUID());
        }
        shared.viewers.clear();
    }

    private static void removeDistantViewers(SharedMenu shared) {
        var viewers = shared.viewers.iterator();
        while (viewers.hasNext()) {
            ServerPlayer viewer = viewers.next();
            if (!isNearby(shared, viewer)) {
                sendClear(viewer, shared.source.getUUID());
                viewers.remove();
            }
        }
    }

    private static void sendClear(ServerPlayer viewer, UUID sourceId) {
        if (isConnected(viewer)) {
            BetterDisplayNetworking.sendToPlayer(viewer, new RemoteUiStatePacket(new UiStateSnapshot(sourceId, null)));
        }
    }

    private static boolean canShare(ServerPlayer player) {
        return isConnected(player) && player.isAlive() && !player.isChangingDimension();
    }

    private static boolean isConnected(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages() && !player.hasDisconnected();
    }

    private static boolean isNearby(SharedMenu shared, ServerPlayer viewer) {
        return viewer != shared.source && viewer.level() == shared.level && isConnected(viewer)
            && !viewer.isChangingDimension() && viewer.distanceToSqr(shared.source) <= RADIUS_SQUARED;
    }

    private static void publish(SharedMenu shared) {
        shared.pending = false;
        shared.lastPublishedTick = shared.level.getServer().getTickCount();
        shared.publishedFingerprint = shared.equipmentFingerprint();
        removeDistantViewers(shared);
        RemoteUiStatePacket packet = null;
        for (ServerPlayer viewer : shared.level.players()) {
            if (!isNearby(shared, viewer)) {
                continue;
            }
            if (packet == null) {
                packet = new RemoteUiStatePacket(snapshotFor(shared.source, shared.menu, shared.anchorYaw, shared.anchorPitch));
            }
            BetterDisplayNetworking.sendToPlayer(viewer, packet);
            shared.viewers.add(viewer);
        }
    }

    public static UiStateSnapshot snapshotFor(ServerPlayer player) {
        SharedMenu shared = OPEN_MENUS.get(player.getUUID());
        if (shared == null || shared.source != player || shared.level != player.level() || !canShare(player)) {
            return new UiStateSnapshot(player.getUUID(), null);
        }
        return snapshotFor(player, shared.menu, shared.anchorYaw, shared.anchorPitch);
    }

    static UiStateSnapshot snapshotFor(ServerPlayer player, SaoMenuState menu) {
        return snapshotFor(player, menu, player.getYRot(), player.getXRot());
    }

    private static UiStateSnapshot snapshotFor(ServerPlayer player, SaoMenuState menu, float anchorYaw, float anchorPitch) {
        if (menu == null) {
            return new UiStateSnapshot(player.getUUID(), null);
        }
        BetterDisplayCombat.CombatState combat = BetterDisplayCombat.combatStateOf(player);
        ItemStack leftHand = combat.drawn() ? player.getOffhandItem() : combat.leftHand();
        ItemStack rightHand = combat.drawn() ? player.getMainHandItem() : combat.rightHand();
        VisibleItems visible = SaoMenuModel.isItemFilter(menu.path())
            ? ownedVisibleItems(player, combat, leftHand, rightHand, menu)
            : new VisibleItems(List.of(), menu.focus());
        String focus = SaoMenuModel.validFocus(menu.path(), visible.focus(), visible.items().size()) ? visible.focus() : "";
        SaoMenuProfile profile = SaoMenuModel.showsProfile(menu) ? collectProfile(player, rightHand.isEmpty() ? player.getMainHandItem() : rightHand) : null;
        return new UiStateSnapshot(player.getUUID(), new SaoMenuState(
            menu.path(), focus, visible.items(), profile, menu.scroll(), menu.optionMask() & SHARED_OPTION_MASK,
            combat.drawn(), toMenuItem(leftHand), toMenuItem(rightHand)), anchorYaw, anchorPitch);
    }

    private static SaoMenuProfile collectProfile(ServerPlayer player, ItemStack mainHand) {
        return new SaoMenuProfile(
            player.getGameProfile().name(), (int) Math.ceil(player.getHealth()), (int) Math.ceil(player.getMaxHealth()),
            player.experienceLevel, (int) Math.round(Math.max(0.0D, player.getAttributeValue(Attributes.ARMOR))),
            Math.round(Math.max(0.0F, Math.min(1.0F, player.experienceProgress)) * 100.0F),
            (float) BetterDisplayCombat.weaponAttackDamage(player, mainHand),
            (float) player.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }

    private static VisibleItems ownedVisibleItems(ServerPlayer player, BetterDisplayCombat.CombatState combat,
                                                  ItemStack leftHand, ItemStack rightHand, SaoMenuState menu) {
        Map<Identifier, Integer> counts = new HashMap<>();
        addOwnedItem(counts, leftHand);
        addOwnedItem(counts, rightHand);
        for (ItemStack item : combat.toolSkillSlots()) {
            addOwnedItem(counts, item);
        }
        // 双持时主手/副手就是真实的“选中快捷栏槽”和“副手槽”，必须一起统计，
        // 否则别人看到的面板里武器数量会少两把。
        for (int slot = InventoryMenu.ARMOR_SLOT_START; slot <= InventoryMenu.SHIELD_SLOT; slot++) {
            addOwnedItem(counts, player.inventoryMenu.slots.get(slot).getItem());
        }
        List<SaoMenuItem> items = new ArrayList<>(SaoMenuState.MAX_ITEMS);
        String focus = menu.focus();
        for (int i = 0; i < Math.min(menu.inventory().size(), SaoMenuState.MAX_ITEMS); i++) {
            SaoMenuItem requested = menu.inventory().get(i);
            int available = counts.getOrDefault(requested.id(), 0);
            int count = Math.min(Math.min(requested.count(), available), 99);
            if (menu.focus().equals("item_" + i)) {
                focus = count > 0 ? "item_" + items.size() : "";
            }
            if (count > 0) {
                items.add(new SaoMenuItem(requested.id(), count));
                counts.put(requested.id(), available - count);
            }
        }
        return new VisibleItems(items, focus);
    }

    private static void addOwnedItem(Map<Identifier, Integer> counts, ItemStack stack) {
        SaoMenuItem item = toMenuItem(stack);
        if (item != null) {
            counts.merge(item.id(), item.count(), Integer::sum);
        }
    }

    public static SaoMenuItem toMenuItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || id.toString().length() > SaoMenuCodec.MAX_ENTRY_ID_LENGTH) {
            return null;
        }
        return new SaoMenuItem(id, Math.min(99, stack.getCount()));
    }

    private record VisibleItems(List<SaoMenuItem> items, String focus) {}

    private static final class SharedMenu {
        private final ServerPlayer source;
        private final ServerLevel level;
        private final float anchorYaw;
        private final float anchorPitch;
        private final Set<ServerPlayer> viewers = new HashSet<>();
        private SaoMenuState menu;
        private int lastUpdateTick;
        private int lastPublishedTick = -1;
        private boolean pending;
        /** 上次发布时的装备/手持武器指纹；一变就立即重发，不必等心跳。 */
        private int publishedFingerprint = Integer.MIN_VALUE;

        private SharedMenu(ServerPlayer source, float anchorYaw, float anchorPitch) {
            this.source = source;
            this.level = source.level();
            this.anchorYaw = anchorYaw;
            this.anchorPitch = anchorPitch;
        }

        /**
         * 装备与手持武器的指纹。
         *
         * <p>面板上的人形模型和装备格是从客户端实体实时读的，但数值（攻击/防御）
         * 走的是服务端快照。换装备后不重发，面板就会一边换模型一边显示旧数值。
         */
        private int equipmentFingerprint() {
            int hash = 1;
            for (EquipmentSlot slot : FINGERPRINT_SLOTS) {
                ItemStack stack = source.getItemBySlot(slot);
                hash = hash * 31 + (stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack));
                hash = hash * 31 + stack.getCount();
            }
            BetterDisplayCombat.CombatState combat = BetterDisplayCombat.combatStateOf(source);
            hash = hash * 31 + (combat.drawn() ? 1 : 0);
            hash = hash * 31 + ItemStack.hashItemAndComponents(combat.leftHand());
            hash = hash * 31 + ItemStack.hashItemAndComponents(combat.rightHand());
            return hash;
        }
    }
}

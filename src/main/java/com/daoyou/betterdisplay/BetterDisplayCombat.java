package com.daoyou.betterdisplay;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class BetterDisplayCombat {
    private static final String DATA_ROOT = BetterDisplayMod.MODID + ":combat_slots";
    private static final String PLAYER_PERSISTED = "PlayerPersisted";
    private static final String LEFT_KEY = "left";
    private static final String RIGHT_KEY = "right";
    private static final String DRAWN_KEY = "drawn";
    private static final String ORIGINAL_MAIN_KEY = "original_main";
    private static final String ORIGINAL_OFF_KEY = "original_off";
    private static final String ORIGINAL_SELECTED_KEY = "original_selected";
    private static final String TOOL_SKILL_KEY_PREFIX = "tool_skill_";
    public static final int TOOL_SKILL_SLOTS = 7;
    private static final int PROTOCOL_VERSION = 5;
    private static final int OFFHAND_MINING_TIMEOUT_TICKS = 12;
    private static final Map<UUID, HandMining> HAND_MINING = new HashMap<>();
    /** 副手独立冷却：原版只维护主手那次攻击的 ticker。 */
    /** 每只手最近一次攻击的 tick：index 0 = 左手，1 = 右手。 */
    private static final Map<UUID, long[]> HAND_ATTACK_TICKS = new HashMap<>();
    /** 每只手当前的冷却长度（tick）。 */
    private static final Map<UUID, int[]> HAND_ATTACK_DELAYS = new HashMap<>();
    private static final Map<UUID, Double> SENT_ATTACK_DAMAGE = new HashMap<>();
    private static final Map<UUID, HeldHands> SENT_HELD_HANDS = new HashMap<>();
    private static final SimpleChannel CHANNEL = ChannelBuilder.named(Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "combat"))
        .networkProtocolVersion(PROTOCOL_VERSION)
        .acceptedVersions(Channel.VersionTest.exact(PROTOCOL_VERSION))
        .simpleChannel();

    /** 攻击链路诊断日志（写入 latest.log，不打扰玩家）。 */
    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger("betterdisplay/combat");

    private static ClientState clientState = ClientState.EMPTY;
    private static double clientAttackDamage = 1.0D;

    private BetterDisplayCombat() {
    }

    public static void init() {
        CHANNEL.messageBuilder(EquipHandPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .codec(EquipHandPacket.STREAM_CODEC)
            .consumerMainThread(EquipHandPacket::handle)
            .add();
        CHANNEL.messageBuilder(ToggleDrawPacket.class, 1, NetworkDirection.PLAY_TO_SERVER)
            .codec(ToggleDrawPacket.STREAM_CODEC)
            .consumerMainThread(ToggleDrawPacket::handle)
            .add();
        CHANNEL.messageBuilder(SyncStatePacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .codec(SyncStatePacket.STREAM_CODEC)
            .consumerMainThread(SyncStatePacket::handle)
            .add();
        CHANNEL.messageBuilder(UnequipHandPacket.class, 3, NetworkDirection.PLAY_TO_SERVER)
            .codec(UnequipHandPacket.STREAM_CODEC)
            .consumerMainThread(UnequipHandPacket::handle)
            .add();
        CHANNEL.messageBuilder(StashHotbarPacket.class, 4, NetworkDirection.PLAY_TO_SERVER)
            .codec(StashHotbarPacket.STREAM_CODEC)
            .consumerMainThread(StashHotbarPacket::handle)
            .add();
        CHANNEL.messageBuilder(MoveHotbarToBackpackPacket.class, 5, NetworkDirection.PLAY_TO_SERVER)
            .codec(MoveHotbarToBackpackPacket.STREAM_CODEC)
            .consumerMainThread(MoveHotbarToBackpackPacket::handle)
            .add();
        CHANNEL.messageBuilder(EquipToolSkillPacket.class, 6, NetworkDirection.PLAY_TO_SERVER)
            .codec(EquipToolSkillPacket.STREAM_CODEC)
            .consumerMainThread(EquipToolSkillPacket::handle)
            .add();
        CHANNEL.messageBuilder(UnequipToolSkillPacket.class, 7, NetworkDirection.PLAY_TO_SERVER)
            .codec(UnequipToolSkillPacket.STREAM_CODEC)
            .consumerMainThread(UnequipToolSkillPacket::handle)
            .add();
        CHANNEL.messageBuilder(OffhandAttackPacket.class, 8, NetworkDirection.PLAY_TO_SERVER)
            .codec(OffhandAttackPacket.STREAM_CODEC)
            .consumerMainThread(OffhandAttackPacket::handle)
            .add();
        CHANNEL.messageBuilder(RemoteUiStatePacket.class, 9, NetworkDirection.PLAY_TO_CLIENT)
            .codec(RemoteUiStateMessage.STREAM_CODEC)
            .consumerMainThread(RemoteUiStateMessage::handle)
            .add();
        CHANNEL.messageBuilder(SaoMenuUpdatePacket.class, 10, NetworkDirection.PLAY_TO_SERVER)
            .codec(SaoMenuUpdateMessage.STREAM_CODEC)
            .consumerMainThread(SaoMenuUpdateMessage::handle)
            .add();
        CHANNEL.messageBuilder(HandActionPacket.class, 12, NetworkDirection.PLAY_TO_SERVER)
            .codec(HandActionPacket.STREAM_CODEC)
            .consumerMainThread(HandActionPacket::handle)
            .add();
        CHANNEL.messageBuilder(HotbarRowPacket.class, 13, NetworkDirection.PLAY_TO_SERVER)
            .codec(HotbarRowPacket.STREAM_CODEC)
            .consumerMainThread(HotbarRowPacket::handle)
            .add();
        TickEvent.PlayerTickEvent.Post.BUS.addListener(BetterDisplayCombat::onPlayerTick);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(BetterDisplayCombat::onPlayerLoggedIn);
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(BetterDisplayCombat::onPlayerRespawn);
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(BetterDisplayCombat::onMainHandMining);
        TickEvent.ServerTickEvent.Post.BUS.addListener(BetterDisplayCombat::onServerTick);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(BetterDisplayCombat::onPlayerLoggedOut);
        PlayerEvent.PlayerChangedDimensionEvent.BUS.addListener(BetterDisplayCombat::onPlayerChangedDimension);
        ServerStoppedEvent.BUS.addListener(event -> RemoteUiSync.onServerStopped(event.getServer()));
        CHANNEL.build();
    }

    private static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        RemoteUiSync.onServerTick(event.server());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            stopHandMining(serverPlayer);
            SENT_ATTACK_DAMAGE.remove(serverPlayer.getUUID());
            SENT_HELD_HANDS.remove(serverPlayer.getUUID());
            HAND_ATTACK_TICKS.remove(serverPlayer.getUUID());
            QuickHotbarUse.clear(serverPlayer);
            HAND_ATTACK_DELAYS.remove(serverPlayer.getUUID());
            RemoteUiSync.onPlayerLogout(serverPlayer);
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuickHotbarUse.clear(player);
            RemoteUiSync.onPlayerChangedWorld(player);
        }
    }

    public static void requestEquip(int menuSlot, HandSlot handSlot) {
        CHANNEL.send(new EquipHandPacket(menuSlot, handSlot), PacketDistributor.SERVER.noArg());
    }

    public static void requestUnequip(HandSlot handSlot) {
        CHANNEL.send(new UnequipHandPacket(handSlot), PacketDistributor.SERVER.noArg());
    }

    public static void requestToggleDrawn() {
        CHANNEL.send(new ToggleDrawPacket(), PacketDistributor.SERVER.noArg());
    }

    public static void requestStashHotbar() {
        CHANNEL.send(new StashHotbarPacket(), PacketDistributor.SERVER.noArg());
    }

    public static void requestMoveHotbarToBackpack(int menuSlot) {
        CHANNEL.send(new MoveHotbarToBackpackPacket(menuSlot), PacketDistributor.SERVER.noArg());
    }

    public static void requestEquipToolSkill(int menuSlot, int toolSkillSlot) {
        CHANNEL.send(new EquipToolSkillPacket(menuSlot, toolSkillSlot), PacketDistributor.SERVER.noArg());
    }

    public static void requestUnequipToolSkill(int toolSkillSlot) {
        CHANNEL.send(new UnequipToolSkillPacket(toolSkillSlot), PacketDistributor.SERVER.noArg());
    }

    public static void requestOffhandAttack(int targetEntityId) {
        CHANNEL.send(new OffhandAttackPacket(targetEntityId), PacketDistributor.SERVER.noArg());
    }

    // ===================== 按手动作请求（客户端 → 服务端）=====================

    /** 让指定手开始/继续挖掘这个方块。同一方块重复发送表示“还按着”。 */
    public static void requestHandMining(HandSlot hand, BlockHitResult target) {
        if (target == null) {
            return;
        }
        sendHandAction(new HandActionPacket(hand, HandActionType.MINE_START,
            target.getBlockPos(), target.getDirection(), -1));
    }

    /** 松手：停止指定手的挖掘。 */
    public static void requestHandMiningStop(HandSlot hand) {
        sendHandAction(new HandActionPacket(hand, HandActionType.MINE_STOP, null, null, -1));
    }

    /** 让指定手执行一次右键类动作（锄地/放置/使用）。 */
    public static void requestHandUse(HandSlot hand, BlockHitResult target) {
        if (target == null) {
            return;
        }
        sendHandAction(new HandActionPacket(hand, HandActionType.USE,
            target.getBlockPos(), target.getDirection(), -1));
    }

    /** 用数字键使用对应的快捷栏物品。 */
    public static void requestQuickSlot(int slotIndex, BlockPos pos, Direction face) {
        sendHandAction(new HandActionPacket(HandSlot.RIGHT, HandActionType.QUICK_USE,
            pos, face, slotIndex));
    }

    /** 开始或松开数字键发起的进食和饮用。 */
    public static void requestQuickConsume(int slotIndex, boolean start) {
        sendHandAction(new HandActionPacket(HandSlot.RIGHT,
            start ? HandActionType.CONSUME_START : HandActionType.CONSUME_STOP,
            null, null, slotIndex));
    }

    /** 让指定手对实体执行“右键功能”（使用物品/交互）。 */
    public static void requestHandInteract(HandSlot hand, int targetEntityId) {
        sendHandAction(new HandActionPacket(hand, HandActionType.INTERACT, null, null, targetEntityId));
    }

    /** 停止指定手正在进行的“右键功能”使用（松手时调用）。 */
    public static void requestHandUseItemStop(HandSlot hand) {
        sendHandAction(new HandActionPacket(hand, HandActionType.USE_ITEM_STOP, null, null, -1));
    }

    /** 让指定手对空气执行“右键功能”（吃药、拉弓、放方块等）。 */
    public static void requestHandUseItem(HandSlot hand) {
        sendHandAction(new HandActionPacket(hand, HandActionType.USE_ITEM, null, null, -1));
    }

    /** 让指定手攻击实体。 */
    public static void requestHandAttack(HandSlot hand, int targetEntityId) {
        sendHandAction(new HandActionPacket(hand, HandActionType.ATTACK, null, null, targetEntityId));
    }

    private static void sendHandAction(HandActionPacket packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }

    static void requestHotbarRow(boolean scrollUp) {
        CHANNEL.send(new HotbarRowPacket(scrollUp), PacketDistributor.SERVER.noArg());
    }

    private static void offhandAttack(ServerPlayer player, int targetEntityId) {
        attackWithHand(player, HandSlot.LEFT, targetEntityId);
    }

    /**
     * 用指定手攻击目标。
     *
     * <p>走的是原版 {@link Player#attack} 那条路径：物品攻速、附魔、暴击、横扫、
     * 命中判定全部复用原版。原版只有一个 {@code attackStrengthTicker}，副手
     * 没有自己的冷却，所以双刀流下副手的冷却由 {@link OffhandCooldown} 单独维护。
     */
    private static void attackWithHand(ServerPlayer player, HandSlot hand, int targetEntityId) {
        if (!canActWithHand(player, hand)) {
            LOG.info("[attack] rejected hand={} reason=canAct handItem={}", hand, activeHand(player, hand));
            return;
        }
        var target = player.level().getEntity(targetEntityId);
        if (!(target instanceof LivingEntity living) || target == player || !living.isAlive()) {
            LOG.info("[attack] rejected hand={} reason=invalidTarget id={}", hand, targetEntityId);
            return;
        }
        boolean inRange = player.isWithinAttackRange(target.getBoundingBox(), 0.0D);
        boolean los = player.hasLineOfSight(target);
        if (!inRange || !los) {
            LOG.info("[attack] rejected hand={} reason=range/los inRange={} los={} dist={}",
                hand, inRange, los, player.distanceTo(target));
            return;
        }
        if (target instanceof Player other && !player.canHarmPlayer(other)) {
            LOG.info("[attack] rejected hand={} reason=canHarmPlayer", hand);
            return;
        }
        // 两只手各用各的冷却。绝不读原版 attackStrengthTicker：
        // 它由双手共享，一边出刀就会把另一边判成 0，造成“只能预输入一只手”。
        if (!handCharged(player, hand)) {
            LOG.info("[attack] rejected hand={} reason=cooldown", hand);
            return;
        }
        stopHandMining(player, hand);
        player.resetLastActionTime();
        boolean attacked = withHandAsMain(player, hand, () -> {
            clearDualWieldInvulnerability(player, living);
            // 原版 attack() 内部会按这把武器的攻速/附魔/暴击/横扫结算伤害。
            player.attack(target);
            return true;
        });
        if (attacked) {
            LOG.info("[attack] ok hand={} target={} hp={}", hand, targetEntityId, living.getHealth());
            player.swing(hand == HandSlot.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            resetHandCooldown(player, hand);
            syncToClient(player);
        }
    }

    /**
     * 清掉目标身上由“自己上一次攻击”造成的受击无敌窗口。
     *
     * <p>原版每次受伤后会给目标约 10 tick 的无敌时间，期间伤害不超过上次就直接丢弃。
     * 双持两把剑各自冷却都短于这个窗口，于是会出现“一刀命中后，另一把剑怎么砍都不掉血”。
     * 这里只在“上次伤害确实来自本人”时清窗口，不会影响其它玩家打出的无敌帧。
     */
    private static void clearDualWieldInvulnerability(Player player, LivingEntity target) {
        if (target.invulnerableTime <= 0) {
            return;
        }
        var last = target.getLastDamageSource();
        boolean ownHit = last != null && last.getEntity() == player;
        if (!ownHit) {
            return;
        }
        target.invulnerableTime = 0;
        LOG.info("[attack] cleared own invulnerability for dual wield, target={}", target.getId());
    }

    /**
     * 两只手各自独立的攻击冷却。
     *
     * <p>原版只有一个共享的 attackStrengthTicker，两只手共用它就会互相打架：
     * 一边刚出刀，另一边就被判“没蓄满”。所以两只手都用这里自己的计时，
     * 完全不读原版 ticker。
     */
    private static boolean handCharged(ServerPlayer player, HandSlot hand) {
        long[] ticks = HAND_ATTACK_TICKS.get(player.getUUID());
        int[] delays = HAND_ATTACK_DELAYS.get(player.getUUID());
        if (ticks == null || delays == null) {
            return true;
        }
        int index = hand.ordinal();
        long now = player.level().getGameTime();
        return now - ticks[index] >= delays[index];
    }

    private static void resetHandCooldown(ServerPlayer player, HandSlot hand) {
        long[] ticks = HAND_ATTACK_TICKS.computeIfAbsent(player.getUUID(), ignored -> new long[2]);
        int[] delays = HAND_ATTACK_DELAYS.computeIfAbsent(player.getUUID(), ignored -> new int[2]);
        int index = hand.ordinal();
        ticks[index] = player.level().getGameTime();
        float delay = 20.0F / Math.max(0.1F, (float) handAttackSpeed(player, hand));
        delays[index] = Math.max(1, Math.round(delay));
    }

    /** 指定手武器的攻速，取自那只手物品自己的属性修饰符，算法同原版。 */
    private static double handAttackSpeed(ServerPlayer player, HandSlot hand) {
        AttributeInstance speed = new AttributeInstance(Attributes.ATTACK_SPEED, ignored -> {});
        speed.setBaseValue(player.getAttributeBaseValue(Attributes.ATTACK_SPEED));
        EquipmentSlot slot = hand == HandSlot.LEFT ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        activeHand(player, hand).forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_SPEED)) {
                speed.addOrUpdateTransientModifier(modifier);
            }
        });
        return speed.getValue();
    }

    private static boolean canActWithOffhand(ServerPlayer player) {
        return canActWithHand(player, HandSlot.LEFT);
    }

    static boolean hasBothHands(Player player) {
        return !player.getMainHandItem().isEmpty() && !player.getOffhandItem().isEmpty();
    }

    private static boolean canActWithHand(ServerPlayer player, HandSlot hand) {
        // 只看这只手有没有东西、玩家能不能动手。
        // 不再要求“双手都是武器/工具”——那个判定链（isWeaponOrTool）对斧、
        // 自定义工具容易漏判，一旦漏判整只手就静默失效。
        return player.isAlive() && !player.isSpectator() && !player.isUsingItem()
            && !activeHand(player, hand).isEmpty();
    }

    /**
     * 服务端按手执行挖掘。
     *
     * <p>原版挖掘状态机只认主手物品，所以每 tick 都要把目标手的物品临时换到主手，
     * 用原版的 getDestroyProgress 算进度，再换回来。碎裂进度通过
     * destroyBlockProgress 下发给客户端，因此玩家能看到正常裂纹。
     */
    private static void updateHandMining(ServerPlayer player, HandSlot hand, BlockPos pos, Direction face) {
        if (pos == null) {
            stopHandMining(player, hand);
            return;
        }
        if (!canActWithHand(player, hand)) {
            stopHandMining(player, hand);
            return;
        }
        if (!player.isWithinBlockInteractionRange(pos, 0.0D)) {
            stopHandMining(player, hand);
            return;
        }
        HandMining active = HAND_MINING.get(player.getUUID());
        long now = player.level().getGameTime();
        if (active != null && active.hand == hand && active.level == player.level() && active.pos.equals(pos)) {
            active.lastRequest = now;                  // 保活
            return;
        }
        stopHandMining(player, hand);
        if (!canMine(player, pos)) {
            return;
        }
        var state = player.level().getBlockState(pos);
        ItemStack tool = activeHand(player, hand).copy();
        boolean allowed = withHandAsMain(player, hand, () -> {
            // 不校验“这把工具能否掉落”：原版允许任何物品挖掘任何方块，
            // 只是速度和掉落物不同（例：斧头挖雪、铁锹挖石头）。
            // 之前在这里用 hasCorrectToolForDrops 拦截，导致雪地等方块完全挖不动。
            var event = ForgeEventFactory.onLeftClickBlock(player, pos, face,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            if (event == null || event.getUseItem() == net.minecraftforge.common.util.Result.DENY
                || player.blockActionRestricted(player.level(), pos, player.gameMode())) {
                return false;
            }
            if (event.getUseBlock() != net.minecraftforge.common.util.Result.DENY && !player.isCreative()) {
                state.attack(player.level(), pos, player);
            }
            return true;
        });
        if (!allowed) {
            return;
        }
        player.resetLastActionTime();
        HAND_MINING.put(player.getUUID(), new HandMining(hand, player.level(), pos, state, tool, now));
    }

    /**
     * 目标方块是否可挖。
     *
     * <p>注意：不能用 player.pick() 做射线检测——它按主手物品算远近，
     * 副手工具挖近距离方块时会永远对不上。这里只做权限与区块状态校验。
     */
    private static boolean canMine(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        return level.isInWorldBounds(pos) && level.hasChunkAt(pos) && level.mayInteract(player, pos)
            && player.isWithinBlockInteractionRange(pos, 0.0D) && !level.getBlockState(pos).isAir();
    }

    private static void tickHandMining(ServerPlayer player) {
        HandMining active = HAND_MINING.get(player.getUUID());
        if (active == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (!canActWithHand(player, active.hand) || active.level != player.level()
            || now - active.lastRequest > OFFHAND_MINING_TIMEOUT_TICKS
            || !canMine(player, active.pos) || player.level().getBlockState(active.pos) != active.block
            || !ItemStack.isSameItemSameComponents(active.tool, activeHand(player, active.hand))) {
            stopHandMining(player);
            return;
        }
        InteractionHand swingHand = active.hand == HandSlot.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (active.ticks++ % 6 == 0) {
            player.swing(swingHand, true);
        }
        active.progress += withHandAsMain(player, active.hand,
            () -> active.block.getDestroyProgress(player, active.level, active.pos));
        if (player.isCreative() || active.progress >= 1.0F) {
            BlockPos target = active.pos;
            stopHandMining(player);
            withHandAsMain(player, active.hand, () -> player.gameMode.destroyBlock(target));
            return;
        }
        int stage = Math.min(9, (int) (active.progress * 10.0F));
        if (stage != active.stage) {
            active.level.destroyBlockProgress(player.getId(), active.pos, stage);
            sendCrackToSelf(player, active.pos, stage);
            active.stage = stage;
        }
    }

    private static void handUse(ServerPlayer player, HandSlot slot, BlockPos pos, Direction face) {
        ServerLevel level = player.level();
        if (pos == null || face == null || !player.isWithinBlockInteractionRange(pos, 1.0D)
            || !level.isInWorldBounds(pos) || !level.isLoaded(pos) || !level.mayInteract(player, pos)) {
            return;
        }
        HitResult picked = player.pick(player.blockInteractionRange(), 1.0F, false);
        BlockHitResult hit = picked instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
            && block.getBlockPos().equals(pos) && block.getDirection() == face
                ? block : new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5D), face, pos, false);
        useHand(player, slot, () -> useBlockWithHand(player, slot, hit));
    }

    private static InteractionResult useBlockWithHand(ServerPlayer player, HandSlot slot, BlockHitResult hit) {
        // Vanilla only opens default block interactions with MAIN_HAND.
        InteractionResult result = withHandAsMain(player, slot, () -> player.gameMode.useItemOn(
            player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit));
        if (slot == HandSlot.LEFT && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            // Some use-on-block items, such as brushes, start a sustained action.
            player.stopUsingItem();
            player.startUsingItem(InteractionHand.OFF_HAND);
        }
        return result;
    }

    private static void handInteract(ServerPlayer player, HandSlot slot, int targetEntityId) {
        var entity = player.level().getEntity(targetEntityId);
        if (entity == null || entity == player || !entity.isAlive()
            || !player.level().getWorldBorder().isWithinBounds(entity.blockPosition())
            || !player.isWithinEntityInteractionRange(entity, 0.0D)) {
            return;
        }

        InteractionHand hand = interactionHand(slot);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        useHand(player, slot, () -> {
            var hit = entity.getBoundingBox().clip(eye, end);
            if (hit.isPresent()) {
                InteractionResult precise = ForgeHooks.onInteractEntityAt(entity, player,
                    hit.get().subtract(entity.position()), hand);
                if (precise.consumesAction() || precise == InteractionResult.FAIL) {
                    return precise;
                }
            }
            return player.interactOn(entity, hand);
        });
    }

    private static void handUseItem(ServerPlayer player, HandSlot slot) {
        useHand(player, slot, () -> InteractionResult.PASS);
    }

    private static void useHand(ServerPlayer player, HandSlot slot, Supplier<InteractionResult> interact) {
        InteractionHand hand = interactionHand(slot);
        ItemStack held = player.getItemInHand(hand);
        if (!hasBothHands(player) || !canActWithHand(player, slot) || player.containerMenu != player.inventoryMenu
            || !held.isItemEnabled(player.level().enabledFeatures())) {
            return;
        }

        boolean wasSneaking = player.isShiftKeyDown();
        try {
            // Shift selects the normal right-click action here. Vanilla sneaking skips block activation.
            player.setShiftKeyDown(false);
            var consumable = held.get(DataComponents.CONSUMABLE);
            boolean useConsumableFirst = consumable != null && consumable.canConsume(player, held);
            InteractionResult result = useConsumableFirst
                ? player.gameMode.useItem(player, player.level(), held, hand) : InteractionResult.PASS;
            if (!result.consumesAction() && result != InteractionResult.FAIL && !player.isUsingItem()) {
                result = interact.get();
            }
            if (!useConsumableFirst && !result.consumesAction() && result != InteractionResult.FAIL && !player.isUsingItem()) {
                ItemStack stack = player.getItemInHand(hand);
                if (!stack.isEmpty()) {
                    result = player.gameMode.useItem(player, player.level(), stack, hand);
                }
            }
            if (result instanceof InteractionResult.Success success
                && success.swingSource() != InteractionResult.SwingSource.NONE) {
                player.swing(hand, true);
            }
        } finally {
            player.setShiftKeyDown(wasSneaking);
        }
        syncInventoryMenus(player);
        syncToClient(player);
    }

    private static InteractionHand interactionHand(HandSlot slot) {
        return slot == HandSlot.LEFT ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private static void handStopUseItem(ServerPlayer player, HandSlot slot) {
        if (player.isUsingItem() && player.getUsedItemHand() == interactionHand(slot)) {
            player.releaseUsingItem();
            syncInventoryMenus(player);
            syncToClient(player);
        }
    }

    private static void stopHandMining(ServerPlayer player, HandSlot hand) {
        HandMining active = HAND_MINING.get(player.getUUID());
        if (active == null || active.hand != hand) {
            return;
        }
        stopHandMining(player);
    }

    private static void stopHandMining(ServerPlayer player) {
        HandMining active = HAND_MINING.remove(player.getUUID());
        if (active != null && active.stage >= 0) {
            active.level.destroyBlockProgress(player.getId(), active.pos, -1);
            sendCrackToSelf(player, active.pos, -1);
        }
    }

    /**
     * 把方块破坏进度发给破坏者本人。
     *
     * <p>原版 {@code ServerLevel.destroyBlockProgress} 在循环里显式跳过
     * {@code player.getId() == breakerId} 的玩家——它假定破坏者客户端有自己的
     * 本地预测。我们的挖掘由服务端驱动，客户端没有预测，所以必须自己补发，
     * 否则方块会正常挖掉但全程没有裂纹。
     */
    private static void sendCrackToSelf(ServerPlayer player, BlockPos pos, int stage) {
        if (player.connection == null) {
            return;
        }
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                player.getId(), pos, stage));
    }

    /**
     * 玩家用主手开始挖掘时，停掉左手正在进行的挖掘，避免两手同时推进同一个方块。
     */
    private static void onMainHandMining(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HandMining active = HAND_MINING.get(player.getUUID());
            if (active != null && active.hand == HandSlot.RIGHT) {
                stopHandMining(player);
            }
        }
    }

    private static <T> T withOffhandAsMain(ServerPlayer player, Supplier<T> action) {
        return withHandAsMain(player, HandSlot.LEFT, action);
    }


    private static <T> T withHandAsMain(ServerPlayer player, HandSlot hand, Supplier<T> action) {
        if (hand == HandSlot.RIGHT) {
            return action.get();
        }
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack main = player.getMainHandItem();
        ItemStack offhand = player.getOffhandItem();
        Map<AttributeInstance, AttributeInstance> previousAttributes = new HashMap<>();
        try {
            replaceSlotAttributes(player, main, EquipmentSlot.MAINHAND, false, previousAttributes);
            replaceSlotAttributes(player, offhand, EquipmentSlot.OFFHAND, false, previousAttributes);
            replaceSlotAttributes(player, offhand, EquipmentSlot.MAINHAND, true, previousAttributes);
            inventory.setItem(selected, offhand);
            inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            return action.get();
        } finally {
            ItemStack updated = inventory.getItem(selected);
            inventory.setItem(selected, main);
            inventory.setItem(Inventory.SLOT_OFFHAND, updated);
            previousAttributes.forEach(AttributeInstance::replaceFrom);
        }
    }

    private static void replaceSlotAttributes(ServerPlayer player, ItemStack stack, EquipmentSlot slot, boolean add,
                                              Map<AttributeInstance, AttributeInstance> previousAttributes) {
        stack.forEachModifier(slot, (attribute, modifier) -> {
            AttributeInstance live = player.getAttribute(attribute);
            if (live == null) {
                return;
            }
            previousAttributes.computeIfAbsent(live, instance -> {
                AttributeInstance backup = new AttributeInstance(attribute, ignored -> {});
                backup.replaceFrom(instance);
                return backup;
            });
            if (add) {
                live.addOrUpdateTransientModifier(modifier);
            } else if (modifier.equals(live.getModifier(modifier.id()))) {
                live.removeModifier(modifier);
            }
        });
    }

    private static final class HandMining {
        private final HandSlot hand;
        private final ServerLevel level;
        private final BlockPos pos;
        private final net.minecraft.world.level.block.state.BlockState block;
        private final ItemStack tool;
        private long lastRequest;
        private int ticks;
        private float progress;
        private int stage = -1;

        private HandMining(HandSlot hand, ServerLevel level, BlockPos pos,
                           net.minecraft.world.level.block.state.BlockState block,
                           ItemStack tool, long lastRequest) {
            this.hand = hand;
            this.level = level;
            this.pos = pos;
            this.block = block;
            this.tool = tool;
            this.lastRequest = lastRequest;
        }
    }

    public static ItemStack clientLeftHand() {
        return clientState.leftHand().copy();
    }

    public static ItemStack clientRightHand() {
        return clientState.rightHand().copy();
    }

    public static boolean clientDrawn() {
        return clientState.drawn();
    }

    public static double clientAttackDamage() {
        return clientAttackDamage;
    }

    static void clearClientState() {
        clientState = ClientState.EMPTY;
        clientAttackDamage = 1.0D;
    }

    public static ItemStack clientToolSkillSlot(int slot) {
        if (slot < 0 || slot >= clientState.toolSkillSlots().size()) {
            return ItemStack.EMPTY;
        }
        return clientState.toolSkillSlots().get(slot).copy();
    }

    public static void syncToClient(ServerPlayer player) {
        double attackDamage = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        SENT_ATTACK_DAMAGE.put(player.getUUID(), attackDamage);
        CHANNEL.send(new SyncStatePacket(stateOf(player), attackDamage), PacketDistributor.PLAYER.with(player));
    }

    static double weaponAttackDamage(Player player, ItemStack weapon) {
        AttributeInstance damage = new AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> {});
        damage.setBaseValue(player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE));
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
                damage.addOrUpdateTransientModifier(modifier);
            }
        });
        return damage.getValue();
    }

    /**
     * 手上武器变了就重新算一次攻击力。
     *
     * <p>服务端在鼠标中键/数字键/滚轮换槽、以及战斗槽换武器时都会改主手物品，
     * 但 ATTACK_DAMAGE 的修饰符要等属性表刷新才生效；只靠「上次发的值 != 当前值」
     * 去比较，会在刷新前后读到同一个旧值而漏发。这里显式记录双手物品，
     * 物品一变就重算并同步。
     */
    private static void syncHeldHands(ServerPlayer player) {
        HeldHands held = new HeldHands(player.getMainHandItem().copy(), player.getOffhandItem().copy());
        HeldHands last = SENT_HELD_HANDS.get(player.getUUID());
        if (held.equals(last)) {
            return;
        }
        SENT_HELD_HANDS.put(player.getUUID(), held);
        syncToClient(player);
    }

    private record HeldHands(ItemStack main, ItemStack offhand) {
        @Override
        public boolean equals(Object other) {
            return other instanceof HeldHands hands
                && ItemStack.matches(main, hands.main)
                && ItemStack.matches(offhand, hands.offhand);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(main) * 31 + ItemStack.hashItemAndComponents(offhand);
        }
    }

    static void sendRemoteUiState(ServerPlayer target, RemoteUiStatePacket packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(target));
    }

    static void sendRemoteUiState(PacketDistributor.PacketTarget target, RemoteUiStatePacket packet) {
        CHANNEL.send(packet, target);
    }

    static void sendSaoMenuUpdate(SaoMenuUpdatePacket packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        if (event.player() instanceof ServerPlayer serverPlayer) {
            tickHandMining(serverPlayer);
            QuickHotbarUse.tick(serverPlayer);
            refreshDualWield(serverPlayer);
            // Vanilla does not synchronize ATTACK_DAMAGE to the owning client.
            Double lastDamage = SENT_ATTACK_DAMAGE.get(serverPlayer.getUUID());
            double attackDamage = serverPlayer.getAttributeValue(Attributes.ATTACK_DAMAGE);
            if (lastDamage == null || Double.compare(lastDamage, attackDamage) != 0) {
                syncToClient(serverPlayer);
            }
            // 换武器时属性刷新可能晚一拍，用双手物品本身做变更检测。
            syncHeldHands(serverPlayer);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            recoverStrandedStacks(serverPlayer);
            refreshDualWield(serverPlayer);
            syncToClient(serverPlayer);
        }
    }

    private static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuickHotbarUse.clear(player);
            RemoteUiSync.onPlayerChangedWorld(player);
            syncToClient(player);
        }
    }

    public static boolean isWeaponOrTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.has(DataComponents.WEAPON)
            || stack.has(DataComponents.KINETIC_WEAPON)
            || stack.has(DataComponents.PIERCING_WEAPON)
            || stack.has(DataComponents.TOOL)
            || isSword(stack)
            || isPickaxe(stack)
            || stack.getItem() instanceof AxeItem
            || stack.getItem() instanceof HoeItem
            || stack.getItem() instanceof ProjectileWeaponItem
            || stack.getItem() instanceof ShearsItem
            || stack.getItem() instanceof ShovelItem
            || stack.getItem() instanceof TridentItem
            || stack.getItem() instanceof BowItem;
    }

    private static void equipFromMenuSlot(ServerPlayer player, int menuSlot, HandSlot handSlot) {
        if (menuSlot < 0 || menuSlot >= player.inventoryMenu.slots.size()) {
            return;
        }
        var slot = player.inventoryMenu.slots.get(menuSlot);
        if (!slot.mayPickup(player)) {
            return;
        }
        ItemStack selected = slot.getItem();
        if (selected.isEmpty()) {
            return;
        }
        ItemStack old = activeHand(player, handSlot).copy();
        ItemStack incoming = selected.copy();
        // 手上有东西就换回原槽位，绝不凭空丢弃。
        if (!old.isEmpty() && slot.mayPlace(old)) {
            slot.set(old.copy());
        } else {
            slot.set(ItemStack.EMPTY);
            if (!old.isEmpty()) {
                player.getInventory().placeItemBackInInventory(old);
            }
        }
        // 直接写真实装备槽，不再经过虚拟存档。
        player.setItemSlot(handSlot == HandSlot.RIGHT ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND, incoming.copy());
        swapHandAttributes(player, old, incoming);
        syncInventoryMenus(player);
        syncToClient(player);
    }

    private static void moveHotbarMenuSlotToBackpack(ServerPlayer player, int menuSlot) {
        if (menuSlot < 36 || menuSlot >= 45 || menuSlot >= player.inventoryMenu.slots.size()) {
            return;
        }
        var slot = player.inventoryMenu.slots.get(menuSlot);
        if (!slot.mayPickup(player)) {
            return;
        }
        ItemStack source = slot.getItem();
        if (source.isEmpty()) {
            return;
        }
        ItemStack moving = source.copy();
        int moved = BetterDisplayInventory.insertIntoBackpack(player.getInventory(), moving);
        if (moved <= 0) {
            return;
        }
        ItemStack taken = source.copy();
        taken.setCount(moved);
        source.shrink(moved);
        slot.set(source.isEmpty() ? ItemStack.EMPTY : source);
        slot.onTake(player, taken);
        syncInventoryMenus(player);
    }

    private static void stashHotbar(ServerPlayer player) {
        Inventory inv = player.getInventory();
        boolean drawn = stateOf(player).drawn();
        boolean changed = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack hotbar = inv.getItem(slot);
            if (hotbar.isEmpty()) {
                continue;
            }
            if (drawn && slot == inv.getSelectedSlot()) {
                continue;
            }
            ItemStack moving = hotbar.copy();
            int moved = BetterDisplayInventory.insertIntoBackpack(inv, moving);
            if (moved > 0) {
                hotbar.shrink(moved);
                if (hotbar.isEmpty()) {
                    inv.setItem(slot, ItemStack.EMPTY);
                } else {
                    inv.setItem(slot, hotbar);
                }
                changed = true;
            }
        }
        if (changed) {
            syncInventoryMenus(player);
        }
    }

    private static void equipToolSkill(ServerPlayer player, int menuSlot, int toolSkillSlot) {
        if (toolSkillSlot < 0 || toolSkillSlot >= TOOL_SKILL_SLOTS || menuSlot < 0 || menuSlot >= player.inventoryMenu.slots.size()) {
            return;
        }
        var slot = player.inventoryMenu.slots.get(menuSlot);
        if (!slot.mayPickup(player)) {
            return;
        }
        ItemStack selected = slot.getItem();
        if (selected.isEmpty()) {
            return;
        }
        ItemStack incoming = selected.copy();
        ItemStack old = getToolSkill(player, toolSkillSlot);
        if (!old.isEmpty() && slot.mayPlace(old)) {
            slot.set(old.copy());
        } else {
            slot.set(ItemStack.EMPTY);
            if (!old.isEmpty()) {
                player.getInventory().placeItemBackInInventory(old);
            }
        }
        setToolSkill(player, toolSkillSlot, incoming);
        syncInventoryMenus(player);
        syncToClient(player);
    }

    private static void unequipToolSkill(ServerPlayer player, int toolSkillSlot) {
        if (toolSkillSlot < 0 || toolSkillSlot >= TOOL_SKILL_SLOTS) {
            return;
        }
        ItemStack stored = getToolSkill(player, toolSkillSlot);
        if (stored.isEmpty()) {
            return;
        }
        ItemStack remaining = stored.copy();
        BetterDisplayInventory.insertIntoBackpack(player.getInventory(), remaining);
        if (remaining.getCount() != stored.getCount()) {
            setToolSkill(player, toolSkillSlot, remaining);
            syncInventoryMenus(player);
            syncToClient(player);
        }
    }

    private static void unequipHand(ServerPlayer player, HandSlot handSlot) {
        ItemStack removed = activeHand(player, handSlot).copy();
        if (removed.isEmpty()) {
            return;
        }
        player.setItemSlot(handSlot == HandSlot.RIGHT ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.getInventory().placeItemBackInInventory(removed);
        syncInventoryMenus(player);
        syncToClient(player);
    }

    /**
     * 双持不再由按键切换，完全由双手物品自动决定。
     *
     * <p>以前 R 键会把双手物品搬进一段虚拟存档再把原槽位清空，任何一步对不上
     * （原槽位写回失败、存档被覆盖）都会让物品凭空消失。现在不再动背包，
     * 主手就是原版主手、副手就是原版副手，物品始终留在原槽位里。
     */
    public static boolean isDualWielding(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return player != null && !player.getMainHandItem().isEmpty()
                && !player.getOffhandItem().isEmpty();
        }
        return isDualWieldPair(serverPlayer.getMainHandItem(), serverPlayer.getOffhandItem());
    }

    /** 双手都拿着可战斗的东西（武器或工具）才算双持。 */
    private static boolean isDualWieldPair(ItemStack main, ItemStack off) {
        return !main.isEmpty() && !off.isEmpty()
            && (isWeaponOrTool(main) || isSword(main))
            && (isWeaponOrTool(off) || isSword(off));
    }

    /** 每 tick 校正：不再持久化“拔刀”状态，也不再改动任何背包槽位。 */
    private static void refreshDualWield(ServerPlayer player) {
        if (player.isUsingItem()) {
            return;
        }
        boolean dual = isDualWieldPair(player.getMainHandItem(), player.getOffhandItem());
        if (root(player).getBooleanOr(DRAWN_KEY, false) != dual) {
            setDrawn(player, dual);
            syncToClient(player);
        }
    }
    /**
     * 立刻把主手属性从旧武器换成新武器，并同步攻击力。
     *
     * <p>setItemSlot 改的物品要到下个 tick 才会重算属性表，这段时间里
     * 面板显示的还是旧武器的攻击力；战斗槽换武器时更是完全靠这里补合成。
     */
    private static void swapHandAttributes(ServerPlayer player, ItemStack oldStack, ItemStack newStack) {
        if (ItemStack.matches(oldStack, newStack)) {
            return;
        }
        Map<AttributeInstance, AttributeInstance> previous = new HashMap<>();
        replaceSlotAttributes(player, oldStack, EquipmentSlot.MAINHAND, false, previous);
        replaceSlotAttributes(player, newStack, EquipmentSlot.MAINHAND, true, previous);
        syncToClient(player);
    }
    private static void syncInventoryMenus(ServerPlayer player) {
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.sendAllDataToRemote();
        }
    }

    /**
     * 救援被旧版拔刀逻辑吞掉的物品。
     *
     * <p>旧实现把双手物品搬进 {@code original_main/original_off/left/right} 这些
     * NBT 键后清空原槽位，退出拔刀时才写回。只要中途失败，物品就只剩在这些键里。
     * 这里在登录时把它们退回背包并清键，避免继续丢失。
     */
    private static void recoverStrandedStacks(ServerPlayer player) {
        CompoundTag root = root(player);
        // 注意：DRAWN_KEY 现在只表示“是否双持”，由 refreshDualWield 维护，不能在这里清。
        if (!root.contains(ORIGINAL_MAIN_KEY) && !root.contains(ORIGINAL_OFF_KEY)
            && !root.contains(ORIGINAL_SELECTED_KEY) && !root.contains(LEFT_KEY) && !root.contains(RIGHT_KEY)) {
            return;
        }
        List<ItemStack> stranded = new ArrayList<>(4);
        // 旧版把双手物品存在这四个键里；原槽位被清空后它们就是唯一副本。
        for (String key : new String[]{ORIGINAL_MAIN_KEY, ORIGINAL_OFF_KEY, LEFT_KEY, RIGHT_KEY}) {
            ItemStack stored = getStored(player, key);
            if (!stored.isEmpty()) {
                stranded.add(stored);
            }
            root.remove(key);
        }
        root.remove(ORIGINAL_SELECTED_KEY);
        if (stranded.isEmpty()) {
            return;
        }
        for (ItemStack stack : stranded) {
            // 主手/副手空着就先放回去，其余进背包，绝不静默丢弃。
            if (player.getMainHandItem().isEmpty()) {
                player.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            } else if (player.getOffhandItem().isEmpty()) {
                player.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
            } else {
                player.getInventory().placeItemBackInInventory(stack.copy());
            }
        }
        player.inventoryMenu.sendAllDataToRemote();
    }

    private static void setOriginalIfMissing(ServerPlayer player, String key, ItemStack stack) {
        CompoundTag root = root(player);
        if (!root.contains(key)) {
            putStack(player, root, key, stack.copy());
        }
    }

    private static ItemStack activeHand(ServerPlayer player, HandSlot handSlot) {
        return handSlot == HandSlot.RIGHT ? player.getMainHandItem() : player.getOffhandItem();
    }

    /** 是不是剑。双持判定在客户端也要用，故公开。 */
    public static boolean isSwordStack(ItemStack stack) {
        return isSword(stack);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.is(Items.WOODEN_SWORD) || stack.is(Items.STONE_SWORD) || stack.is(Items.IRON_SWORD) || stack.is(Items.GOLDEN_SWORD) || stack.is(Items.DIAMOND_SWORD) || stack.is(Items.NETHERITE_SWORD);
    }

    private static boolean isPickaxe(ItemStack stack) {
        return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE) || stack.is(Items.IRON_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE);
    }

    /**
     * 供远程联机界面系统读取战斗槽状态（服务端权威数据）。
     */
    public static CombatState combatStateOf(Player player) {
        return stateOf(player);
    }

    private static CombatState stateOf(Player player) {
        // 双手直接来自真实背包，不再有独立存档。
        return new CombatState(player.getOffhandItem().copy(), player.getMainHandItem().copy(),
            root(player).getBooleanOr(DRAWN_KEY, false), getToolSkillSlots(player));
    }

    private static List<ItemStack> getToolSkillSlots(Player player) {
        List<ItemStack> slots = new ArrayList<>(TOOL_SKILL_SLOTS);
        for (int i = 0; i < TOOL_SKILL_SLOTS; i++) {
            slots.add(getToolSkill(player, i));
        }
        return slots;
    }

    private static ItemStack getToolSkill(Player player, int slot) {
        return getStored(player, TOOL_SKILL_KEY_PREFIX + slot);
    }

    private static void setToolSkill(Player player, int slot, ItemStack stack) {
        putStack(player, root(player), TOOL_SKILL_KEY_PREFIX + slot, stack);
    }

    private static void setDrawn(Player player, boolean drawn) {
        root(player).putBoolean(DRAWN_KEY, drawn);
    }

    private static ItemStack getStored(Player player, String key) {
        CompoundTag root = root(player);
        if (!root.contains(key)) {
            return ItemStack.EMPTY;
        }
        Tag tag = root.get(key);
        if (tag == null) {
            return ItemStack.EMPTY;
        }
        DynamicOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY).copy();
    }

    private static void putStack(Player player, CompoundTag root, String key, ItemStack stack) {
        if (stack.isEmpty()) {
            root.put(key, new CompoundTag());
            return;
        }
        DynamicOps<Tag> ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack.copy()).result().ifPresent(tag -> root.put(key, tag));
    }

    private static CompoundTag root(Player player) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompoundOrEmpty(PLAYER_PERSISTED);
        data.put(PLAYER_PERSISTED, persisted);
        // Move the interim format back to Forge's respawn-persistent tag.
        data.getCompound(DATA_ROOT).ifPresent(interim -> {
            if (!interim.isEmpty()) {
                CompoundTag original = persisted.getCompoundOrEmpty(DATA_ROOT);
                if (!original.isEmpty() && !original.equals(interim)) {
                    persisted.put(DATA_ROOT + "_original_backup", original.copy());
                }
                persisted.put(DATA_ROOT, interim);
            }
            data.remove(DATA_ROOT);
        });
        CompoundTag combat = persisted.getCompoundOrEmpty(DATA_ROOT);
        persisted.put(DATA_ROOT, combat);
        return combat;
    }


    public enum HandSlot {
        LEFT,
        RIGHT
    }

    public record CombatState(ItemStack leftHand, ItemStack rightHand, boolean drawn, List<ItemStack> toolSkillSlots) {
    }

    private record ClientState(ItemStack leftHand, ItemStack rightHand, boolean drawn, List<ItemStack> toolSkillSlots) {
        private static final ClientState EMPTY = new ClientState(ItemStack.EMPTY, ItemStack.EMPTY, false, emptyToolSkillSlots());
    }

    private record EquipHandPacket(int menuSlot, HandSlot handSlot) {
        private static final StreamCodec<RegistryFriendlyByteBuf, EquipHandPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public EquipHandPacket decode(RegistryFriendlyByteBuf buffer) {
                return new EquipHandPacket(buffer.readVarInt(), HandSlot.values()[buffer.readVarInt()]);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, EquipHandPacket packet) {
                buffer.writeVarInt(packet.menuSlot());
                buffer.writeVarInt(packet.handSlot().ordinal());
            }
        };

        private static void handle(EquipHandPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                equipFromMenuSlot(sender, packet.menuSlot(), packet.handSlot());
            }
        }
    }

    private record UnequipHandPacket(HandSlot handSlot) {
        private static final StreamCodec<RegistryFriendlyByteBuf, UnequipHandPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public UnequipHandPacket decode(RegistryFriendlyByteBuf buffer) {
                return new UnequipHandPacket(HandSlot.values()[buffer.readVarInt()]);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, UnequipHandPacket packet) {
                buffer.writeVarInt(packet.handSlot().ordinal());
            }
        };

        private static void handle(UnequipHandPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                unequipHand(sender, packet.handSlot());
            }
        }
    }

    private record StashHotbarPacket() {
        private static final StreamCodec<RegistryFriendlyByteBuf, StashHotbarPacket> STREAM_CODEC = StreamCodec.unit(new StashHotbarPacket());

        private static void handle(StashHotbarPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                stashHotbar(sender);
            }
        }
    }

    private record MoveHotbarToBackpackPacket(int menuSlot) {
        private static final StreamCodec<RegistryFriendlyByteBuf, MoveHotbarToBackpackPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public MoveHotbarToBackpackPacket decode(RegistryFriendlyByteBuf buffer) {
                return new MoveHotbarToBackpackPacket(buffer.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, MoveHotbarToBackpackPacket packet) {
                buffer.writeVarInt(packet.menuSlot());
            }
        };

        private static void handle(MoveHotbarToBackpackPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                moveHotbarMenuSlotToBackpack(sender, packet.menuSlot());
            }
        }
    }

    private record EquipToolSkillPacket(int menuSlot, int toolSkillSlot) {
        private static final StreamCodec<RegistryFriendlyByteBuf, EquipToolSkillPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public EquipToolSkillPacket decode(RegistryFriendlyByteBuf buffer) {
                return new EquipToolSkillPacket(buffer.readVarInt(), buffer.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, EquipToolSkillPacket packet) {
                buffer.writeVarInt(packet.menuSlot());
                buffer.writeVarInt(packet.toolSkillSlot());
            }
        };

        private static void handle(EquipToolSkillPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                equipToolSkill(sender, packet.menuSlot(), packet.toolSkillSlot());
            }
        }
    }

    private record UnequipToolSkillPacket(int toolSkillSlot) {
        private static final StreamCodec<RegistryFriendlyByteBuf, UnequipToolSkillPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public UnequipToolSkillPacket decode(RegistryFriendlyByteBuf buffer) {
                return new UnequipToolSkillPacket(buffer.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, UnequipToolSkillPacket packet) {
                buffer.writeVarInt(packet.toolSkillSlot());
            }
        };

        private static void handle(UnequipToolSkillPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                unequipToolSkill(sender, packet.toolSkillSlot());
            }
        }
    }

    private record OffhandAttackPacket(int targetEntityId) {
        private static final StreamCodec<RegistryFriendlyByteBuf, OffhandAttackPacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public OffhandAttackPacket decode(RegistryFriendlyByteBuf buffer) {
                return new OffhandAttackPacket(buffer.readVarInt());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, OffhandAttackPacket packet) {
                buffer.writeVarInt(packet.targetEntityId());
            }
        };

        private static void handle(OffhandAttackPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                offhandAttack(sender, packet.targetEntityId());
            }
        }
    }

    /** 按手动作的种类。 */
    public enum HandActionType {
        MINE_START, MINE_STOP, USE, ATTACK, QUICK_USE, CONSUME_START, CONSUME_STOP,
        INTERACT, USE_ITEM, USE_ITEM_STOP
    }

    /**
     * 统一的按手动作包：客户端告诉服务端“哪只手、做什么、指向哪”。
     */
    private record HandActionPacket(HandSlot hand, HandActionType action,
                                    BlockPos pos, Direction face, int entityId) {
        private static final StreamCodec<RegistryFriendlyByteBuf, HandActionPacket> STREAM_CODEC =
            StreamCodec.of(
                (buffer, packet) -> {
                    buffer.writeVarInt(packet.hand.ordinal());
                    buffer.writeVarInt(packet.action.ordinal());
                    buffer.writeBoolean(packet.pos != null);
                    if (packet.pos != null) {
                        BlockPos.STREAM_CODEC.encode(buffer, packet.pos);
                        Direction.STREAM_CODEC.encode(buffer, packet.face);
                    }
                    buffer.writeVarInt(packet.entityId);
                },
                buffer -> {
                    HandSlot hand = HandSlot.values()[buffer.readVarInt()];
                    HandActionType action = HandActionType.values()[buffer.readVarInt()];
                    BlockPos pos = null;
                    Direction face = null;
                    if (buffer.readBoolean()) {
                        pos = BlockPos.STREAM_CODEC.decode(buffer);
                        face = Direction.STREAM_CODEC.decode(buffer);
                    }
                    return new HandActionPacket(hand, action, pos, face, buffer.readVarInt());
                });

        private static void handle(HandActionPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            switch (packet.action) {
                case MINE_START -> updateHandMining(sender, packet.hand, packet.pos, packet.face);
                case MINE_STOP -> stopHandMining(sender, packet.hand);
                case USE -> handUse(sender, packet.hand, packet.pos, packet.face);
                case ATTACK -> attackWithHand(sender, packet.hand, packet.entityId);
                case QUICK_USE, CONSUME_START ->
                    QuickHotbarUse.request(sender, packet.entityId, packet.pos, packet.face);
                case CONSUME_STOP -> QuickHotbarUse.release(sender, packet.entityId);
                case INTERACT -> handInteract(sender, packet.hand, packet.entityId);
                case USE_ITEM -> handUseItem(sender, packet.hand);
                case USE_ITEM_STOP -> handStopUseItem(sender, packet.hand);
            }
        }
    }

    private record HotbarRowPacket(boolean scrollUp) {
        private static final StreamCodec<RegistryFriendlyByteBuf, HotbarRowPacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> buffer.writeBoolean(packet.scrollUp),
            buffer -> new HotbarRowPacket(buffer.readBoolean())
        );

        private static void handle(HotbarRowPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer player = context.getSender();
            if (player == null || !player.isAlive() || player.isSpectator() || player.containerMenu != player.inventoryMenu) {
                return;
            }
            QuickHotbarUse.stop(player);
            stopHandMining(player);
            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                player.stopUsingItem();
            }
            BetterDisplayInventory.rotateRows(player.getInventory(), packet.scrollUp);
            player.inventoryMenu.broadcastChanges();
        }
    }

    private record ToggleDrawPacket() {
        private static final StreamCodec<RegistryFriendlyByteBuf, ToggleDrawPacket> STREAM_CODEC = StreamCodec.unit(new ToggleDrawPacket());

        private static void handle(ToggleDrawPacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                // 拔刀已取消：双持由双手物品自动判定，这里只校正一次状态。
                refreshDualWield(sender);
            }
        }
    }

    private record SyncStatePacket(CombatState state, double attackDamage) {
        private static final StreamCodec<RegistryFriendlyByteBuf, SyncStatePacket> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public SyncStatePacket decode(RegistryFriendlyByteBuf buffer) {
                ItemStack left = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                ItemStack right = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                boolean drawn = buffer.readBoolean();
                List<ItemStack> toolSkillSlots = new ArrayList<>(TOOL_SKILL_SLOTS);
                for (int i = 0; i < TOOL_SKILL_SLOTS; i++) {
                    toolSkillSlots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                }
                return new SyncStatePacket(new CombatState(left, right, drawn, toolSkillSlots), buffer.readDouble());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, SyncStatePacket packet) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, packet.state().leftHand());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, packet.state().rightHand());
                buffer.writeBoolean(packet.state().drawn());
                List<ItemStack> toolSkillSlots = packet.state().toolSkillSlots();
                for (int i = 0; i < TOOL_SKILL_SLOTS; i++) {
                    ItemStack stack = i < toolSkillSlots.size() ? toolSkillSlots.get(i) : ItemStack.EMPTY;
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
                }
                buffer.writeDouble(packet.attackDamage());
            }
        };

        private static void handle(SyncStatePacket packet, CustomPayloadEvent.Context context) {
            clientState = new ClientState(packet.state().leftHand().copy(), packet.state().rightHand().copy(), packet.state().drawn(), copyToolSkillSlots(packet.state().toolSkillSlots()));
            clientAttackDamage = packet.attackDamage();
        }
    }

    private record RemoteUiStateMessage() {
        private static final StreamCodec<RegistryFriendlyByteBuf, RemoteUiStatePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> UiStateSnapshot.STREAM_CODEC.encode(buffer, packet.snapshot()),
            buffer -> new RemoteUiStatePacket(UiStateSnapshot.STREAM_CODEC.decode(buffer))
        );

        private static void handle(RemoteUiStatePacket packet, CustomPayloadEvent.Context context) {
            UiStateSnapshot snapshot = packet.snapshot();
            if (snapshot != null) {
                RemoteUiStateStore.update(snapshot);
            }
        }
    }

    private record SaoMenuUpdateMessage() {
        private static final StreamCodec<RegistryFriendlyByteBuf, SaoMenuUpdatePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> SaoMenuUpdatePacket.STREAM_CODEC.encode(buffer, packet),
            buffer -> SaoMenuUpdatePacket.STREAM_CODEC.decode(buffer)
        );

        private static void handle(SaoMenuUpdatePacket packet, CustomPayloadEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                RemoteUiSync.onClientUpdate(sender, packet);
            }
        }
    }

    private static List<ItemStack> emptyToolSkillSlots() {        List<ItemStack> slots = new ArrayList<>(TOOL_SKILL_SLOTS);
        for (int i = 0; i < TOOL_SKILL_SLOTS; i++) {
            slots.add(ItemStack.EMPTY);
        }
        return slots;
    }

    private static List<ItemStack> copyToolSkillSlots(List<ItemStack> source) {
        List<ItemStack> slots = new ArrayList<>(TOOL_SKILL_SLOTS);
        for (int i = 0; i < TOOL_SKILL_SLOTS; i++) {
            slots.add(i < source.size() ? source.get(i).copy() : ItemStack.EMPTY);
        }
        return slots;
    }
}

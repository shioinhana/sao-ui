package com.daoyou.betterdisplay;

import java.util.Objects;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.event.TickEvent;

public final class BetterDisplayClient {
    private static final KeyMapping TOGGLE_HAND_SLOTS_KEY = new KeyMapping("key.betterdisplay.toggle_hand_slots", InputConstants.KEY_R, KeyMapping.Category.INVENTORY);
    private static final KeyMapping STASH_HOTBAR_KEY = new KeyMapping("key.betterdisplay.stash_hotbar", InputConstants.KEY_J, KeyMapping.Category.INVENTORY);
    private static final KeyMapping OFFHAND_USE_KEY = new KeyMapping("key.betterdisplay.offhand_use", InputConstants.KEY_X, KeyMapping.Category.GAMEPLAY);
    private static final Identifier COMBAT_BAR_LAYER = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "combat_bar");
    private static final Identifier HOTBAR_HIDE_BEGIN_LAYER = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "hotbar_hide_begin");
    private static final Identifier HOTBAR_HIDE_END_LAYER = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "hotbar_hide_end");
    private static final Identifier VANILLA_HOTBAR_SPRITE = Identifier.withDefaultNamespace("hud/hotbar");
    private static final Identifier VANILLA_HOTBAR_SELECTION_SPRITE = Identifier.withDefaultNamespace("hud/hotbar_selection");
    private static final Identifier VANILLA_HOTBAR_OFFHAND_LEFT_SPRITE = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");
    private static final int TOOL_SKILL_ROW_SLOTS = BetterDisplayCombat.TOOL_SKILL_SLOTS;
    private static final int HOTBAR_HIDE_HEIGHT = 23;
    private static final int VANILLA_HOTBAR_WIDTH = 182;
    private static final int VANILLA_HOTBAR_HEIGHT = 22;
    private static final int VANILLA_HOTBAR_OFFHAND_WIDTH = 29;
    private static final int VANILLA_HOTBAR_OFFHAND_HEIGHT = 24;
    private static final int VANILLA_MAIN_HAND_SLOT_WIDTH = 22;
    private static final int VANILLA_HOTBAR_SLOT_SPACING = 20;
    private static final int VANILLA_HOTBAR_ITEM_OFFSET = 3;
    private static final int HAND_SLOT_GAP = 6;
    private static final int HANDS_SKILLS_GAP = 10;
    private static final float COMBAT_BAR_SLIDE_SPEED = 5.0F;
    private static final float HOTBAR_HIDE_THRESHOLD = 0.5F;
    private static final int OFFHAND_USE_REPEAT_TICKS = 4;
    private static final int MENU_SHARE_HEARTBEAT_TICKS = 10;
    private static final float MENU_LOOK_YAW_DEGREES = 24.0F;
    private static final float MENU_LOOK_PITCH_DEGREES = 14.0F;
    private static final double MENU_LOOK_RESPONSE_SECONDS = 0.07D;
    private static float combatBarSlideProgress;
    private static long combatBarLastFrameMillis;
    private static boolean hotbarScissorActive;
    private static boolean offhandUseActive;
    private static boolean holdingVanillaUseKey;
    private static int offhandUseDelay;
    private static Screen trackedMovementScreen;
    private static Vec3 trackedMovementScreenOrigin;
    private static boolean skipNextToggleHandSlotsClick;
    private static SaoMenuState lastSharedMenu;
    private static MenuLook lastSharedLook;
    private static int menuShareHeartbeat;
    private static LocalPlayer menuSharePlayer;
    private static ClientLevel menuShareLevel;
    private static MenuLook menuLook;

    private BetterDisplayClient() {
    }

    public static void openVanillaInventory(LocalPlayer player) {
        Minecraft.getInstance().setScreen(new InventoryScreen(player));
    }

    public static void init() {
        RegisterKeyMappingsEvent.BUS.addListener(BetterDisplayClient::onRegisterKeyMappings);
        ScreenEvent.Opening.BUS.addListener(BetterDisplayClient::onScreenOpening);
        ScreenEvent.Closing.BUS.addListener(BetterDisplayClient::onScreenClosing);
        ScreenEvent.Init.Post.BUS.addListener(BetterDisplayClient::onScreenInitPost);
        MovementInputUpdateEvent.BUS.addListener(BetterDisplayClient::onMovementInputUpdate);
        TickEvent.ClientTickEvent.Pre.BUS.addListener(BetterDisplayClient::onClientTick);
        TickEvent.ClientTickEvent.Post.BUS.addListener(BetterDisplayClient::onClientTick);
        TickEvent.RenderTickEvent.Pre.BUS.addListener(BetterDisplayClient::onRenderTick);
        AddGuiOverlayLayersEvent.BUS.addListener(BetterDisplayClient::onAddGuiOverlayLayers);
        // 鼠标按键在 InputEvent.MouseButton.Pre 里接管：它每次物理按下/抬起都会触发，
        // 比 InteractionKeyMappingTriggered 可靠（后者只在特定攻击/使用路径派发）。
        InputEvent.MouseButton.Pre.BUS.addListener(
            (java.util.function.Predicate<InputEvent.MouseButton.Pre>) BetterDisplayClient::onMouseButtonPre);
        AddFramePassEvent.BUS.addListener(BetterDisplayClient::onAddFramePass);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> {
            RemoteUiStateStore.clear();
            menuLook = null;
            resetMenuSharing();
            BetterDisplayCombat.clearClientState();
            offhandUseActive = false;
            offhandUseDelay = 0;
            activeUseHand = null;
            restoreVanillaUseKey(Minecraft.getInstance());
        });
    }

    private static void onAddFramePass(AddFramePassEvent event) {
        event.addPass(Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "remote_ui"), new FramePassManager.PassDefinition() {
            @Override
            public void extracts(LevelTargetBundle targets, FramePass pass) {
                if (targets.main != null) {
                    targets.main = pass.readsAndWrites(targets.main);
                }
            }

            @Override
            public void executes(LevelRenderState state) {
                renderRemoteUi();
            }
        });
    }

    private static void renderRemoteUi() {
        Minecraft minecraft = Minecraft.getInstance();
        // LevelRenderer 的全局模型视图矩阵已包含相机的逆旋转。
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        RemoteUiRenderer.renderWorld(new PoseStack(), minecraft.gameRenderer.getMainCamera(), partialTick);
    }

    /**
     * 双手都持有可战斗的物品（剑或工具）。
     *
     * <p>必须用“武器或工具”而不是“两把剑”：左剑右镐/斧/铲也是双持，
     * 只认双剑会让工具那只手的左键完全走回原版（只挖主手）。
     */
    private static boolean isHoldingDualWeapons(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return !main.isEmpty() && !off.isEmpty()
            && isCombatItem(main) && isCombatItem(off);
    }

    /** 是不是挖掘类工具（镐/斧/铲），剑和锄头不算。 */
    private static boolean isMiningTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // 1.21.11 没有 PickaxeItem；镐靠 TOOL 组件认。
        return stack.has(net.minecraft.core.component.DataComponents.TOOL)
            || stack.getItem() instanceof net.minecraft.world.item.AxeItem
            || stack.getItem() instanceof net.minecraft.world.item.ShovelItem;
    }

    private static boolean isCombatItem(ItemStack stack) {
        return BetterDisplayCombat.isSwordStack(stack)
            || stack.has(net.minecraft.core.component.DataComponents.TOOL)
            || stack.getItem() instanceof net.minecraft.world.item.AxeItem
            || stack.getItem() instanceof net.minecraft.world.item.ShovelItem
            || stack.getItem() instanceof net.minecraft.world.item.HoeItem;
    }

    /**
     * 左右键 = 对应那只手的动作。
     *
     * <p>键位固定：<b>左键 → 副手（左手）</b>，<b>右键 → 主手（右手）</b>。
     * 每只手具体做什么，由那只手拿着什么、指着什么决定：
     * <table border="1">
     *   <tr><th>指着</th><th>手上有工具/武器</th><th>动作</th></tr>
     *   <tr><td>方块</td><td>斧/镐/铲</td><td>挖掘（原版进度、掉落、耐久）</td></tr>
     *   <tr><td>方块</td><td>锄头</td><td>右键锄地</td></tr>
     *   <tr><td>实体</td><td>剑/斧</td><td>挥砍（原版冷却、附魔、暴击）</td></tr>
     *   <tr><td>空气</td><td>任意</td><td>挥动那只手</td></tr>
     * </table>
     *
     * <p>实现方式：把目标手的物品临时换到主手，交给原版
     * {@link net.minecraft.client.multiplayer.MultiPlayerGameMode} 执行，用完换回。
     * 这样不存在“自己模拟挖掘”导致的进度、粒子、掉落不同步。
     */
    /** 诊断：把最近一次左键事件的结果显示在屏幕上。 */

    // ===================== 左右键 → 左右手 =====================

    /** 单只手的挖掘/按住状态。 */
    private static final class HandMiningState {
        boolean mouseDown;
        boolean mining;
        BlockPos target;
        int heartbeat;
    }

    private static final HandMiningState LEFT_HAND = new HandMiningState();
    private static final HandMiningState RIGHT_HAND = new HandMiningState();

    private static HandMiningState stateOf(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? LEFT_HAND : RIGHT_HAND;
    }

    /**
     * 鼠标按键分派。
     *
     * <p><b>按键只决定用哪只手</b>：左键 → 左手（副手），右键 → 右手（主手）。
     * 这只手用「左键功能」还是「右键功能」，由交互类型决定：
     * <table border="1">
     *   <tr><th>交互类型</th><th>该手物品</th><th>执行</th></tr>
     *   <tr><td>指着实体</td><td>武器/工具</td><td>左键功能：攻击</td></tr>
     *   <tr><td>指着方块</td><td>锄头 / 方块 / 食物 / 桶</td><td>右键功能：锄地、放置、使用</td></tr>
     *   <tr><td>指着方块</td><td>斧 / 镐 / 铲 / 剑</td><td>左键功能：挖掘</td></tr>
     *   <tr><td>指着空气</td><td>任意</td><td>左键功能：挥手</td></tr>
     * </table>
     *
     * <p>只接管左手；右手就是原版主手，能交给原版时一律交给原版，
     * 只在原版会“回落到副手物品”时拦下来，避免右键串到左手。
     */
    /**
     * 双持时的鼠标分派。
     *
     * <p><b>按键只决定用哪只手，Shift 决定用哪个功能</b>：
     * <table border="1">
     *   <tr><th>操作</th><th>作用</th></tr>
     *   <tr><td>左键</td><td>左手（副手）的左键功能：攻击 / 挖掘</td></tr>
     *   <tr><td>右键</td><td>右手（主手）的左键功能：攻击 / 挖掘</td></tr>
     *   <tr><td>Shift + 左键</td><td>左手的右键功能：使用 / 安放 / 锄地</td></tr>
     *   <tr><td>Shift + 右键</td><td>右手的右键功能：使用 / 安放 / 锄地</td></tr>
     * </table>
     *
     * <p>原版左键固定作用于主手、右键固定是“使用”，所以双持时两个键都要由我们接管。
     */
    private static boolean onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null || minecraft.getOverlay() != null
            || minecraft.gameMode == null) {
            return false;
        }
        int button = event.getButton();
        boolean left = button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
        boolean right = button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (!left && !right) {
            return false;
        }
        InteractionHand hand = left ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        boolean press = event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        HandMiningState state = stateOf(hand);
        state.mouseDown = press;
        if (!press) {
            // 抬起事件必须交回原版：取消它会让 KeyMapping.setDown(false) 不执行，
            // 原版就会一直以为鼠标键还按着，持续自动攻击主手，把双持分派搅乱。
            endMining(hand);
            if (activeUseHand == hand) {
                stopHandUse(player);
            }
            return false;
        }
        if (!BetterDisplayCombat.hasBothHands(player) || !player.isAlive() || player.isSpectator()) {
            state.mouseDown = false;
            return false;
        }
        if (minecraft.options.keyShift.isDown() || player.isShiftKeyDown()) {
            if (activeUseHand == null && !player.isHandsBusy()) {
                activeUseHand = hand;
                dualUseDelay = OFFHAND_USE_REPEAT_TICKS;
                dispatchRightFunction(minecraft, hand);
            }
        } else {
            dispatchLeftFunction(player, minecraft, hand);
        }
        return true;
    }

    private static void stopHandUse(LocalPlayer player) {
        InteractionHand hand = activeUseHand;
        activeUseHand = null;
        if (hand == null) {
            return;
        }
        if (player.isUsingItem() && player.getUsedItemHand() == hand) {
            player.stopUsingItem();
        }
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.getConnection().isConnected()) {
            BetterDisplayCombat.requestHandUseItemStop(handSlot(hand));
        }
    }

    private static void tickHandUse(Minecraft minecraft) {
        if (activeUseHand == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            activeUseHand = null;
            return;
        }
        if (minecraft.screen != null || minecraft.getOverlay() != null || !minecraft.isWindowActive()
            || !player.isAlive() || !BetterDisplayCombat.hasBothHands(player)) {
            stopHandUse(player);
            return;
        }
        if (dualUseDelay > 0) {
            dualUseDelay--;
        }
        if (dualUseDelay == 0 && !player.isUsingItem()) {
            dualUseDelay = OFFHAND_USE_REPEAT_TICKS;
            dispatchRightFunction(minecraft, activeUseHand);
        }
    }

    static void clearDualStateIfSingleHanded(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || BetterDisplayCombat.hasBothHands(player)) {
            return;
        }
        endMining(InteractionHand.OFF_HAND);
        endMining(InteractionHand.MAIN_HAND);
    }

    private static void dispatchLeftFunction(LocalPlayer player, Minecraft minecraft, InteractionHand hand) {
        ItemStack item = player.getItemInHand(hand);
        LivingEntity target = crosshairLiving(minecraft, player);
        if (target != null && isCombatItem(item)) {
            BetterDisplayCombat.requestHandAttack(handSlot(hand), target.getId());
            noteHandAttack(player, hand, item);
            return;
        }
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            dispatchMining(player, minecraft, hand, hit);
            return;
        }
        player.swing(hand, true);
    }

    /** 这只手的“右键功能”：对实体交互、对空气使用物品、对方块使用/安放。 */
    private static void dispatchRightFunction(Minecraft minecraft, InteractionHand hand) {
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BetterDisplayCombat.requestHandUse(handSlot(hand), hit);
            return;
        }
        if (minecraft.hitResult instanceof EntityHitResult hit) {
            BetterDisplayCombat.requestHandInteract(handSlot(hand), hit.getEntity().getId());
            return;
        }
        BetterDisplayCombat.requestHandUseItem(handSlot(hand));
    }

    /** 一只手用于冷却显示的记录：手上那把武器、起始 tick、冷却长度。 */
    private static final class HandCooldownState {
        ItemStack weapon = ItemStack.EMPTY;
        int startTick = -10000;
        int delayTicks;
    }

    private static final HandCooldownState LEFT_CD = new HandCooldownState();
    private static final HandCooldownState RIGHT_CD = new HandCooldownState();

    private static HandCooldownState cooldownOf(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? LEFT_CD : RIGHT_CD;
    }
    /**
     * 取出准星指向的活体实体。
     *
     * <p>优先用 hitResult；但它在某些时序下会滞后或落到方块上，
     * 所以再用 Minecraft.crosshairPickEntity 兜底，避免“明明对着敌人却打不到”。
     */
    private static LivingEntity crosshairLiving(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.crosshairPickEntity instanceof LivingEntity living
            && living != player && living.isAlive()
            && player.isWithinEntityInteractionRange(living, 0.0D)) {
            return living;
        }
        if (minecraft.hitResult instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof LivingEntity living
            && living != player && living.isAlive()
            && player.isWithinEntityInteractionRange(living, 0.0D)) {
            return living;
        }
        return null;
    }

    /** 用这只手开始/继续挖掘。 */
    private static void dispatchMining(LocalPlayer player, Minecraft minecraft,
                                       InteractionHand hand, HitResult hit) {
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            player.swing(hand, true);
            return;
        }
        HandMiningState state = stateOf(hand);
        state.mining = true;
        state.target = blockHit.getBlockPos().immutable();
        state.heartbeat = 0;
        BetterDisplayCombat.requestHandMining(handSlot(hand), blockHit);
    }

    /** 这只手松手：停止挖掘。 */
    private static void endMining(InteractionHand hand) {
        HandMiningState state = stateOf(hand);
        if (state.mining) {
            state.mining = false;
            state.target = null;
            BetterDisplayCombat.requestHandMiningStop(handSlot(hand));
        }
    }

    private static InteractionHand activeUseHand;
    private static int dualUseDelay;

    private static BetterDisplayCombat.HandSlot handSlot(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND
            ? BetterDisplayCombat.HandSlot.LEFT : BetterDisplayCombat.HandSlot.RIGHT;
    }

    /**
     * 按住期间给服务端保活。
     *
     * <p>服务端 12 tick 收不到请求就判定松手，所以按住时必须持续告知。
     * 两只手各管各的。
     */
    private static void tickHandMining(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        tickOneHandMining(minecraft, InteractionHand.OFF_HAND, LEFT_HAND);
        tickOneHandMining(minecraft, InteractionHand.MAIN_HAND, RIGHT_HAND);
    }

    private static void tickOneHandMining(Minecraft minecraft, InteractionHand hand, HandMiningState state) {
        if (!state.mining) {
            return;
        }
        if (!state.mouseDown || minecraft.screen != null || minecraft.getOverlay() != null) {
            endMining(hand);
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        boolean retarget = !hit.getBlockPos().equals(state.target);
        if (retarget) {
            state.target = hit.getBlockPos().immutable();
            state.heartbeat = 0;
        }
        if (retarget || --state.heartbeat <= 0) {
            state.heartbeat = 4;
            BetterDisplayCombat.requestHandMining(handSlot(hand), hit);
        }
    }

    private static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof BetterDisplayInventoryScreen screen) {
            activeMenuLook(Minecraft.getInstance(), screen);
        }
    }

    private static void onScreenClosing(ScreenEvent.Closing event) {
        if (menuLook != null && event.getScreen() == menuLook.screen) {
            // Keep the last visible orientation when normal mouse look resumes.
            menuLook = null;
        }
    }

    private static void onRenderTick(TickEvent.RenderTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BetterDisplayInventoryScreen screen)) {
            menuLook = null;
            return;
        }
        MenuLook look = activeMenuLook(minecraft, screen);
        if (look == null || !minecraft.isWindowActive() || minecraft.getOverlay() != null) {
            return;
        }

        var window = minecraft.getWindow();
        long now = Util.getNanos();
        double seconds = Math.min((now - look.lastFrameNanos) * 1.0e-9D, 0.1D);
        look.lastFrameNanos = now;
        double mouseX = minecraft.mouseHandler.xpos();
        double mouseY = minecraft.mouseHandler.ypos();
        // A screen opened from another menu may inherit a cursor away from the center.
        look.mouseMoved |= mouseX != look.initialMouseX || mouseY != look.initialMouseY;
        if (!look.mouseMoved) {
            return;
        }

        float targetYaw = cursorAxis(mouseX, window.getScreenWidth()) * MENU_LOOK_YAW_DEGREES;
        float pitch = cursorAxis(mouseY, window.getScreenHeight()) * MENU_LOOK_PITCH_DEGREES;
        float targetPitch = Mth.clamp(look.anchorPitch + pitch, -90.0F, 90.0F) - look.anchorPitch;
        float weight = (float) -Math.expm1(-seconds / MENU_LOOK_RESPONSE_SECONDS);
        look.yawOffset = approachLook(look.yawOffset, targetYaw, weight);
        look.pitchOffset = approachLook(look.pitchOffset, targetPitch, weight);

        float yaw = look.anchorYaw + look.yawOffset;
        float absolutePitch = look.anchorPitch + look.pitchOffset;
        look.player.setYRot(yaw);
        look.player.setXRot(absolutePitch);
        // The easing above already interpolates per frame. Keep Camera.setup's tick interpolation in sync.
        look.player.yRotO = yaw;
        look.player.xRotO = absolutePitch;
    }

    private static float cursorAxis(double cursor, int screenSize) {
        return screenSize > 0 ? (float) Mth.clamp(2.0D * cursor / screenSize - 1.0D, -1.0D, 1.0D) : 0.0F;
    }

    private static float approachLook(float current, float target, float weight) {
        return Math.abs(target - current) < 0.01F ? target : Mth.lerp(weight, current, target);
    }

    static MenuView menuView(BetterDisplayInventoryScreen screen) {
        MenuLook look = activeMenuLook(Minecraft.getInstance(), screen);
        return look == null ? MenuView.INACTIVE
            : new MenuView(look.anchorYaw, look.anchorPitch, look.yawOffset, look.pitchOffset);
    }

    private static MenuLook activeMenuLook(Minecraft minecraft, BetterDisplayInventoryScreen screen) {
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != screen || player == null || minecraft.level == null || minecraft.getCameraEntity() != player
            || !minecraft.options.getCameraType().isFirstPerson() || !player.isAlive() || player.isSleeping()) {
            menuLook = null;
            return null;
        }
        if (menuLook == null || menuLook.screen != screen || menuLook.player != player || menuLook.level != minecraft.level) {
            menuLook = new MenuLook(screen, player, minecraft.level,
                minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
        }
        return menuLook;
    }

    record MenuView(float anchorYaw, float anchorPitch, float yawOffset, float pitchOffset) {
        private static final MenuView INACTIVE = new MenuView(0.0F, 0.0F, 0.0F, 0.0F);
    }

    private static final class MenuLook {
        private final BetterDisplayInventoryScreen screen;
        private final LocalPlayer player;
        private final ClientLevel level;
        private final float anchorYaw;
        private final float anchorPitch;
        private final double initialMouseX;
        private final double initialMouseY;
        private long lastFrameNanos = Util.getNanos();
        private boolean mouseMoved;
        private float yawOffset;
        private float pitchOffset;

        private MenuLook(BetterDisplayInventoryScreen screen, LocalPlayer player, ClientLevel level, double mouseX, double mouseY) {
            this.screen = screen;
            this.player = player;
            this.level = level;
            this.anchorYaw = player.getYRot();
            this.anchorPitch = player.getXRot();
            this.initialMouseX = mouseX;
            this.initialMouseY = mouseY;
        }
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        trackedMovementScreen = null;
        trackedMovementScreenOrigin = null;
        if (event.getNewScreen() != null) {
            QuickHotbarInput.stop();
        }
    }

    private static void handleSaoMenuKey(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !player.isAlive() || player.isSpectator() || !minecraft.isWindowActive()
            || minecraft.screen != null || minecraft.getOverlay() != null) {
            return;
        }
        KeyMapping key = minecraft.options.keyPlayerList;
        if (!key.consumeClick()) {
            return;
        }
        while (key.consumeClick()) {
            // A held key should open the screen once.
        }
        BetterDisplayInventoryScreen screen = new BetterDisplayInventoryScreen(player);
        if (key.isDown()) {
            screen.waitForSaoKeyRelease();
        }
        minecraft.setScreen(screen);
    }

    static boolean saoMenuKeyMatches(KeyEvent event) {
        return Minecraft.getInstance().options.keyPlayerList.matches(event);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HAND_SLOTS_KEY);
        event.register(STASH_HOTBAR_KEY);
        event.register(OFFHAND_USE_KEY);
    }

    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().addConditionTo(ForgeLayeredDraw.POST_SLEEP_STACK, ForgeLayeredDraw.TAB_LIST, () -> false);
        event.getLayeredDraw().addBelow(ForgeLayeredDraw.PRE_SLEEP_STACK, HOTBAR_HIDE_BEGIN_LAYER, ForgeLayeredDraw.HOTBAR_AND_DECOS, BetterDisplayClient::beginHotbarHide);
        event.getLayeredDraw().addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, HOTBAR_HIDE_END_LAYER, ForgeLayeredDraw.HOTBAR_AND_DECOS, BetterDisplayClient::endHotbarHide);
        event.getLayeredDraw().addAbove(ForgeLayeredDraw.PRE_SLEEP_STACK, COMBAT_BAR_LAYER, HOTBAR_HIDE_END_LAYER, BetterDisplayClient::renderCombatBar);
    }

    private static void beginHotbarHide(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || isCreativeMode(minecraft)) {
            return;
        }
        if (!BetterDisplayCombat.clientDrawn()) {
            return;
        }
        if (combatBarSlideProgress < HOTBAR_HIDE_THRESHOLD) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        graphics.enableScissor(0, 0, width, Math.max(0, height - HOTBAR_HIDE_HEIGHT));
        hotbarScissorActive = true;
    }

    private static void endHotbarHide(GuiGraphics graphics, DeltaTracker delta) {
        if (hotbarScissorActive) {
            graphics.disableScissor();
            hotbarScissorActive = false;
        }
    }

    private static void renderCombatBar(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            return;
        }
        boolean drawn = BetterDisplayCombat.clientDrawn() && !isCreativeMode(minecraft);
        long now = Util.getMillis();
        if (combatBarLastFrameMillis == 0L) {
            combatBarLastFrameMillis = now;
        }
        float elapsedSeconds = Math.min(0.1F, (now - combatBarLastFrameMillis) / 1000.0F);
        combatBarLastFrameMillis = now;
        float target = drawn ? 1.0F : 0.0F;
        if (combatBarSlideProgress < target) {
            combatBarSlideProgress = Math.min(target, combatBarSlideProgress + COMBAT_BAR_SLIDE_SPEED * elapsedSeconds);
        } else if (combatBarSlideProgress > target) {
            combatBarSlideProgress = Math.max(target, combatBarSlideProgress - COMBAT_BAR_SLIDE_SPEED * elapsedSeconds);
        }
        if (combatBarSlideProgress <= 0.001F) {
            return;
        }
        float eased = easeInOutQuad(combatBarSlideProgress);
        // 布局：左手格 | 原版九格物品栏 | 右手格
        int totalWidth = VANILLA_HOTBAR_OFFHAND_WIDTH + HANDS_SKILLS_GAP
            + VANILLA_HOTBAR_WIDTH + HANDS_SKILLS_GAP + VANILLA_MAIN_HAND_SLOT_WIDTH;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int x = width / 2 - totalWidth / 2;
        int targetY = height - VANILLA_HOTBAR_HEIGHT;
        int hiddenY = height + VANILLA_HOTBAR_HEIGHT + 4;
        int y = Math.round(targetY + (1.0F - eased) * (hiddenY - targetY));
        int offhandX = x;
        int hotbarX = offhandX + VANILLA_HOTBAR_OFFHAND_WIDTH + HANDS_SKILLS_GAP;
        int mainhandX = hotbarX + VANILLA_HOTBAR_WIDTH + HANDS_SKILLS_GAP;

        // 左手格（最左）
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VANILLA_HOTBAR_OFFHAND_LEFT_SPRITE,
            offhandX, y - 1, VANILLA_HOTBAR_OFFHAND_WIDTH, VANILLA_HOTBAR_OFFHAND_HEIGHT);
        drawStackAt(graphics, minecraft, offhandX + VANILLA_HOTBAR_ITEM_OFFSET,
            y + VANILLA_HOTBAR_ITEM_OFFSET, BetterDisplayCombat.clientLeftHand());

        // 原版九格物品栏：完整展示玩家所有快捷栏物品
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VANILLA_HOTBAR_SPRITE,
            VANILLA_HOTBAR_WIDTH, VANILLA_HOTBAR_HEIGHT, 0, 0, hotbarX, y,
            VANILLA_HOTBAR_WIDTH, VANILLA_HOTBAR_HEIGHT);
        int selectedSlot = player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            drawHotbarStack(graphics, minecraft, hotbarX, y, i, stack);
            if (i == selectedSlot) {
                // 正在使用的主手：选中框 + 整体压灰，表示它已被右手占用
                int slotX = hotbarX + 3 + i * VANILLA_HOTBAR_SLOT_SPACING;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VANILLA_HOTBAR_SELECTION_SPRITE,
                    slotX - 4, y - 1, 24, 23);
                graphics.fill(slotX, y + 3, slotX + 16, y + 19, 0x99A0A0A0);
            }
        }

        // 右手格（最右）
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, VANILLA_HOTBAR_SPRITE,
            VANILLA_HOTBAR_WIDTH, VANILLA_HOTBAR_HEIGHT, 0, 0, mainhandX, y,
            VANILLA_MAIN_HAND_SLOT_WIDTH, VANILLA_HOTBAR_HEIGHT);
        drawStackAt(graphics, minecraft, mainhandX + VANILLA_HOTBAR_ITEM_OFFSET,
            y + VANILLA_HOTBAR_ITEM_OFFSET, BetterDisplayCombat.clientRightHand());

        // 左右手格各自显示攻击冷却
        drawSlotCooldown(graphics, offhandX + 1, y - 1,
            VANILLA_HOTBAR_OFFHAND_WIDTH - 2, VANILLA_HOTBAR_OFFHAND_HEIGHT,
            handCharge(player, InteractionHand.OFF_HAND));
        drawSlotCooldown(graphics, mainhandX, y,
            VANILLA_MAIN_HAND_SLOT_WIDTH, VANILLA_HOTBAR_HEIGHT,
            handCharge(player, InteractionHand.MAIN_HAND));
    }

    /**
     * 指定手的冷却进度：0 = 刚挥完，1 = 已就绪。
     *
     * <p>两只手都用这里自管的计时，不读原版 attackStrengthTicker。
     * 那个计时器是双手共享的，一边出刀会把另一边显示成没蓄满。
     */
    static float handCharge(LocalPlayer player, InteractionHand hand) {
        ItemStack current = player.getItemInHand(hand);
        HandCooldownState state = cooldownOf(hand);
        if (current.isEmpty()) {
            return 1.0F;
        }
        if (!ItemStack.isSameItemSameComponents(current, state.weapon)) {
            return 1.0F;                     // 换了武器，视为已就绪
        }
        if (state.delayTicks <= 0) {
            return 1.0F;
        }
        int elapsed = player.tickCount - state.startTick;
        return net.minecraft.util.Mth.clamp(elapsed / (float) state.delayTicks, 0.0F, 1.0F);
    }

    /**
     * 在格子上画冷却：未就绪部分盖一层半透明黑。
     *
     * @param charge 0..1，1 表示已就绪（不画）
     */
    private static void drawSlotCooldown(GuiGraphics graphics, int x, int y, int w, int h, float charge) {
        if (charge >= 0.999F || w <= 0 || h <= 0) {
            return;
        }
        int covered = Math.max(1, Math.round((1.0F - charge) * h));
        // 从下往上恢复：底部是被遮挡的冷却区。
        graphics.fill(x, y + h - covered, x + w, y + h, 0xB0000000);
    }

    /** 记录一次攻击，用于格子上的冷却显示。 */
    private static void noteHandAttack(LocalPlayer player, InteractionHand hand, ItemStack weapon) {
        HandCooldownState state = cooldownOf(hand);
        state.weapon = weapon.copy();
        state.delayTicks = handCooldownTicks(player, hand, weapon);
        state.startTick = player.tickCount;
    }

    /** 指定手武器的冷却 tick 数，算法同原版 20 / 攻速。 */
    private static int handCooldownTicks(LocalPlayer player, InteractionHand hand, ItemStack stack) {
        if (player == null || stack.isEmpty()) {
            return 0;
        }
        var speedAttr = net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED;
        var slot = hand == InteractionHand.OFF_HAND
            ? net.minecraft.world.entity.EquipmentSlot.OFFHAND
            : net.minecraft.world.entity.EquipmentSlot.MAINHAND;
        var instance = new net.minecraft.world.entity.ai.attributes.AttributeInstance(speedAttr, ignored -> {});
        instance.setBaseValue(player.getAttributeBaseValue(speedAttr));
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(speedAttr)) {
                instance.addOrUpdateTransientModifier(modifier);
            }
        });
        double speed = instance.getValue();
        return Math.max(1, (int) Math.round(20.0D / Math.max(0.1D, speed)));
    }

    private static float easeInOutQuad(float t) {
        return t < 0.5F ? 2.0F * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 2) / 2.0F;
    }

    private static void drawHotbarStack(GuiGraphics graphics, Minecraft minecraft, int hotbarX, int hotbarY, int slot, ItemStack stack) {
        int x = hotbarX + VANILLA_HOTBAR_ITEM_OFFSET + slot * VANILLA_HOTBAR_SLOT_SPACING;
        int y = hotbarY + VANILLA_HOTBAR_ITEM_OFFSET;
        drawStackAt(graphics, minecraft, x, y, stack);
    }

    private static void drawStackAt(GuiGraphics graphics, Minecraft minecraft, int x, int y, ItemStack stack) {
        if (!stack.isEmpty()) {
            graphics.renderFakeItem(stack, x, y);
            graphics.renderItemDecorations(minecraft.font, stack, x, y);
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Pre event) {
        handleMovementScreen();
        Minecraft minecraft = Minecraft.getInstance();
        handleSaoMenuKey(minecraft);
        QuickHotbarInput.tick(minecraft);

        LocalPlayer player = minecraft.player;
        if (player != null && player.isUsingItem() && !minecraft.options.keyUse.isDown()
            && (player.getUsedItemHand() == activeUseHand
                || (offhandUseActive && canControlOffhand(minecraft) && OFFHAND_USE_KEY.isDown()
                    && player.getUsedItemHand() == InteractionHand.OFF_HAND))) {
            // Vanilla releases held items when its use mapping is up. Keep that mapping down for this tick only.
            minecraft.options.keyUse.setDown(true);
            holdingVanillaUseKey = true;
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        restoreVanillaUseKey(minecraft);
        handleMovementScreen();
        handleCombatToggleKey();
        tickHandMining(minecraft);
        handleOffhandUseKey(minecraft);
        publishSharedMenu(minecraft);
        tickHandUse(minecraft);
        clearDualStateIfSingleHanded(minecraft);
    }

    private static void publishSharedMenu(Minecraft minecraft) {
        if (minecraft.getConnection() == null || minecraft.player == null || minecraft.level == null) {
            resetMenuSharing();
            return;
        }
        if (menuSharePlayer != minecraft.player || menuShareLevel != minecraft.level) {
            resetMenuSharing();
            menuSharePlayer = minecraft.player;
            menuShareLevel = minecraft.level;
        }
        SaoMenuState menu = null;
        MenuLook look = null;
        if (BetterDisplayConfig.shareUiState && minecraft.player.isAlive() && !minecraft.player.isSpectator()
            && minecraft.screen instanceof BetterDisplayInventoryScreen screen) {
            menu = screen.sharedMenuState();
            look = activeMenuLook(minecraft, screen);
        }
        if (!Objects.equals(lastSharedMenu, menu) || lastSharedLook != look
            || (menu != null && --menuShareHeartbeat <= 0)) {
            float anchorYaw = look == null ? minecraft.player.getYRot() : look.anchorYaw;
            float anchorPitch = look == null ? minecraft.player.getXRot() : look.anchorPitch;
            BetterDisplayNetworking.sendSaoMenuUpdate(new SaoMenuUpdatePacket(menu, anchorYaw, anchorPitch));
            lastSharedMenu = menu;
            lastSharedLook = look;
            menuShareHeartbeat = MENU_SHARE_HEARTBEAT_TICKS;
        }
    }

    private static void resetMenuSharing() {
        lastSharedMenu = null;
        lastSharedLook = null;
        menuShareHeartbeat = 0;
        menuSharePlayer = null;
        menuShareLevel = null;
    }

    private static void restoreVanillaUseKey(Minecraft minecraft) {
        if (holdingVanillaUseKey) {
            minecraft.options.keyUse.setDown(false);
            holdingVanillaUseKey = false;
        }
    }

    private static boolean canControlOffhand(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return player != null && minecraft.level != null && minecraft.gameMode != null && player.isAlive()
            && !isCreativeMode(minecraft) && !player.isSpectator() && !player.isHandsBusy()
            && minecraft.isWindowActive() && minecraft.screen == null && minecraft.getOverlay() == null
            && BetterDisplayCombat.clientDrawn() && !player.getOffhandItem().isEmpty();
    }

    private static void handleOffhandUseKey(Minecraft minecraft) {
        boolean clicked = false;
        while (OFFHAND_USE_KEY.consumeClick()) {
            clicked = true;
        }
        if (offhandUseDelay > 0) {
            offhandUseDelay--;
        }

        if ((!OFFHAND_USE_KEY.isDown() && !clicked) || !canControlOffhand(minecraft)
            || minecraft.options.keyUse.isDown() || minecraft.gameMode.isDestroying()) {
            if (offhandUseActive && minecraft.player != null && minecraft.gameMode != null
                && minecraft.player.isUsingItem() && minecraft.player.getUsedItemHand() == InteractionHand.OFF_HAND
                && !minecraft.options.keyUse.isDown()) {
                minecraft.gameMode.releaseUsingItem(minecraft.player);
            }
            offhandUseActive = false;
            return;
        }
        if (!minecraft.player.isUsingItem() && offhandUseDelay == 0) {
            offhandUseDelay = OFFHAND_USE_REPEAT_TICKS;
            triggerOffhandUseAction(minecraft);
            offhandUseActive = minecraft.player.isUsingItem()
                && minecraft.player.getUsedItemHand() == InteractionHand.OFF_HAND;
        }
    }

    private static void triggerOffhandUseAction(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        InteractionHand hand = InteractionHand.OFF_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isItemEnabled(minecraft.level.enabledFeatures())) {
            return;
        }

        if (minecraft.hitResult instanceof EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            if (!minecraft.level.getWorldBorder().isWithinBounds(entity.blockPosition())) {
                return;
            }
            if (player.isWithinEntityInteractionRange(entity, 0.0D)) {
                InteractionResult result = minecraft.gameMode.interactAt(player, entity, entityHit, hand);
                if (!result.consumesAction()) {
                    result = minecraft.gameMode.interact(player, entity, hand);
                }
                if (finishOffhandUse(minecraft, result, false)) {
                    return;
                }
            }
        } else if (minecraft.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            int initialCount = stack.getCount();
            InteractionResult result = minecraft.gameMode.useItemOn(player, hand, blockHit);
            if (finishOffhandUse(minecraft, result, stack.getCount() != initialCount || player.hasInfiniteMaterials())) {
                return;
            }
        }

        if (!player.getItemInHand(hand).isEmpty()) {
            finishOffhandUse(minecraft, minecraft.gameMode.useItem(player, hand), true);
        }
    }

    private static boolean finishOffhandUse(Minecraft minecraft, InteractionResult result, boolean animateItem) {
        if (result instanceof InteractionResult.Success success
            && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
            minecraft.player.swing(InteractionHand.OFF_HAND);
            if (animateItem) {
                minecraft.gameRenderer.itemInHandRenderer.itemUsed(InteractionHand.OFF_HAND);
            }
        }
        return result.consumesAction() || result == InteractionResult.FAIL;
    }

    static boolean toggleHandSlotsKeyMatches(net.minecraft.client.input.KeyEvent event) {
        return TOGGLE_HAND_SLOTS_KEY.matches(event);
    }

    static void requestToggleHandSlotsFromScreen() {
        skipNextToggleHandSlotsClick = true;
        BetterDisplayCombat.requestToggleDrawn();
    }

    private static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() == minecraft.player && isMovementEnabledScreen(minecraft.screen)) {
            applyMovementInput(minecraft, event.getInput());
        }
    }

    static void syncMovementKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        if (isMovementEnabledScreen(minecraft.screen)) {
            if (minecraft.player != null) {
                applyMovementInput(minecraft, minecraft.player.input);
            }
        }
    }

    private static void applyMovementInput(Minecraft minecraft, ClientInput input) {
        boolean forward = isKeyDown(minecraft.options.keyUp);
        boolean backward = isKeyDown(minecraft.options.keyDown);
        boolean left = isKeyDown(minecraft.options.keyLeft);
        boolean right = isKeyDown(minecraft.options.keyRight);
        boolean jump = isKeyDown(minecraft.options.keyJump);
        boolean shift = isKeyDown(minecraft.options.keyShift);
        boolean sprint = isKeyDown(minecraft.options.keySprint);
        syncMovementKey(minecraft.options.keyUp, forward);
        syncMovementKey(minecraft.options.keyDown, backward);
        syncMovementKey(minecraft.options.keyLeft, left);
        syncMovementKey(minecraft.options.keyRight, right);
        syncMovementKey(minecraft.options.keyJump, jump);
        syncMovementKey(minecraft.options.keyShift, shift);
        syncMovementKey(minecraft.options.keySprint, sprint);
        input.keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);
        input.moveVector = new Vec2(calculateImpulse(left, right), calculateImpulse(forward, backward)).normalized();
    }

    private static boolean isKeyDown(KeyMapping keyMapping) {
        InputConstants.Key key = ((net.minecraftforge.client.extensions.IForgeKeyMapping) keyMapping).getKey();
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key.getValue());
    }

    private static void syncMovementKey(KeyMapping keyMapping, boolean down) {
        keyMapping.setDown(down);
    }

    private static float calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    private static void handleMovementScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Screen screen = minecraft.screen;
        if (player == null || !isMovementEnabledScreen(screen)) {
            trackedMovementScreen = null;
            trackedMovementScreenOrigin = null;
            return;
        }
        if (trackedMovementScreen != screen || trackedMovementScreenOrigin == null) {
            trackedMovementScreen = screen;
            trackedMovementScreenOrigin = player.position();
        }
        syncMovementKeys();
        double closeDistance = BetterDisplayConfig.closeDistanceBlocks;
        if (player.position().distanceToSqr(trackedMovementScreenOrigin) > closeDistance * closeDistance) {
            trackedMovementScreen = null;
            trackedMovementScreenOrigin = null;
            minecraft.setScreen(null);
        }
    }

    private static boolean isMovementEnabledScreen(Screen screen) {
        return screen instanceof BetterDisplayInventoryScreen || screen instanceof AbstractContainerScreen<?>;
    }

    private static void handleCombatToggleKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || isCreativeMode(minecraft)) {
            return;
        }
        // 双持由双手物品自动判定，R 键不再切换。消费掉点击，避免误触发。
        while (TOGGLE_HAND_SLOTS_KEY.consumeClick()) {
            if (skipNextToggleHandSlotsClick) {
                skipNextToggleHandSlotsClick = false;
            }
        }
        while (STASH_HOTBAR_KEY.consumeClick()) {
            openStashConfirm(minecraft);
        }
    }

    /** 锄头只用右键（锄地），左键不驱动挖掘流程。 */
    private static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.HoeItem;
    }

    static boolean stashHotbarKeyMatches(KeyEvent event) {
        return STASH_HOTBAR_KEY.matches(event);
    }

    static void requestStashHotbarFromScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        openStashConfirm(minecraft);
    }

    private static void openStashConfirm(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        if (minecraft.screen instanceof BetterDisplayInventoryScreen current) {
            current.showStashConfirm();
            return;
        }
        if (minecraft.screen == null) {
            BetterDisplayInventoryScreen screen = new BetterDisplayInventoryScreen(minecraft.player);
            minecraft.setScreen(screen);
            screen.showStashConfirm();
        }
    }

    private static boolean isCreativeMode(Minecraft minecraft) {
        return minecraft.gameMode != null && minecraft.gameMode.getPlayerMode() == GameType.CREATIVE;
    }
}

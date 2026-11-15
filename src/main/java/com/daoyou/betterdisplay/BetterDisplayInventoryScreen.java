package com.daoyou.betterdisplay;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.scores.DisplaySlot;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BetterDisplayInventoryScreen extends Screen {
    private static final Identifier PROFILE_TEXTURE = Identifier.fromNamespaceAndPath("saoui", "textures/menu/parts/profilebg.png");
    private static final Identifier CONFIRM_BG_TEXTURE = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/confirm_bg.png");
    private static final int CONFIRM_BG_WIDTH = 142;
    private static final int CONFIRM_BG_HEIGHT = 138;
    private static final Identifier EMPTY_ICON = icon("cancel");
    private static final Identifier TRASH_ICON = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/icon_trash.png");
    private static final int POPUP_CLOSE_BUTTON_SIZE = 12;
    private static final int PROFILE_TEXTURE_SIZE = 256;
    private static final int PROFILE_SOURCE_WIDTH = 165;
    private static final int PROFILE_SOURCE_HEIGHT = 256;
    private static final int PANEL_WIDTH = 148;
    private static final int PANEL_HEIGHT = 230;
    private static final int SLOT_SIZE = 20;
    private static final List<EquipmentSlot> PROFILE_ARMOR = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final int ICON_SIZE = 19;
    private static final int ROW_WIDTH = 84;
    private static final int ROW_HEIGHT = 18;
    private static final int TOP_SPACING = 25;
    private static final int ROW_SPACING = 20;
    private static final int CONTENT_LIMIT = 7;
    private static final int LEVEL_GAP = 5;
    private static final int MAX_LEVELS = 8;
    private static final int POPUP_WIDTH = 220;
    private static final int POPUP_TITLE_HEIGHT = 40;
    private static final int POPUP_SHADOW_HEIGHT = 20;
    private static final int POPUP_BUTTON_HEIGHT = 60;
    private static final List<RegisteredFilter> REGISTERED_ITEM_FILTERS = new ArrayList<>();
    private static final List<RegisteredRootAction> REGISTERED_ROOT_ACTIONS = new ArrayList<>();

    private final LocalPlayer player;
    private final long openStartedAt;
    private List<MenuNode> roots;
    private final List<MenuNode> path = new ArrayList<>();
    private final long[] levelStartedAt = new long[MAX_LEVELS];
    private final int[] focusedRows = new int[MAX_LEVELS];
    private final int[] scrollOffsets = new int[MAX_LEVELS];
    private BetterDisplayPopup popup;
    private boolean showingPlayerList;
    private boolean waitingForSaoKeyRelease;
    private int focusedRootIndex;
    private final Matrix3x2f menuTransform = new Matrix3x2f();
    private final Matrix3x2f menuInverse = new Matrix3x2f();
    private int tooltipMouseX;
    private int tooltipMouseY;

    public static void registerItemFilter(String title, Identifier icon, Predicate<ItemStack> filter) {
        synchronized (REGISTERED_ITEM_FILTERS) {
            REGISTERED_ITEM_FILTERS.add(new RegisteredFilter(Objects.requireNonNull(title), Objects.requireNonNull(icon), Objects.requireNonNull(filter)));
        }
    }

    public static void registerRootAction(String title, Identifier icon, Consumer<BetterDisplayInventoryScreen> action) {
        synchronized (REGISTERED_ROOT_ACTIONS) {
            REGISTERED_ROOT_ACTIONS.add(new RegisteredRootAction(Objects.requireNonNull(title), Objects.requireNonNull(icon), Objects.requireNonNull(action)));
        }
    }

    public BetterDisplayInventoryScreen(LocalPlayer player) {
        super(Component.translatable("screen.betterdisplay.inventory"));
        this.player = player;
        this.openStartedAt = System.currentTimeMillis();
        BetterDisplayClientSounds.playMenuOpen();
        Arrays.fill(this.levelStartedAt, this.openStartedAt);
        this.roots = buildRoots();
    }

    SaoMenuState sharedMenuState() {
        List<String> sharedPath = new ArrayList<>();
        for (MenuNode node : this.path) {
            if (node.shareId == null || sharedPath.size() == SaoMenuState.MAX_DEPTH) {
                break;
            }
            sharedPath.add(node.shareId);
        }

        int options = equipmentShareMask();
        if (BetterDisplayConfig.shareUiState) {
            options |= SaoMenuModel.SHARE_UI_STATE_ENABLED;
        }
        if (this.path.isEmpty()) {
            String focus = this.roots.get(this.focusedRootIndex).shareId;
            return new SaoMenuState(sharedPath, focus, List.of(), null, 0, options, false, null, null);
        }
        if (sharedPath.size() != this.path.size()) {
            return new SaoMenuState(sharedPath, "", List.of(), null, 0, options, false, null, null);
        }

        int level = this.path.size();
        List<MenuNode> rows = rowsFor(this.path.getLast());
        if (rows.isEmpty()) {
            return new SaoMenuState(sharedPath, "", List.of(), null, 0, options, false, null, null);
        }
        int focusIndex = Math.clamp(this.focusedRows[level], 0, rows.size() - 1);
        int scroll = Math.clamp(this.scrollOffsets[level], 0, maxScrollOffset(rows));
        scroll = Math.clamp(scroll, Math.max(0, focusIndex - CONTENT_LIMIT + 1), focusIndex);
        List<SaoMenuItem> items = new ArrayList<>();
        String focus = rows.get(focusIndex).shareId;
        for (int i = scroll; i < Math.min(rows.size(), scroll + SaoMenuState.MAX_ITEMS); i++) {
            MenuNode row = rows.get(i);
            if (row.itemRow) {
                SaoMenuItem item = RemoteUiSync.toMenuItem(row.stack);
                if (item != null) {
                    if (i == focusIndex) {
                        focus = "item_" + items.size();
                    }
                    items.add(item);
                }
            }
        }
        return new SaoMenuState(sharedPath, focus, items, null, scroll, options, false, null, null);
    }

    private int equipmentShareMask() {
        int mask = SaoMenuModel.EQUIPMENT_MASK_PRESENT;
        for (MenuNode section : this.roots.getFirst().children) {
            if ("equipment".equals(section.shareId)) {
                for (MenuNode category : section.children) {
                    mask |= switch (category.shareId) {
                        case "armor" -> SaoMenuModel.ARMOR_AVAILABLE;
                        case "weapon" -> SaoMenuModel.WEAPON_AVAILABLE;
                        case "tool" -> SaoMenuModel.TOOL_AVAILABLE;
                        case null, default -> 0;
                    };
                }
            }
        }
        return mask;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.showingPlayerList) {
            renderPlayerList(graphics, mouseX, mouseY);
            return;
        }
        updateMenuProjection();
        tooltipMouseX = mouseX;
        tooltipMouseY = mouseY;
        Vec2 pointer = menuPoint(mouseX, mouseY);
        int menuMouseX = (int) Math.floor(pointer.x);
        int menuMouseY = (int) Math.floor(pointer.y);
        float open = openProgress();
        int panelX = panelX(open);
        int panelY = panelY();
        int menuX = menuX(panelX, open);
        int menuY = menuY(panelY);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().mul(menuTransform);
            if (shouldRenderProfilePanel()) {
                renderProfilePanel(graphics, panelX, panelY, menuMouseX, menuMouseY);
            }
            renderTopLevel(graphics, menuX, menuY, menuMouseX, menuMouseY);
            renderOpenLevels(graphics, menuX, menuY, menuMouseX, menuMouseY);
        } finally {
            graphics.pose().popMatrix();
        }
        if (this.popup != null) {
            this.popup.render(graphics, mouseX, mouseY);
            if (this.popup.isFinished()) {
                this.popup = null;
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.minecraft.options.keyPlayerList.matchesMouse(event)) {
            return handleSaoKeyPress();
        }
        if (this.showingPlayerList) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && hitCloseButton(event.x(), event.y(), 0, 0, this.width)) {
                BetterDisplayClientSounds.playMenuSelect();
                closePlayerList();
            }
            return true;
        }
        if (this.popup != null) {
            this.popup.mouseClicked(event.x(), event.y());
            return true;
        }
        Vec2 pointer = menuPoint(event.x(), event.y());
        float open = openProgress();
        int panelX = panelX(open);
        int panelY = panelY();
        int menuX = menuX(panelX, open);
        int menuY = menuY(panelY);
        if (shouldRenderProfilePanel() && handleProfileSlotClick(pointer.x, pointer.y, panelX, panelY)) {
            return true;
        }
        for (int i = 0; i < this.roots.size(); i++) {
            float alpha = rootAlpha(open, i);
            int x = rootIconX(menuX, alpha);
            int y = menuY + i * TOP_SPACING;
            if (alpha > 0.05F && inside(pointer.x, pointer.y, x, y, ICON_SIZE, ICON_SIZE)) {
                this.focusedRootIndex = i;
                selectRoot(this.roots.get(i));
                return true;
            }
        }
        if (handleLevelClick(pointer.x, pointer.y, menuX, menuY)) {
            return true;
        }
        if (!this.path.isEmpty()) {
            closeMenu();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (BetterDisplayClient.saoMenuKeyMatches(event)) {
            return handleSaoKeyPress();
        }
        if (this.showingPlayerList) {
            if (event.isEscape() || event.isLeft()) {
                closePlayerList();
            }
            return true;
        }
        if (BetterDisplayClient.toggleHandSlotsKeyMatches(event)) {
            toggleCombatSlots();
            return true;
        }
        if (BetterDisplayClient.stashHotbarKeyMatches(event)) {
            BetterDisplayClient.requestStashHotbarFromScreen();
            return true;
        }
        if (this.popup != null) {
            if (event.isEscape()) {
                closePopup();
            } else if (event.isConfirmation()) {
                this.popup.activateDefault();
            }
            return true;
        }
        if (event.isEscape()) {
            if (!this.path.isEmpty()) {
                closeMenu();
                return true;
            }
            this.onClose();
            return true;
        }
        if (event.isUp()) {
            moveFocus(-1);
            return true;
        }
        if (event.isDown()) {
            moveFocus(1);
            return true;
        }
        if (event.isLeft()) {
            moveBack();
            return true;
        }
        if (event.isRight() || event.isConfirmation()) {
            activateFocus();
            return true;
        }
        return super.keyPressed(event);
    }

    void waitForSaoKeyRelease() {
        this.waitingForSaoKeyRelease = true;
    }

    private boolean handleSaoKeyPress() {
        if (this.waitingForSaoKeyRelease) {
            return true;
        }
        waitForSaoKeyRelease();
        if (this.showingPlayerList) {
            closePlayerList();
        } else if (this.popup != null) {
            closePopup();
        } else {
            this.onClose();
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (BetterDisplayClient.saoMenuKeyMatches(event)) {
            this.waitingForSaoKeyRelease = false;
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.showingPlayerList) {
            return true;
        }
        if (this.popup != null) {
            return this.popup.mouseDragged(dragX, dragY);
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.minecraft.options.keyPlayerList.matchesMouse(event)) {
            this.waitingForSaoKeyRelease = false;
            return true;
        }
        if (this.showingPlayerList) {
            return true;
        }
        if (this.popup != null) {
            this.popup.mouseReleased();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (this.showingPlayerList || this.popup != null) {
            return true;
        }
        if (deltaY != 0.0D) {
            Vec2 pointer = menuPoint(mouseX, mouseY);
            float open = openProgress();
            int panelX = panelX(open);
            int panelY = panelY();
            int menuX = menuX(panelX, open);
            int menuY = menuY(panelY);
            if (scrollLevelAt(pointer.x, pointer.y, menuX, menuY, deltaY > 0.0D ? -1 : 1)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void removed() {
        this.waitingForSaoKeyRelease = false;
        closePlayerList();
        super.removed();
    }

    private boolean shouldRenderProfilePanel() {
        return !this.path.isEmpty() && !this.roots.isEmpty() && this.path.get(0) == this.roots.get(0);
    }

    private int panelX(float open) {
        return this.width / 2 - PANEL_WIDTH - 22 - ICON_SIZE / 2 - Math.round((1.0F - open) * 30.0F);
    }

    private int panelY() {
        return this.height / 2 - PANEL_HEIGHT / 2;
    }

    private int menuX(int panelX, float open) {
        return panelX + PANEL_WIDTH + 22 + Math.round((1.0F - open) * 30.0F);
    }

    private int menuY(int panelY) {
        return panelY + (PANEL_HEIGHT - ((this.roots.size() - 1) * TOP_SPACING + ICON_SIZE)) / 2;
    }

    Vec2 projectMenuPoint(float x, float y) {
        Vector2f point = menuTransform.transformPosition(x, y, new Vector2f());
        return new Vec2(point.x, point.y);
    }

    private Vec2 menuPoint(double screenX, double screenY) {
        Vector2f point = menuInverse.transformPosition((float) screenX, (float) screenY, new Vector2f());
        return new Vec2(point.x, point.y);
    }

    private void updateMenuProjection() {
        float centerX = this.width / 2.0F;
        float centerY = this.height / 2.0F;
        float scale = menuScale();
        menuTransform.translation(centerX, centerY).scale(scale).translate(-centerX, -centerY);
        var view = BetterDisplayClient.menuView(this);
        if (view.yawOffset() != 0.0F || view.pitchOffset() != 0.0F) {
            var renderer = this.minecraft.gameRenderer;
            var camera = renderer.getMainCamera();
            Vec3 forward = new Vec3(camera.forwardVector());
            Vec3 right = new Vec3(camera.leftVector()).scale(-1.0);
            Vec3 up = new Vec3(camera.upVector());
            var opening = new Quaternionf().rotationYXZ((float) Math.toRadians(180.0 - view.anchorYaw()),
                (float) Math.toRadians(-view.anchorPitch()), 0.0F);
            Vec3 anchor = new Vec3(opening.transform(new Vector3f(0, 0, -1)));
            Vec3 menuRight = new Vec3(opening.transform(new Vector3f(1, 0, 0)));
            Vec3 menuDown = new Vec3(opening.transform(new Vector3f(0, -1, 0)));
            double depth = forward.dot(anchor);
            // Measure the actual game projection, so the panel pans with the world at any FOV.
            Vec3 projectedUnit = renderer.projectPointToScreen(camera.position().add(forward).add(right).add(up));
            double focalX = projectedUnit.x * centerX;
            double focalY = projectedUnit.y * centerY;
            if (depth > 0.1 && focalX > 0.0 && focalY > 0.0) {
                double x = right.dot(anchor) / depth;
                double y = up.dot(anchor) / depth;
                float m00 = (float) (scale * (right.dot(menuRight) - x * forward.dot(menuRight)) / depth);
                float m01 = (float) (-scale * (up.dot(menuRight) - y * forward.dot(menuRight)) / depth);
                float m10 = (float) (scale * (right.dot(menuDown) - x * forward.dot(menuDown)) / depth);
                float m11 = (float) (-scale * (up.dot(menuDown) - y * forward.dot(menuDown)) / depth);
                float anchorX = (float) (centerX + focalX * x);
                float anchorY = (float) (centerY - focalY * y);
                menuTransform.set(m00, m01, m10, m11,
                    anchorX - m00 * centerX - m10 * centerY, anchorY - m01 * centerX - m11 * centerY);
            }
        }
        menuTransform.invert(menuInverse);
    }

    private float menuScale() {
        float scale = Math.min(0.88F, Math.min(this.width / 560.0F, this.height / 280.0F));
        if (this.path.isEmpty()) {
            return scale;
        }

        // Keep longer menus within reach of the bounded mouse look, without moving their origin.
        int reachX = ICON_SIZE / 2 + this.path.size() * (ROW_WIDTH + LEVEL_GAP);
        int panelY = panelY();
        int columnY = menuY(panelY) + this.roots.indexOf(this.path.getFirst()) * TOP_SPACING;
        int bottom = panelY + PANEL_HEIGHT;
        for (int i = 0; i < this.path.size(); i++) {
            int level = i + 1;
            List<MenuNode> rows = rowsFor(this.path.get(i));
            normalizeLevelState(level, rows);
            int start = visibleStart(level, rows);
            int visibleCount = Math.min(CONTENT_LIMIT, rows.size() - start);
            if (visibleCount > 0) {
                bottom = Math.max(bottom, columnY + (visibleCount - 1) * ROW_SPACING + ROW_HEIGHT);
            }
            if (level < this.path.size()) {
                int selected = rows.indexOf(this.path.get(level));
                if (selected >= 0) {
                    columnY += (selected - start) * ROW_SPACING;
                }
            }
        }
        float reachY = bottom - this.height / 2.0F;
        return Math.min(scale, Math.min(this.width * 0.59F / reachX, this.height * 0.59F / reachY));
    }

    private void renderProfilePanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, PROFILE_TEXTURE, x, y, 0.0F, 0.0F, PANEL_WIDTH, PANEL_HEIGHT, PROFILE_SOURCE_WIDTH, PROFILE_SOURCE_HEIGHT, PROFILE_TEXTURE_SIZE, PROFILE_TEXTURE_SIZE, 0xF2FFFFFF);
        int characterX = x + PANEL_WIDTH / 2 - 9;
        int characterY = y + PANEL_HEIGHT / 2;
        int shadowHeight = 18 + Math.max(Math.min(mouseY - y, 0), -16);
        graphics.blit(RenderPipelines.GUI_TEXTURED, PROFILE_TEXTURE, characterX - 18, characterY - shadowHeight / 2, 200.0F, 85.0F, 36, shadowHeight, 56, 30, PROFILE_TEXTURE_SIZE, PROFILE_TEXTURE_SIZE, 0xAAFFFFFF);
        String name = this.player.getName().getString();
        drawCenteredString(graphics, name, x + sx(50) + name.length() / 2, y + sy(20), 0xFF000000);
        renderProfileAvatar(graphics, x, y);
        // 资料卡只保留人物模型与数值；装备信息统一放在右侧“装备”选项里。
        List<String> profile = profileStats();
        for (int i = 0; i < profile.size(); i++) {
            drawCenteredString(graphics, profile.get(i), x + PANEL_WIDTH / 2 - 9, y + sy(172) + i * 9, 0xFF000000);
        }
    }

    private void renderProfileAvatar(GuiGraphics graphics, int x, int y) {
        Vec2 first = projectMenuPoint(x + sx(53), y + sy(48));
        Vec2 second = projectMenuPoint(x + sx(118), y + sy(153));
        int modelScale = Math.max(1, Math.round(36 * (second.y - first.y) / (sy(153) - sy(48))));
        if (!(this.minecraft.getEntityRenderDispatcher().getRenderer(this.player)
            .createRenderState(this.player, 1.0F) instanceof AvatarRenderState avatar)) {
            return;
        }
        boolean rightMain = this.player.getMainArm() == HumanoidArm.RIGHT;
        ItemStack right = profileHand(rightMain);
        ItemStack left = profileHand(!rightMain);
        var models = this.minecraft.getItemModelResolver();
        models.updateForLiving(avatar.rightHandItemState, right, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, this.player);
        models.updateForLiving(avatar.leftHandItemState, left, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, this.player);
        avatar.rightHandItemStack = right;
        avatar.leftHandItemStack = left;
        avatar.rightArmPose = right.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        avatar.leftArmPose = left.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        float yaw = (float) Math.atan(((first.x + second.x) / 2 - tooltipMouseX) / 40.0F) * 20.0F;
        float pitch = (float) Math.atan(((first.y + second.y) / 2 - tooltipMouseY) / 40.0F) * 20.0F;
        avatar.bodyRot = 180.0F + yaw;
        avatar.yRot = yaw;
        avatar.xRot = -pitch;
        avatar.boundingBoxWidth /= avatar.scale;
        avatar.boundingBoxHeight /= avatar.scale;
        avatar.scale = 1.0F;
        avatar.lightCoords = 0xF000F0;
        avatar.outlineColor = 0;
        avatar.nameTag = null;
        avatar.shadowPieces.clear();
        Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(pitch));
        graphics.submitEntityRenderState(avatar, modelScale, new Vector3f(0, avatar.boundingBoxHeight / 2 + 0.0625F, 0),
            new Quaternionf().rotateZ((float) Math.PI).mul(tilt), tilt,
            Math.round(first.x), Math.round(first.y), Math.round(second.x), Math.round(second.y));
    }

    private void renderTopLevel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        float open = openProgress();
        for (int i = 0; i < this.roots.size(); i++) {
            MenuNode node = this.roots.get(i);
            float alpha = rootAlpha(open, i);
            int tabX = rootIconX(x, alpha);
            int tabY = y + i * TOP_SPACING;
            boolean selected = (!this.path.isEmpty() && this.path.get(0) == node) || (this.path.isEmpty() && this.focusedRootIndex == i);
            drawIconButton(graphics, node, tabX, tabY, selected, alpha);
            if (this.popup == null && inside(mouseX, mouseY, tabX, tabY, ICON_SIZE, ICON_SIZE)) {
                graphics.fill(tabX, tabY, tabX + ICON_SIZE, tabY + ICON_SIZE, alpha(0xFFFFFF, alpha * 0.18F));
                graphics.setTooltipForNextFrame(this.font, Component.literal(node.title), tooltipMouseX, tooltipMouseY);
            }
        }
    }

    private static float rootAlpha(float open, int index) {
        return clamp((open - index * 0.10F) / 0.52F);
    }

    private static int rootIconX(int x, float alpha) {
        return x - Math.round((1.0F - alpha) * 24.0F);
    }

    private void renderOpenLevels(GuiGraphics graphics, int rootX, int rootY, int mouseX, int mouseY) {
        if (this.path.isEmpty()) {
            return;
        }
        int anchorY = rootY + this.roots.indexOf(this.path.get(0)) * TOP_SPACING;
        int x = rootX + ICON_SIZE + LEVEL_GAP;
        for (int level = 1; level <= this.path.size() && level < MAX_LEVELS; level++) {
            MenuNode parent = this.path.get(level - 1);
            List<MenuNode> rows = rowsFor(parent);
            normalizeLevelState(level, rows);
            renderRows(graphics, rows, x, anchorY, level, mouseX, mouseY);
            if (level < this.path.size()) {
                int selected = rows.indexOf(this.path.get(level));
                if (selected >= 0) {
                    anchorY += (selected - visibleStart(level, rows)) * ROW_SPACING;
                }
            }
            x += ROW_WIDTH + LEVEL_GAP;
        }
    }

    private void renderRows(GuiGraphics graphics, List<MenuNode> rows, int x, int y, int level, int mouseX, int mouseY) {
        int start = visibleStart(level, rows);
        int end = Math.min(rows.size(), start + CONTENT_LIMIT);
        float levelProgress = levelProgress(level);
        for (int rowIndex = start; rowIndex < end; rowIndex++) {
            int visibleIndex = rowIndex - start;
            MenuNode row = rows.get(rowIndex);
            float alpha = clamp((levelProgress - visibleIndex * 0.08F) / 0.42F);
            int rowX = rowX(x, level, visibleIndex);
            int rowY = y + visibleIndex * ROW_SPACING;
            boolean selected = this.path.contains(row) || this.focusedRows[level] == rowIndex;
            drawRow(graphics, row, rowX, rowY, selected, alpha);
            if (this.popup == null && inside(mouseX, mouseY, rowX, rowY, ROW_WIDTH, ROW_HEIGHT)) {
                graphics.fill(rowX, rowY, rowX + ROW_WIDTH, rowY + ROW_HEIGHT, alpha(0xFFFFFF, alpha * 0.18F));
                if (row.itemRow && !row.stack.isEmpty()) {
                    renderItemTooltip(graphics, row.stack, equippedStatus(row), tooltipMouseX, tooltipMouseY);
                } else {
                    graphics.setTooltipForNextFrame(this.font, Component.literal(row.disabled ? row.title + " - 未实装" : row.title),
                        tooltipMouseX, tooltipMouseY);
                }
            }
        }
    }

    private boolean handleLevelClick(double mouseX, double mouseY, int rootX, int rootY) {
        if (this.path.isEmpty()) {
            return false;
        }
        int anchorY = rootY + this.roots.indexOf(this.path.get(0)) * TOP_SPACING;
        int x = rootX + ICON_SIZE + LEVEL_GAP;
        for (int level = 1; level <= this.path.size() && level < MAX_LEVELS; level++) {
            MenuNode parent = this.path.get(level - 1);
            List<MenuNode> rows = rowsFor(parent);
            normalizeLevelState(level, rows);
            int start = visibleStart(level, rows);
            int end = Math.min(rows.size(), start + CONTENT_LIMIT);
            for (int rowIndex = start; rowIndex < end; rowIndex++) {
                int visibleIndex = rowIndex - start;
                int rowX = rowX(x, level, visibleIndex);
                int rowY = anchorY + visibleIndex * ROW_SPACING;
                if (inside(mouseX, mouseY, rowX, rowY, ROW_WIDTH, ROW_HEIGHT)) {
                    this.focusedRows[level] = rowIndex;
                    activateRow(rows.get(rowIndex), level);
                    return true;
                }
            }
            if (level < this.path.size()) {
                int selected = rows.indexOf(this.path.get(level));
                if (selected >= 0) {
                    anchorY += (selected - visibleStart(level, rows)) * ROW_SPACING;
                }
            }
            x += ROW_WIDTH + LEVEL_GAP;
        }
        return false;
    }

    private void activateRow(MenuNode row, int level) {
        if (row.itemRow) {
            BetterDisplayClientSounds.playMenuSelect();
            openItemPopup(row);
            return;
        }
        if (row.disabled) {
            openNotice(row.title, List.of("未实装"));
            return;
        }
        if (row.action != null) {
            BetterDisplayClientSounds.playMenuSelect();
            row.action.run();
            return;
        }
        if (hasExpandableRows(row) && level + 1 < MAX_LEVELS) {
            BetterDisplayClientSounds.playMenuSelect();
            if (this.path.size() > level && this.path.get(level) == row) {
                trimPath(level);
            } else {
                trimPath(level);
                this.path.add(row);
                this.levelStartedAt[this.path.size()] = System.currentTimeMillis();
                this.focusedRows[this.path.size()] = 0;
                this.scrollOffsets[this.path.size()] = 0;
            }
        }
    }

    private void selectRoot(MenuNode node) {
        if (node.disabled) {
            openNotice(node.title, List.of("未实装"));
            return;
        }
        if (!this.path.isEmpty() && this.path.get(0) == node) {
            BetterDisplayClientSounds.playMenuSelect();
            closeMenu();
            return;
        }
        BetterDisplayClientSounds.playMenuSelect();
        this.path.clear();
        Arrays.fill(this.focusedRows, 0);
        Arrays.fill(this.scrollOffsets, 0);
        this.path.add(node);
        this.levelStartedAt[1] = System.currentTimeMillis();
    }

    private void closeMenu() {
        this.path.clear();
        Arrays.fill(this.focusedRows, 0);
        Arrays.fill(this.scrollOffsets, 0);
    }

    private void moveFocus(int delta) {
        if (this.path.isEmpty()) {
            this.focusedRootIndex = wrapIndex(this.focusedRootIndex + delta, this.roots.size());
            return;
        }
        int level = Math.min(this.path.size(), MAX_LEVELS - 1);
        List<MenuNode> rows = rowsFor(this.path.get(level - 1));
        normalizeLevelState(level, rows);
        if (!rows.isEmpty()) {
            this.focusedRows[level] = wrapIndex(this.focusedRows[level] + delta, rows.size());
            ensureRowVisible(level, rows, this.focusedRows[level]);
        }
    }

    private void moveBack() {
        if (!this.path.isEmpty()) {
            trimPath(this.path.size() - 1);
        }
    }

    private void activateFocus() {
        if (this.path.isEmpty()) {
            selectRoot(this.roots.get(this.focusedRootIndex));
            return;
        }
        int level = Math.min(this.path.size(), MAX_LEVELS - 1);
        List<MenuNode> rows = rowsFor(this.path.get(level - 1));
        normalizeLevelState(level, rows);
        if (!rows.isEmpty()) {
            int index = wrapIndex(this.focusedRows[level], rows.size());
            this.focusedRows[level] = index;
            ensureRowVisible(level, rows, index);
            activateRow(rows.get(index), level);
        }
    }

    private boolean scrollLevelAt(double mouseX, double mouseY, int rootX, int rootY, int delta) {
        if (this.path.isEmpty()) {
            return false;
        }
        int anchorY = rootY + this.roots.indexOf(this.path.get(0)) * TOP_SPACING;
        int x = rootX + ICON_SIZE + LEVEL_GAP;
        for (int level = 1; level <= this.path.size() && level < MAX_LEVELS; level++) {
            MenuNode parent = this.path.get(level - 1);
            List<MenuNode> rows = rowsFor(parent);
            normalizeLevelState(level, rows);
            int start = visibleStart(level, rows);
            int end = Math.min(rows.size(), start + CONTENT_LIMIT);
            for (int rowIndex = start; rowIndex < end; rowIndex++) {
                int visibleIndex = rowIndex - start;
                int rowX = rowX(x, level, visibleIndex);
                int rowY = anchorY + visibleIndex * ROW_SPACING;
                if (inside(mouseX, mouseY, rowX, rowY, ROW_WIDTH, ROW_HEIGHT)) {
                    int next = Math.max(0, Math.min(this.scrollOffsets[level] + delta, maxScrollOffset(rows)));
                    if (next != this.scrollOffsets[level]) {
                        this.scrollOffsets[level] = next;
                        if (level == this.path.size() && (this.focusedRows[level] < next || this.focusedRows[level] >= next + CONTENT_LIMIT)) {
                            this.focusedRows[level] = next;
                        }
                    }
                    return true;
                }
            }
            if (level < this.path.size()) {
                int selected = rows.indexOf(this.path.get(level));
                if (selected >= 0) {
                    anchorY += (selected - visibleStart(level, rows)) * ROW_SPACING;
                }
            }
            x += ROW_WIDTH + LEVEL_GAP;
        }
        return false;
    }

    private void normalizeLevelState(int level, List<MenuNode> rows) {
        if (rows.isEmpty()) {
            this.focusedRows[level] = 0;
            this.scrollOffsets[level] = 0;
            return;
        }
        this.focusedRows[level] = Math.max(0, Math.min(this.focusedRows[level], rows.size() - 1));
        this.scrollOffsets[level] = Math.max(0, Math.min(this.scrollOffsets[level], maxScrollOffset(rows)));
        if (level < this.path.size()) {
            int selected = rows.indexOf(this.path.get(level));
            if (selected >= 0) {
                ensureRowVisible(level, rows, selected);
            }
        } else {
            ensureRowVisible(level, rows, this.focusedRows[level]);
        }
    }

    private int visibleStart(int level, List<MenuNode> rows) {
        this.scrollOffsets[level] = Math.max(0, Math.min(this.scrollOffsets[level], maxScrollOffset(rows)));
        return this.scrollOffsets[level];
    }

    private void ensureRowVisible(int level, List<MenuNode> rows, int rowIndex) {
        if (rows.isEmpty()) {
            this.scrollOffsets[level] = 0;
            return;
        }
        int clampedIndex = Math.max(0, Math.min(rowIndex, rows.size() - 1));
        int start = visibleStart(level, rows);
        if (clampedIndex < start) {
            this.scrollOffsets[level] = clampedIndex;
        } else if (clampedIndex >= start + CONTENT_LIMIT) {
            this.scrollOffsets[level] = clampedIndex - CONTENT_LIMIT + 1;
        }
        this.scrollOffsets[level] = Math.max(0, Math.min(this.scrollOffsets[level], maxScrollOffset(rows)));
    }

    private int maxScrollOffset(List<MenuNode> rows) {
        return Math.max(0, rows.size() - CONTENT_LIMIT);
    }

    private int rowX(int x, int level, int index) {
        float alpha = clamp((levelProgress(level) - index * 0.08F) / 0.42F);
        return x + Math.round((1.0F - alpha) * 18.0F);
    }

    private void trimPath(int keep) {
        while (this.path.size() > keep) {
            this.path.remove(this.path.size() - 1);
        }
        for (int i = Math.max(keep + 1, 0); i < this.focusedRows.length; i++) {
            this.focusedRows[i] = 0;
            this.scrollOffsets[i] = 0;
        }
    }

    private List<MenuNode> rowsFor(MenuNode parent) {
        if (parent.filter != null) {
            return itemRows(parent.filter);
        }
        return parent.children;
    }

    private boolean hasExpandableRows(MenuNode node) {
        return node.filter != null || !node.children.isEmpty();
    }

    private List<MenuNode> itemRows(StackFilter filter) {
        List<MenuNode> rows = new ArrayList<>();
        collectToolSkillRows(rows, filter);
        for (int i = InventoryMenu.ARMOR_SLOT_START; i <= InventoryMenu.SHIELD_SLOT; i++) {
            collectMenuSlot(rows, filter, i);
        }
        if (rows.isEmpty()) {
            rows.add(MenuNode.disabled("空", EMPTY_ICON).shared("empty"));
        }
        return rows;
    }

    private ItemStack handStack(BetterDisplayCombat.HandSlot slot) {
        if (BetterDisplayCombat.clientDrawn()) {
            return slot == BetterDisplayCombat.HandSlot.RIGHT ? this.player.getMainHandItem() : this.player.getOffhandItem();
        }
        return slot == BetterDisplayCombat.HandSlot.RIGHT ? BetterDisplayCombat.clientRightHand() : BetterDisplayCombat.clientLeftHand();
    }

    private ItemStack profileHand(boolean main) {
        ItemStack equipped = handStack(main ? BetterDisplayCombat.HandSlot.RIGHT : BetterDisplayCombat.HandSlot.LEFT);
        return equipped.isEmpty() ? (main ? this.player.getMainHandItem() : this.player.getOffhandItem()) : equipped;
    }

    private void collectToolSkillRows(List<MenuNode> rows, StackFilter filter) {
        for (int i = 0; i < BetterDisplayCombat.TOOL_SKILL_SLOTS; i++) {
            ItemStack stack = BetterDisplayCombat.clientToolSkillSlot(i);
            if (!stack.isEmpty() && filter.accept(stack)) {
                rows.add(MenuNode.toolSkillRow(i, stack));
            }
        }
    }

    private void collectMenuSlot(List<MenuNode> rows, StackFilter filter, int menuSlot) {
        if (menuSlot >= this.player.inventoryMenu.slots.size()) {
            return;
        }
        Slot slot = this.player.inventoryMenu.slots.get(menuSlot);
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty() && filter.accept(stack)) {
            // 列表标题直接带装备状态，不必悬停才能看出来。
            MenuNode row = MenuNode.itemRow(stack, menuSlot);
            row.equipTag = equippedStatus(row);
            rows.add(row);
        }
    }

    private List<MenuNode> buildRoots() {
        MenuNode equipment = MenuNode.texture("装备", icon("equipment")).shared("equipment");
        MenuNode armor = MenuNode.filter("防具", Items.IRON_CHESTPLATE, stack -> isArmorFor(stack, EquipmentSlot.HEAD) || isArmorFor(stack, EquipmentSlot.CHEST) || isArmorFor(stack, EquipmentSlot.LEGS) || isArmorFor(stack, EquipmentSlot.FEET) || stack.is(Items.SHIELD)).shared("armor");
        MenuNode weapons = MenuNode.filter("武器", Items.IRON_SWORD, this::isWeapon).shared("weapon");
        MenuNode tools = MenuNode.filter("工具", Items.IRON_PICKAXE, this::isStrictTool).shared("tool");
        addCategoryIfNotEmpty(equipment, armor);
        addCategoryIfNotEmpty(equipment, weapons);
        addCategoryIfNotEmpty(equipment, tools);
        if (equipment.children.isEmpty()) {
            equipment.add(MenuNode.disabled("空", EMPTY_ICON).shared("empty"));
        }
        MenuNode items = MenuNode.texture("物品", icon("items")).shared("items");
        items.add(MenuNode.filter("消耗品", Items.APPLE, this::isConsumable).shared("consumable"));
        items.add(MenuNode.filter("方块", Items.COBBLESTONE, stack -> stack.getItem() instanceof BlockItem).shared("block"));
        items.add(MenuNode.filter("杂项", Items.IRON_INGOT, this::isMisc).shared("misc"));
        List<RegisteredFilter> registeredFilters = registeredFilters();
        if (!registeredFilters.isEmpty()) {
            MenuNode customFilters = MenuNode.texture("扩展过滤器", icon("items"));
            for (RegisteredFilter registeredFilter : registeredFilters) {
                customFilters.add(MenuNode.filterTexture(registeredFilter.title(), registeredFilter.icon(), stack -> registeredFilter.filter().test(stack)));
            }
            items.add(customFilters);
        }
        MenuNode profile = MenuNode.texture("资料", icon("profile")).shared("profile");
        profile.add(equipment);
        profile.add(items);
        profile.add(MenuNode.disabled("技能", icon("skills")).shared("skills"));
        profile.add(MenuNode.action("合成", icon("crafting"), () -> BetterDisplayClient.openVanillaInventory(this.player)).shared("crafting"));
        profile.add(MenuNode.action("角色资料", icon("profile"), this::openProfilePopup).shared("character_profile"));
        MenuNode social = MenuNode.texture("社交", icon("social")).shared("social");
        SaoMenuModel.Entry onlinePlayers = SaoMenuModel.ONLINE_PLAYERS;
        social.add(MenuNode.action(onlinePlayers.label(), icon(onlinePlayers.icon()), this::openPlayerList).shared(onlinePlayers.id()));
        social.add(MenuNode.disabled("公会", icon("guild")).shared("guild"));
        MenuNode party = MenuNode.texture("队伍", icon("party")).shared("party");
        party.add(MenuNode.action("邀请", icon("invite"), this::openInvitePlayerPopup).shared("invite"));
        party.add(MenuNode.action("离开", EMPTY_ICON, this::openLeavePartyPopup).shared("leave_party"));
        MenuNode friends = MenuNode.texture("好友", icon("friend")).shared("friends");
        friends.add(MenuNode.action("添加好友", icon("friend"), this::openAddFriendPopup).shared("add_friend"));
        friends.add(MenuNode.disabled("离线好友", icon("logout")).shared("offline_friends"));
        social.add(party);
        social.add(friends);
        MenuNode message = MenuNode.texture("消息", icon("message")).shared("message");
        message.add(MenuNode.disabled("消息盒", icon("messagereceived")).shared("inbox"));
        MenuNode navigation = MenuNode.texture("导航", icon("navigation")).shared("navigation");
        MenuNode quests = MenuNode.texture("任务", icon("quest")).shared("quest");
        quests.add(MenuNode.disabled("未完成任务", EMPTY_ICON).shared("incomplete_quests"));
        quests.add(MenuNode.disabled("已完成任务", icon("confirm")).shared("completed_quests"));
        navigation.add(quests);
        MenuNode recipes = MenuNode.texture("配方", icon("crafting")).shared("recipes");
        recipes.add(MenuNode.disabled("已解锁配方", icon("confirm")).shared("unlocked_recipes"));
        recipes.add(MenuNode.disabled("未解锁配方", EMPTY_ICON).shared("locked_recipes"));
        navigation.add(recipes);
        navigation.add(MenuNode.disabled("区域地图", icon("fieldmap")).shared("fieldmap"));
        navigation.add(MenuNode.disabled("迷宫地图", icon("dungeonmap")).shared("dungeonmap"));
        MenuNode settings = MenuNode.texture("设置", icon("settings")).shared("settings");
        MenuNode options = MenuNode.texture("选项", icon("option")).shared("options");
        addOptionCoreCategories(options);
        settings.add(options);
        settings.add(MenuNode.action("菜单", icon("help"), () -> this.minecraft.setScreen(new PauseScreen(true))).shared("menu"));
        settings.add(MenuNode.action("退出", icon("logout"), () -> this.minecraft.setScreen(new PauseScreen(true))).shared("logout"));
        List<MenuNode> roots = new ArrayList<>(List.of(profile, social, message, navigation, settings));
        for (RegisteredRootAction rootAction : registeredRootActions()) {
            roots.add(MenuNode.action(rootAction.title(), rootAction.icon(), () -> rootAction.action().accept(this)));
        }
        return roots;
    }

    private void addCategoryIfNotEmpty(MenuNode parent, MenuNode child) {
        if ((child.filter != null && hasItemRows(child.filter)) || !child.children.isEmpty()) {
            parent.add(child);
        }
    }

    private boolean hasItemRows(StackFilter filter) {
        return itemRows(filter).stream().anyMatch(row -> row.itemRow);
    }

    private void addOptionCoreCategories(MenuNode options) {
        MenuNode ui = MenuNode.texture("界面", icon("option")).shared("interface");
        ui.add(MenuNode.action("Minecraft 设置", icon("option"), () -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))).shared("minecraft_options"));
        ui.add(MenuNode.action("战斗槽按键", icon("settings"), () -> this.minecraft.setScreen(new KeyBindsScreen(this, this.minecraft.options))).shared("keybinds"));
        ui.add(MenuNode.action("共享菜单: " + (BetterDisplayConfig.shareUiState ? "开" : "关"), icon("confirm"), this::toggleShareUiState).shared("share_ui_state"));
        ui.add(MenuNode.action("关闭距离: " + BetterDisplayConfig.closeDistanceBlocks, icon("navigation"), () -> openNotice("关闭距离", List.of("当前: " + BetterDisplayConfig.closeDistanceBlocks + " 格", "使用 + / - 调整"))).shared("close_distance"));
        ui.add(MenuNode.action("关闭距离 +1", icon("navigation"), () -> changeCloseDistance(1)).shared("close_distance_up"));
        ui.add(MenuNode.action("关闭距离 -1", icon("navigation"), () -> changeCloseDistance(-1)).shared("close_distance_down"));
        ui.add(MenuNode.disabled("默认死亡画面", EMPTY_ICON).shared("default_death_screen"));
        ui.add(MenuNode.disabled("GUI 暂停", EMPTY_ICON).shared("pause_gui"));
        ui.add(MenuNode.disabled("界面移动", icon("sprinting")).shared("move_gui"));
        options.add(ui);
        options.add(optionCategory("theme", "主题", icon("settings"), List.of("默认 UI", "Hex2", "GGO", "SAO"),
            List.of("default_ui", "hex2", "ggo", "sao")));
        MenuNode entities = optionCategory("entity", "实体", icon("profile"), List.of("实体生命条", "实体水晶"),
            List.of("entity_health_bar", "entity_crystal"));
        entities.add(optionCategory("entity_health_bar_options", "生命条", icon("profile"),
            List.of("友好", "敌对", "杀手", "Boss", "创造", "OP", "无效"),
            List.of("friendly", "hostile", "killer", "boss", "creative", "op", "invalid")));
        options.add(entities);
        options.add(optionCategory("health", "生命", icon("profile"), List.of("平滑生命", "轻量 HUD", "吸收值位置", "屏幕敌人血条", "隐藏离线队伍"),
            List.of("smooth_health", "lightweight_hud", "absorption_position", "screen_enemy_health", "hide_offline_party")));
        options.add(optionCategory("hotbar", "快捷栏", icon("items"), List.of("原版快捷栏", "横向快捷栏", "纵向快捷栏"),
            List.of("vanilla_hotbar", "horizontal_hotbar", "vertical_hotbar")));
        options.add(optionCategory("effects", "特效", icon("skills"), List.of("旋转水晶", "粒子", "声音", "鼠标悬停"),
            List.of("rotating_crystal", "particles", "sound", "mouse_hover")));
        MenuNode more = MenuNode.texture("更多", icon("help")).shared("more");
        more.add(optionCategory("misc_options", "杂项", icon("help"), List.of("仇恨系统", "坐骑状态", "自定义字体", "文本阴影"),
            List.of("aggro", "mount_status", "custom_font", "text_shadow")));
        MenuNode debug = optionCategory("debug", "调试", icon("settings"), List.of("渲染系统"), List.of("rendering_summary"));
        debug.add(optionCategory("rendering", "渲染系统", icon("settings"), List.of("准星", "护甲", "快捷栏", "氧气", "药水图标", "生命", "饥饿"),
            List.of("crosshair", "armor", "hotbar", "air", "potion_icons", "health", "hunger")));
        more.add(debug);
        options.add(more);
    }

    private MenuNode optionCategory(String id, String title, Identifier icon, List<String> labels, List<String> ids) {
        MenuNode node = MenuNode.texture(title, icon).shared(id);
        for (int i = 0; i < labels.size(); i++) {
            node.add(MenuNode.disabled(labels.get(i), EMPTY_ICON).shared(ids.get(i)));
        }
        return node;
    }

    private void toggleShareUiState() {
        BetterDisplayConfig.toggleShareUiState();
        refreshRoots();
        openNotice("共享菜单", List.of("当前: " + (BetterDisplayConfig.shareUiState ? "开" : "关")));
    }

    private void changeCloseDistance(int delta) {
        BetterDisplayConfig.setCloseDistanceBlocks(BetterDisplayConfig.closeDistanceBlocks + delta);
        refreshRoots();
    }

    private void refreshRoots() {
        this.roots = buildRoots();
        closeMenu();
        this.focusedRootIndex = wrapIndex(this.focusedRootIndex, this.roots.size());
    }

    private static List<RegisteredFilter> registeredFilters() {
        synchronized (REGISTERED_ITEM_FILTERS) {
            return List.copyOf(REGISTERED_ITEM_FILTERS);
        }
    }

    private static List<RegisteredRootAction> registeredRootActions() {
        synchronized (REGISTERED_ROOT_ACTIONS) {
            return List.copyOf(REGISTERED_ROOT_ACTIONS);
        }
    }

    private boolean isArmorFor(ItemStack stack, EquipmentSlot slot) {
        return this.player.getEquipmentSlotForItem(stack) == slot;
    }

    private boolean isArmor(ItemStack stack) {
        return stack.has(DataComponents.EQUIPPABLE) && this.player.getEquipmentSlotForItem(stack).getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    private boolean isWeapon(ItemStack stack) {
        return isSword(stack) || isBow(stack) || stack.is(Items.TRIDENT);
    }

    private boolean isBow(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof ProjectileWeaponItem;
    }

    private boolean isStrictTool(ItemStack stack) {
        Item item = stack.getItem();
        return isPickaxe(stack) || item instanceof AxeItem || item instanceof ShovelItem;
    }

    private boolean isConsumable(ItemStack stack) {
        if (stack.has(DataComponents.FOOD)) {
            return true;
        }
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.MILK_BUCKET) || stack.is(Items.HONEY_BOTTLE);
    }

    private boolean isSword(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD;
    }

    private boolean isPickaxe(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE || item == Items.IRON_PICKAXE || item == Items.GOLDEN_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE;
    }

    private boolean isMisc(ItemStack stack) {
        return !isWeapon(stack) && !isStrictTool(stack) && !isArmor(stack) && !isConsumable(stack) && !(stack.getItem() instanceof BlockItem);
    }

    private void openProfilePopup() {
        openNotice("角色资料", profileStats());
    }

    private List<String> profileStats() {
        return List.of(
            "等级 " + this.player.experienceLevel,
            "经验 " + Math.round(clamp(this.player.experienceProgress) * 100.0F) + "%",
            "生命 " + format(this.player.getHealth()) + "/" + format(this.player.getMaxHealth()),
            "主手攻击 " + format(BetterDisplayCombat.weaponAttackDamage(this.player, profileHand(true))),
            "副手攻击 " + format(BetterDisplayCombat.weaponAttackDamage(this.player, profileHand(false))),
            "移速属性 " + String.format(Locale.ROOT, "%.3f", this.player.getAttributeValue(Attributes.MOVEMENT_SPEED)),
            "防御 " + format(this.player.getAttributeValue(Attributes.ARMOR)),
            "韧性 " + format(this.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS))
        );
    }

    private static EquipmentSlot armorSlot(int menuSlot) {
        int index = menuSlot - InventoryMenu.ARMOR_SLOT_START;
        return index >= 0 && index < PROFILE_ARMOR.size() ? PROFILE_ARMOR.get(index) : null;
    }

    private static String armorSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头部";
            case CHEST -> "胸部";
            case LEGS -> "腿部";
            case FEET -> "脚部";
            default -> throw new IllegalArgumentException("Not a player armor slot: " + slot);
        };
    }

    private static String handSlotName(BetterDisplayCombat.HandSlot slot) {
        return slot == BetterDisplayCombat.HandSlot.RIGHT ? "主手" : "副手";
    }

    /** 行标题右侧的短标记：护甲显示「已装备」，武器/工具显示主手/副手。 */
    private String badgeFor(MenuNode row) {
        if (armorSlot(row.menuSlot) != null) {
            return "已装备";
        }
        return row.virtualHandSlot != null
            ? handSlotName(row.virtualHandSlot)
            : slotHandMarker(row.menuSlot, row.stack);
    }

    private String equippedStatus(MenuNode row) {
        EquipmentSlot armor = armorSlot(row.menuSlot);
        if (armor != null) {
            return "已装备" + " · " + armorSlotName(armor);
        }
        if (row.virtualHandSlot != null) {
            return "已装备" + " · " + handSlotName(row.virtualHandSlot);
        }
        String hand = slotHandMarker(row.menuSlot, row.stack);
        return hand.isEmpty() ? "" : "已装备" + " · " + hand;
    }

    /**
     * 按原版槽位判断主手/副手。
     *
     * <p>选中快捷栏槽就是主手，副手槽就是副手；拔刀时战斗槽的那把武器
     * 会占住这两个槽位，所以同样能标对。其余槽位不标手。
     */
    private String slotHandMarker(int menuSlot, ItemStack stack) {
        if (stack == null || stack.isEmpty() || isArmor(stack) || menuSlot < 0) {
            return "";
        }
        if (menuSlot == InventoryMenu.USE_ROW_SLOT_START + this.player.getInventory().getSelectedSlot()) {
            return "主手";
        }
        return menuSlot == InventoryMenu.SHIELD_SLOT ? "副手" : "";
    }

    /**
     * 按物品本身判断主手/副手，用于面板装备格等没有槽位信息的场合。
     *
     * <p>护甲只可能是「已装备」，不标手；武器和工具按物品反查，
     * 这样背包里和快捷栏里的同一把剑都会标上「主手」。
     */
    private String heldMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || isArmor(stack)) {
            return "";
        }
        if (sameItem(this.player.getMainHandItem(), stack)) {
            return "主手";
        }
        if (sameItem(this.player.getOffhandItem(), stack)) {
            return "副手";
        }
        // 战斗槽里存着的武器即使还没拔刀，也算已装备。
        if (sameItem(handStack(BetterDisplayCombat.HandSlot.RIGHT), stack)) {
            return "主手";
        }
        return sameItem(handStack(BetterDisplayCombat.HandSlot.LEFT), stack) ? "副手" : "";
    }

    /** 同一件物品：id 与组件一致即可，数量不参与比较。 */
    private static boolean sameItem(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(first, second);
    }
    private boolean handleProfileSlotClick(double mouseX, double mouseY, int panelX, int panelY) {
        for (int i = 0; i < PROFILE_ARMOR.size(); i++) {
            ItemStack stack = this.player.getItemBySlot(PROFILE_ARMOR.get(i));
            if (!stack.isEmpty() && inside(mouseX, mouseY, panelX + sx(24), panelY + sy(57 + 22 * i), SLOT_SIZE, SLOT_SIZE)) {
                openItemPopup(MenuNode.itemRow(stack, InventoryMenu.ARMOR_SLOT_START + i));
                return true;
            }
        }
        return handleVirtualHandSlotClick(mouseX, mouseY, panelX + sx(112), panelY + sy(72), BetterDisplayCombat.HandSlot.RIGHT)
            || handleVirtualHandSlotClick(mouseX, mouseY, panelX + sx(112), panelY + sy(100), BetterDisplayCombat.HandSlot.LEFT);
    }

    private boolean handleVirtualHandSlotClick(double mouseX, double mouseY, int x, int y, BetterDisplayCombat.HandSlot handSlot) {
        ItemStack stack = handStack(handSlot);
        if (!stack.isEmpty() && inside(mouseX, mouseY, x, y, SLOT_SIZE, SLOT_SIZE)) {
            BetterDisplayCombat.requestUnequip(handSlot);
            return true;
        }
        return false;
    }

    private void openItemPopup(MenuNode row) {
        ItemStack stack = row.stack.copy();
        if (row.virtualHandSlot != null) {
            openVirtualHandPopup(row.virtualHandSlot, stack);
            return;
        }
        if (row.toolSkillSlot >= 0) {
            openToolSkillPopup(row.toolSkillSlot, stack);
            return;
        }
        List<String> lines = tooltipLines(stack);
        if (lines.isEmpty()) {
            lines = List.of(stack.getHoverName().getString());
        }
        List<PopupButton> buttons = new ArrayList<>();
        boolean weaponOrTool = isWeapon(stack) || isStrictTool(stack);
        boolean inHotbar = isHotbarMenuSlot(row.menuSlot);
        ItemStack swordIcon = new ItemStack(Items.IRON_SWORD);
        ItemStack bundleIcon = new ItemStack(Items.BUNDLE);
        EquipmentSlot wornArmor = armorSlot(row.menuSlot);
        if (wornArmor != null || isArmor(stack)) {
            boolean equipped = wornArmor != null;
            if (equipped) {
                lines = new ArrayList<>(lines);
                lines.add(0, "已装备" + "· " + armorSlotName(wornArmor));
            }
            buttons.add(new PopupButton(equipped ? "卸下" : "装备", EMPTY_ICON, new ItemStack(Items.IRON_CHESTPLATE), true, () -> {
                quickMove(row.menuSlot);
                closePopup();
            }).withEquipSound());
            buttons.add(new PopupButton("丢弃", TRASH_ICON, true, () -> openDropConfirm(row.menuSlot, stack)));
            buttons.add(new PopupButton("取消", icon("cancel"), true, this::closePopup));
        } else if (weaponOrTool) {
            buttons.add(new PopupButton("装备主手", EMPTY_ICON, swordIcon, true, () -> {
                BetterDisplayCombat.requestEquip(row.menuSlot, BetterDisplayCombat.HandSlot.RIGHT);
                closePopup();
            }).withEquipSound());
            buttons.add(new PopupButton("装备副手", EMPTY_ICON, swordIcon, true, () -> {
                BetterDisplayCombat.requestEquip(row.menuSlot, BetterDisplayCombat.HandSlot.LEFT);
                closePopup();
            }).withEquipSound());
            buttons.add(new PopupButton("工具技能栏装备", icon("skills"), true, () -> openToolSkillSelection(row.menuSlot, stack)));
            buttons.add(new PopupButton("丢弃", TRASH_ICON, true, () -> openDropConfirm(row.menuSlot, stack)));
            if (inHotbar) {
                buttons.add(new PopupButton("放入背包", EMPTY_ICON, bundleIcon, true, () -> {
                    BetterDisplayCombat.requestMoveHotbarToBackpack(row.menuSlot);
                    closePopup();
                }));
            } else {
                buttons.add(new PopupButton("装备快捷栏", icon("items"), true, () -> openHotbarSelection(row.menuSlot, stack)));
            }
            buttons.add(new PopupButton("取消", icon("cancel"), true, this::closePopup));
        } else {
            if (inHotbar) {
                buttons.add(new PopupButton("放入背包", EMPTY_ICON, bundleIcon, true, () -> {
                    BetterDisplayCombat.requestMoveHotbarToBackpack(row.menuSlot);
                    closePopup();
                }));
            } else {
                buttons.add(new PopupButton("装备快捷栏", icon("items"), true, () -> openHotbarSelection(row.menuSlot, stack)));
            }
            buttons.add(new PopupButton("丢弃", TRASH_ICON, true, () -> openDropConfirm(row.menuSlot, stack)));
            buttons.add(new PopupButton("取消", icon("cancel"), true, this::closePopup));
        }
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), lines, "", buttons);
    }

    private static boolean isHotbarMenuSlot(int menuSlot) {
        return menuSlot >= 36 && menuSlot < 45;
    }

    private void openToolSkillPopup(int toolSkillSlot, ItemStack stack) {
        List<String> lines = tooltipLines(stack);
        if (lines.isEmpty()) {
            lines = List.of(stack.getHoverName().getString());
        }
        String slotName = "工具技能栏位" + (toolSkillSlot + 1);
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), lines, "", List.of(
            new PopupButton("卸下" + slotName, icon("skills"), true, () -> {
                BetterDisplayCombat.requestUnequipToolSkill(toolSkillSlot);
                closePopup();
            }).withEquipSound(),
            new PopupButton("取消", EMPTY_ICON, this::closePopup)
        ));
    }

    private void openVirtualHandPopup(BetterDisplayCombat.HandSlot handSlot, ItemStack stack) {
        List<String> lines = tooltipLines(stack);
        if (lines.isEmpty()) {
            lines = List.of(stack.getHoverName().getString());
        }
        lines = new ArrayList<>(lines);
        lines.add(0, "已装备" + "· " + handSlotName(handSlot));
        String handName = handSlotName(handSlot);
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), lines, "", List.of(
            new PopupButton("卸下" + handName, icon("equipment"), true, () -> {
                BetterDisplayCombat.requestUnequip(handSlot);
                closePopup();
            }).withEquipSound(),
            new PopupButton("取消", EMPTY_ICON, this::closePopup)
        ));
    }

    private void openToolSkillSelection(int menuSlot, ItemStack stack) {
        List<PopupButton> buttons = new ArrayList<>();
        for (int i = 0; i < BetterDisplayCombat.TOOL_SKILL_SLOTS; i++) {
            int toolSkillSlot = i;
            ItemStack iconStack = BetterDisplayCombat.clientToolSkillSlot(i);
            buttons.add(new PopupButton("工具技能栏位" + (i + 1), EMPTY_ICON, iconStack, () -> {
                BetterDisplayCombat.requestEquipToolSkill(menuSlot, toolSkillSlot);
                closePopup();
            }).withEquipSound());
        }
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), List.of("选择工具技能栏位"), "", buttons);
    }

    private void openHotbarSelection(int menuSlot, ItemStack stack) {
        List<PopupButton> buttons = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int hotbarIndex = i;
            int hotbarSlot = 36 + i;
            ItemStack iconStack = hotbarSlot < this.player.inventoryMenu.slots.size() ? this.player.inventoryMenu.slots.get(hotbarSlot).getItem().copy() : ItemStack.EMPTY;
            buttons.add(new PopupButton("栏位 " + (i + 1), EMPTY_ICON, iconStack, () -> {
                swapToHotbar(menuSlot, hotbarIndex);
                closePopup();
            }));
        }
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), List.of("选择栏位"), "", buttons);
    }

    private void openDropConfirm(int menuSlot, ItemStack stack) {
        this.popup = new BetterDisplayPopup(stack.getHoverName().getString(), List.of("确定要丢弃这个物品吗？"), "", List.of(
            new PopupButton("确认", icon("confirm"), true, () -> {
                throwStack(menuSlot);
                closePopup();
            }),
            new PopupButton("取消", icon("cancel"), true, this::closePopup)
        ));
    }

    public void showStashConfirm() {
        int selected = this.player.getInventory().getSelectedSlot();
        if (selected < 0 || selected >= 9) {
            return;
        }
        int menuSlot = 36 + selected;
        ItemStack stack = this.player.getInventory().getItem(selected).copy();
        if (stack.isEmpty()) {
            openNotice("收回背包", List.of("当前快捷栏为空"));
            return;
        }
        String name = stack.getHoverName().getString();
        this.popup = new BetterDisplayPopup(name, List.of("确定将 " + name +  " 收回背包？"), "", List.of(
            new PopupButton("确认", icon("confirm"), true, () -> {
                BetterDisplayCombat.requestMoveHotbarToBackpack(menuSlot);
                closePopup();
            }),
            new PopupButton("取消", icon("cancel"), true, this::closePopup)
        ));
    }

    private void openLeavePartyPopup() {
        this.popup = new BetterDisplayPopup("离开队伍", List.of("确定要离开当前队伍吗？", "需要 SAOMCLib 服务端支持"), "", List.of(
            new PopupButton("确认", icon("confirm"), () -> openNotice("离开队伍", List.of("未连接 SAOMCLib 服务端"))),
            new PopupButton("取消", EMPTY_ICON, this::closePopup)
        ));
    }

    private void openInvitePlayerPopup() {
        openPlayerSelectionPopup("邀请玩家", "邀请", "邀请已选择玩家需要 SAOMCLib 服务端支持");
    }

    private void openAddFriendPopup() {
        openPlayerSelectionPopup("添加好友", "添加", "添加好友需要 SAOMCLib 服务端支持");
    }

    private void openPlayerList() {
        this.showingPlayerList = true;
        this.minecraft.gui.getTabList().setVisible(true);
    }

    private void closePlayerList() {
        if (this.showingPlayerList) {
            this.showingPlayerList = false;
            this.minecraft.gui.getTabList().setVisible(false);
        }
    }

    private void renderPlayerList(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, this.width, this.height, 0x77000000);
        graphics.nextStratum();
        if (this.minecraft.level != null && this.minecraft.getConnection() != null) {
            var scoreboard = this.minecraft.level.getScoreboard();
            this.minecraft.gui.getTabList().render(graphics, this.width, scoreboard, scoreboard.getDisplayObjective(DisplaySlot.LIST));
        }
        renderCloseButton(graphics, mouseX, mouseY, 0, 0, this.width, 1.0F);
    }

    private void openPlayerSelectionPopup(String title, String action, String result) {
        List<PopupButton> buttons = new ArrayList<>();
        if (this.minecraft.getConnection() != null) {
            for (PlayerInfo info : this.minecraft.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!name.equals(this.player.getName().getString()) && buttons.size() < 9) {
                    buttons.add(new PopupButton(name, icon("profile"), () -> openNotice(action + ": " + name, List.of(result))));
                }
            }
        }
        if (buttons.isEmpty()) {
            openNotice(title, List.of("没有可选择的在线玩家"));
            return;
        }
        this.popup = new BetterDisplayPopup(title, List.of("选择在线玩家"), "", buttons);
    }

    private void openNotice(String title, List<String> lines) {
        this.popup = new BetterDisplayPopup(title, lines, "", List.of(new PopupButton("确认", icon("confirm"), this::closePopup)));
    }

    private void closePopup() {
        if (this.popup != null) {
            this.popup.close();
        }
    }

    private void renderCloseButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width, float visibility) {
        if (visibility < 0.05F) {
            return;
        }
        int cx = x + width - POPUP_CLOSE_BUTTON_SIZE - 4;
        int cy = y + 4;
        boolean hovered = inside(mouseX, mouseY, cx, cy, POPUP_CLOSE_BUTTON_SIZE, POPUP_CLOSE_BUTTON_SIZE);
        int bg = hovered ? alpha(0xFF5555, visibility) : alpha(0xFFFFFF, visibility * 0.55F);
        graphics.fill(cx, cy, cx + POPUP_CLOSE_BUTTON_SIZE, cy + POPUP_CLOSE_BUTTON_SIZE, bg);
        drawWhiteBorder(graphics, cx, cy, POPUP_CLOSE_BUTTON_SIZE, POPUP_CLOSE_BUTTON_SIZE, visibility, false);
        graphics.blit(RenderPipelines.GUI_TEXTURED, EMPTY_ICON, cx, cy, 0.0F, 0.0F, POPUP_CLOSE_BUTTON_SIZE, POPUP_CLOSE_BUTTON_SIZE, 16, 16, 16, 16, alpha(0xFFFFFF, visibility));
        if (hovered) {
            graphics.setTooltipForNextFrame(this.font, Component.literal("关闭"), mouseX, mouseY);
        }
    }

    private static boolean hitCloseButton(double mouseX, double mouseY, int x, int y, int width) {
        int cx = x + width - POPUP_CLOSE_BUTTON_SIZE - 4;
        int cy = y + 4;
        return inside(mouseX, mouseY, cx, cy, POPUP_CLOSE_BUTTON_SIZE, POPUP_CLOSE_BUTTON_SIZE);
    }

    private List<String> tooltipLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.EMPTY, this.player, TooltipFlag.NORMAL);
        for (int i = 0; i < tooltip.size() && i < 6; i++) {
            String line = tooltip.get(i).getString();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private void quickMove(int menuSlot) {
        if (this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryMouseClick(this.player.inventoryMenu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, this.player);
        }
    }

    private void throwStack(int menuSlot) {
        if (this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryMouseClick(this.player.inventoryMenu.containerId, menuSlot, 1, ClickType.THROW, this.player);
        }
    }

    private void swapToHotbar(int menuSlot, int hotbarIndex) {
        if (this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryMouseClick(this.player.inventoryMenu.containerId, menuSlot, hotbarIndex, ClickType.SWAP, this.player);
        }
    }

    private void drawRow(GuiGraphics graphics, MenuNode node, int x, int y, boolean selected, float alpha) {
        int bg = node.disabled
            ? alpha(0xAAAAAA, alpha * 0.55F)
            : selected ? alpha(0xF4BD52, alpha) : alpha(0xFFFFFF, alpha * 0.35F);
        graphics.fill(x, y, x + ROW_WIDTH, y + ROW_HEIGHT, bg);
        graphics.fill(x + 1, y + ROW_HEIGHT / 2 - 1, x + ROW_WIDTH - 1, y + ROW_HEIGHT / 2, alpha(0xBBBBBB, alpha * 0.35F));
        graphics.fill(x + 1, y + ROW_HEIGHT / 2, x + ROW_WIDTH - 1, y + ROW_HEIGHT / 2 + 1, alpha(0xDDDDDD, alpha * 0.22F));
        drawWhiteBorder(graphics, x, y, ROW_WIDTH, ROW_HEIGHT, alpha, node.disabled);
        drawNodeIcon(graphics, node, x + 1, y + 1, alpha);
        int textColor = node.disabled
            ? alpha(0x555555, Math.max(alpha, 0.95F))
            : selected ? alpha(0x000000, Math.max(alpha, 0.98F)) : alpha(0x202020, Math.max(alpha, 0.98F));
        String badge = armorSlot(node.menuSlot) == null ? badgeFor(node) : "已装备";
        if (badge.isEmpty()) {
            graphics.drawString(this.font, fit(node.displayTitle(), 56), x + 22, y + 5, textColor, false);
            drawRowIndicator(graphics, x + ROW_WIDTH - ICON_SIZE - 2, y - 1, node, selected, alpha);
        } else {
            int right = x + ROW_WIDTH - 3;
            int badgeX = right - this.font.width(badge);
            graphics.fill(badgeX - 1, y + 2, right + 1, y + ROW_HEIGHT - 2, alpha(0xFFF2CD, alpha));
            graphics.drawString(this.font, fit(node.displayTitle(), badgeX - x - 24), x + 22, y + 5, textColor, false);
            graphics.drawString(this.font, badge, badgeX, y + 5, textColor, false);
        }
    }

    private void drawRowIndicator(GuiGraphics graphics, int x, int y, MenuNode node, boolean selected, float alpha) {
        int bg = node.disabled
            ? alpha(0xAAAAAA, alpha * 0.55F)
            : selected ? alpha(0xFFFFFF, alpha * 0.92F) : alpha(0xFFFFFF, alpha * 0.42F);
        graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, bg);
        drawWhiteBorder(graphics, x, y, ICON_SIZE, ICON_SIZE, alpha, node.disabled);
    }

    private void drawIconButton(GuiGraphics graphics, MenuNode node, int x, int y, boolean selected, float alpha) {
        int bg = node.disabled
            ? alpha(0xAAAAAA, alpha * 0.55F)
            : selected ? alpha(0xF4BD52, alpha) : alpha(0xFFFFFF, alpha * 0.42F);
        graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, bg);
        drawWhiteBorder(graphics, x, y, ICON_SIZE, ICON_SIZE, alpha, node.disabled);
        drawNodeIcon(graphics, node, x + 2, y + 2, alpha);
    }

    private void drawWhiteBorder(GuiGraphics graphics, int x, int y, int w, int h, float alpha, boolean disabled) {
        int color = disabled ? alpha(0x888888, alpha * 0.85F) : alpha(0xFFFFFF, Math.max(alpha, 0.92F));
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private void drawNodeIcon(GuiGraphics graphics, MenuNode node, int x, int y, float alpha) {
        if (!node.itemIcon.isEmpty()) {
            graphics.renderFakeItem(node.itemIcon, x, y);
        } else {
            graphics.blit(RenderPipelines.GUI_TEXTURED, node.textureIcon, x, y, 0.0F, 0.0F, 16, 16, 16, 16, alpha(0xFFFFFF, alpha));
        }
    }

    private void renderItemTooltip(GuiGraphics graphics, ItemStack stack, String status, int mouseX, int mouseY) {
        if (status.isEmpty()) {
            graphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
            return;
        }
        List<Component> tooltip = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, stack));
        tooltip.add(Component.literal(status));
        graphics.setTooltipForNextFrame(this.font, tooltip, stack.getTooltipImage(), mouseX, mouseY);
    }

    private void toggleCombatSlots() {
        BetterDisplayClient.requestToggleHandSlotsFromScreen();
        refreshRoots();
    }

    private float openProgress() {
        return easeInOutQuint(clamp((System.currentTimeMillis() - this.openStartedAt) / 420.0F));
    }

    private float levelProgress(int level) {
        return easeInOutQuint(clamp((System.currentTimeMillis() - this.levelStartedAt[level]) / 320.0F));
    }

    private String fit(String text, int width) {
        if (this.font.width(text) <= width) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
    }

    private void drawCenteredString(GuiGraphics graphics, String text, int centerX, int y, int color) {
        graphics.drawString(this.font, text, centerX - this.font.width(text) / 2, y, color, false);
    }

    private static int sx(int value) {
        return Math.round(value * PANEL_WIDTH / (float)PROFILE_SOURCE_WIDTH);
    }

    private static int sy(int value) {
        return Math.round(value * PANEL_HEIGHT / (float)PROFILE_SOURCE_HEIGHT);
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("saoui", "textures/ggo/menu/icons/" + name + ".png");
    }

    private static float easeInQuint(float value) {
        return value * value * value * value * value;
    }

    private static float easeInOutQuint(float value) {
        return value < 0.5F ? 16.0F * value * value * value * value * value : 1.0F - (float)Math.pow(-2.0F * value + 2.0F, 5.0F) / 2.0F;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int alpha(int rgb, float alpha) {
        return ((int)(clamp(alpha) * 255.0F) << 24) | rgb;
    }

    private static int wrapIndex(int index, int size) {
        return size <= 0 ? 0 : Math.floorMod(index, size);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    private interface StackFilter {
        boolean accept(ItemStack stack);
    }

    private interface MenuAction {
        void run();
    }

    private record RegisteredFilter(String title, Identifier icon, Predicate<ItemStack> filter) {
    }

    private record RegisteredRootAction(String title, Identifier icon, Consumer<BetterDisplayInventoryScreen> action) {
    }

    private static final class MenuNode {
        private final String title;
        private final Identifier textureIcon;
        private final ItemStack itemIcon;
        private final List<MenuNode> children = new ArrayList<>();
        private final StackFilter filter;
        private final MenuAction action;
        private final boolean disabled;
        private final boolean itemRow;
        private final ItemStack stack;
        private final int menuSlot;
        private final BetterDisplayCombat.HandSlot virtualHandSlot;
        private final int toolSkillSlot;
        private String shareId;
        /** 由 equippedStatus 算出的装备标记，画行标题时前缀显示。 */
        private String equipTag = "";

        private MenuNode(String title, Identifier textureIcon, ItemStack itemIcon, StackFilter filter, MenuAction action, boolean disabled, boolean itemRow, ItemStack stack, int menuSlot, BetterDisplayCombat.HandSlot virtualHandSlot, int toolSkillSlot) {
            this.title = title;
            this.textureIcon = textureIcon;
            this.itemIcon = itemIcon;
            this.filter = filter;
            this.action = action;
            this.disabled = disabled;
            this.itemRow = itemRow;
            this.stack = stack;
            this.menuSlot = menuSlot;
            this.virtualHandSlot = virtualHandSlot;
            this.toolSkillSlot = toolSkillSlot;
        }

        private static MenuNode texture(String title, Identifier icon) {
            return new MenuNode(title, icon, ItemStack.EMPTY, null, null, false, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode item(String title, Item item) {
            return new MenuNode(title, EMPTY_ICON, new ItemStack(item), null, null, false, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode filter(String title, Item item, StackFilter filter) {
            return new MenuNode(title, EMPTY_ICON, new ItemStack(item), filter, null, false, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode filterTexture(String title, Identifier icon, StackFilter filter) {
            return new MenuNode(title, icon, ItemStack.EMPTY, filter, null, false, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode action(String title, Identifier icon, MenuAction action) {
            return new MenuNode(title, icon, ItemStack.EMPTY, null, action, false, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode disabled(String title, Identifier icon) {
            return new MenuNode(title, icon, ItemStack.EMPTY, null, null, true, false, ItemStack.EMPTY, -1, null, -1);
        }

        private static MenuNode itemRow(ItemStack stack, int menuSlot) {
            String title = stack.getCount() > 1 ? stack.getHoverName().getString() + " x" + stack.getCount() : stack.getHoverName().getString();
            return new MenuNode(title, EMPTY_ICON, stack.copy(), null, null, false, true, stack.copy(), menuSlot, null, -1);
        }

        /** 行标题：装备标记在前，物品名在后。 */
        private String displayTitle() {
            return equipTag == null || equipTag.isEmpty() ? title : equipTag + " · " + title;
        }

        private static MenuNode toolSkillRow(int toolSkillSlot, ItemStack stack) {
            String itemName = stack.getCount() > 1 ? stack.getHoverName().getString() + " x" + stack.getCount() : stack.getHoverName().getString();
            return new MenuNode("工具技能栏位" + (toolSkillSlot + 1) + ": " + itemName, EMPTY_ICON, stack.copy(), null, null, false, true, stack.copy(), -1, null, toolSkillSlot);
        }

        private void add(MenuNode child) {
            this.children.add(child);
        }

        private MenuNode shared(String id) {
            this.shareId = id;
            return this;
        }
    }

    private final class BetterDisplayPopup {
        private final String title;
        private final List<String> lines;
        private final String footer;
        private final List<PopupButton> buttons;
        private final long startedAt = System.currentTimeMillis();
        private long closingStartedAt = -1L;
        private int offsetX;
        private int offsetY;
        private boolean dragging;

        private BetterDisplayPopup(String title, List<String> lines, String footer, List<PopupButton> buttons) {
            this.title = title;
            this.lines = lines;
            this.footer = footer;
            this.buttons = buttons;
        }

        private void render(GuiGraphics graphics, int mouseX, int mouseY) {
            float progress = popupProgress();
            float closing = closingProgress();
            float visibility = progress * (1.0F - closing);
            int shadowHeight = shadowHeight(progress);
            int textHeight = textHeight(progress);
            int currentHeight = currentHeight(shadowHeight, textHeight);
            int currentWidth = currentWidth(progress, closing);
            int x = BetterDisplayInventoryScreen.this.width / 2 - currentWidth / 2 + this.offsetX;
            int y = BetterDisplayInventoryScreen.this.height / 2 - currentHeight / 2 + this.offsetY;
            int tint = alpha(0xFFFFFF, visibility);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CONFIRM_BG_TEXTURE, x, y, 0.0F, 0.0F, currentWidth, currentHeight, CONFIRM_BG_WIDTH, CONFIRM_BG_HEIGHT, CONFIRM_BG_WIDTH, CONFIRM_BG_HEIGHT, tint);
            int buttonBarY = y + POPUP_TITLE_HEIGHT + shadowHeight + textHeight + shadowHeight;
            if (visibility > 0.03F) {
                BetterDisplayInventoryScreen.this.drawCenteredString(graphics, this.title, BetterDisplayInventoryScreen.this.width / 2 + this.offsetX, y + 17, alpha(0x000000, crispTextAlpha(visibility, closing)));
            }
            if (progress > 0.56F && !this.lines.isEmpty()) {
                int textAlpha = alpha(0x000000, crispTextAlpha(((progress - 0.50F) / 0.50F) * (1.0F - closing), closing));
                int textStartY = y + POPUP_TITLE_HEIGHT + shadowHeight + Math.max(0, (textHeight - this.lines.size() * 10) / 2);
                for (int i = 0; i < this.lines.size(); i++) {
                    BetterDisplayInventoryScreen.this.drawCenteredString(graphics, this.lines.get(i), BetterDisplayInventoryScreen.this.width / 2 + this.offsetX, textStartY + i * 10, textAlpha);
                }
            }
            if (visibility > 0.03F && !this.footer.isEmpty()) {
                BetterDisplayInventoryScreen.this.drawCenteredString(graphics, this.footer, BetterDisplayInventoryScreen.this.width / 2 + this.offsetX, buttonBarY + POPUP_BUTTON_HEIGHT / 2 - 4, alpha(0x000000, crispTextAlpha(visibility, closing)));
            }
            renderButtons(graphics, mouseX, mouseY, x, currentWidth, buttonBarY, progress, closing);
            renderCloseButton(graphics, mouseX, mouseY, x, y, currentWidth, visibility);
        }

        private float crispTextAlpha(float value, float closing) {
            return closing <= 0.0F ? Math.max(value, 0.95F) : value;
        }

        private void renderButtons(GuiGraphics graphics, int mouseX, int mouseY, int x, int currentWidth, int buttonBarY, float progress, float closing) {
            int step = currentWidth / (this.buttons.size() + 1);
            for (int i = 0; i < this.buttons.size(); i++) {
                PopupButton button = this.buttons.get(i);
                float buttonAlpha = clamp((progress - i * 0.12F) / 0.50F) * (1.0F - closing);
                float scale = buttonAlpha < 0.20F ? buttonAlpha * 4.0F + 0.20F : 1.0F;
                int buttonSize = Math.max(4, Math.round(ICON_SIZE * scale));
                int buttonX = x + step * (i + 1) - buttonSize / 2;
                int buttonY = buttonBarY + POPUP_BUTTON_HEIGHT / 2 - buttonSize / 2 - Math.round((1.0F - buttonAlpha) * 12.0F);
                graphics.fill(buttonX, buttonY, buttonX + buttonSize, buttonY + buttonSize, alpha(0xFFFFFF, buttonAlpha * 0.42F));
                drawWhiteBorder(graphics, buttonX, buttonY, buttonSize, buttonSize, buttonAlpha, false);
                int iconSize = Math.max(1, buttonSize - 3);
                int iconOffset = (buttonSize - iconSize) / 2;
                if (!button.itemIcon.isEmpty()) {
                    graphics.renderFakeItem(button.itemIcon, buttonX + iconOffset, buttonY + iconOffset);
                    graphics.renderItemDecorations(BetterDisplayInventoryScreen.this.font, button.itemIcon, buttonX + iconOffset, buttonY + iconOffset);
                } else {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, button.icon, buttonX + iconOffset, buttonY + iconOffset, 0.0F, 0.0F, iconSize, iconSize, 16, 16, 16, 16, alpha(0xFFFFFF, buttonAlpha));
                }
                if (inside(mouseX, mouseY, buttonX, buttonY, buttonSize, buttonHitHeight(button, buttonSize))) {
                    graphics.fill(buttonX, buttonY, buttonX + buttonSize, buttonY + buttonSize, alpha(0xFFFFFF, buttonAlpha * 0.18F));
                    graphics.setTooltipForNextFrame(BetterDisplayInventoryScreen.this.font, Component.literal(button.label), mouseX, mouseY);
                }
                if (button.visibleLabel && buttonAlpha > 0.60F) {
                    BetterDisplayInventoryScreen.this.drawCenteredString(graphics, button.label, buttonX + buttonSize / 2, buttonY + buttonSize + 3, alpha(0x000000, crispTextAlpha(buttonAlpha, closing)));
                }
            }
        }

        private boolean mouseClicked(double mouseX, double mouseY) {
            if (isClosing()) {
                return true;
            }
            float progress = popupProgress();
            float closing = closingProgress();
            int shadowHeight = shadowHeight(progress);
            int textHeight = textHeight(progress);
            int currentHeight = currentHeight(shadowHeight, textHeight);
            int currentWidth = currentWidth(progress, closing);
            int x = BetterDisplayInventoryScreen.this.width / 2 - currentWidth / 2 + this.offsetX;
            int y = BetterDisplayInventoryScreen.this.height / 2 - currentHeight / 2 + this.offsetY;
            if (hitCloseButton(mouseX, mouseY, x, y, currentWidth)) {
                BetterDisplayClientSounds.playMenuSelect();
                BetterDisplayInventoryScreen.this.closePopup();
                return true;
            }
            int buttonBarY = y + POPUP_TITLE_HEIGHT + shadowHeight + textHeight + shadowHeight;
            int step = currentWidth / (this.buttons.size() + 1);
            for (int i = 0; i < this.buttons.size(); i++) {
                PopupButton button = this.buttons.get(i);
                float buttonAlpha = clamp((progress - i * 0.12F) / 0.50F);
                float scale = buttonAlpha < 0.20F ? buttonAlpha * 4.0F + 0.20F : 1.0F;
                int buttonSize = Math.max(4, Math.round(ICON_SIZE * scale));
                int buttonX = x + step * (i + 1) - buttonSize / 2;
                int buttonY = buttonBarY + POPUP_BUTTON_HEIGHT / 2 - buttonSize / 2 - Math.round((1.0F - buttonAlpha) * 12.0F);
                if (inside(mouseX, mouseY, buttonX, buttonY, buttonSize, buttonHitHeight(button, buttonSize))) {
                    playButtonSound(button);
                    button.action.run();
                    return true;
                }
            }
            this.dragging = inside(mouseX, mouseY, x, y, currentWidth, currentHeight);
            return this.dragging;
        }

        private boolean mouseDragged(double dragX, double dragY) {
            if (this.dragging && !isClosing()) {
                this.offsetX += Math.round(dragX);
                this.offsetY += Math.round(dragY);
            }
            return true;
        }

        private void mouseReleased() {
            this.dragging = false;
        }

        private void activateDefault() {
            if (!this.buttons.isEmpty() && !isClosing()) {
                PopupButton button = this.buttons.get(0);
                playButtonSound(button);
                button.action.run();
            }
        }

        private static void playButtonSound(PopupButton button) {
            if (button.equipSound) {
                BetterDisplayClientSounds.playEquip();
            } else {
                BetterDisplayClientSounds.playMenuSelect();
            }
        }

        private void close() {
            if (this.closingStartedAt < 0L) {
                this.closingStartedAt = System.currentTimeMillis();
                this.dragging = false;
            }
        }

        private boolean isClosing() {
            return this.closingStartedAt >= 0L;
        }

        private boolean isFinished() {
            return isClosing() && closingProgress() >= 1.0F;
        }

        private float popupProgress() {
            return easeInQuint(clamp((System.currentTimeMillis() - this.startedAt) / 320.0F));
        }

        private float closingProgress() {
            return this.closingStartedAt < 0L ? 0.0F : clamp((System.currentTimeMillis() - this.closingStartedAt) / 160.0F);
        }

        private int currentWidth(float progress, float closing) {
            float widthScale = progress < 0.20F ? progress * 4.0F + 0.20F : 1.0F;
            return Math.max(1, Math.round(POPUP_WIDTH * widthScale * (1.0F - closing)));
        }

        private int shadowHeight(float progress) {
            return Math.round(POPUP_SHADOW_HEIGHT * (progress > 0.66F ? 1.0F : progress / 0.66F));
        }

        private int textHeight(float progress) {
            return Math.round(this.lines.size() * 10.0F * clamp((progress - 0.55F) / 0.45F));
        }

        private int currentHeight(int shadowHeight, int textHeight) {
            return POPUP_TITLE_HEIGHT + shadowHeight + textHeight + shadowHeight + POPUP_BUTTON_HEIGHT;
        }

        private int buttonHitHeight(PopupButton button, int buttonSize) {
            return button.visibleLabel ? buttonSize + BetterDisplayInventoryScreen.this.font.lineHeight + 5 : buttonSize;
        }
    }

    private static final class PopupButton {
        private final String label;
        private final Identifier icon;
        private final ItemStack itemIcon;
        private final boolean visibleLabel;
        private final MenuAction action;
        private boolean equipSound;

        private PopupButton(String label, Identifier icon, MenuAction action) {
            this(label, icon, ItemStack.EMPTY, false, action);
        }

        private PopupButton(String label, Identifier icon, boolean visibleLabel, MenuAction action) {
            this(label, icon, ItemStack.EMPTY, visibleLabel, action);
        }

        private PopupButton(String label, Identifier icon, ItemStack itemIcon, MenuAction action) {
            this(label, icon, itemIcon, false, action);
        }

        private PopupButton(String label, Identifier icon, ItemStack itemIcon, boolean visibleLabel, MenuAction action) {
            this.label = label;
            this.icon = icon;
            this.itemIcon = itemIcon;
            this.visibleLabel = visibleLabel;
            this.action = action;
        }

        private PopupButton withEquipSound() {
            this.equipSound = true;
            return this;
        }
    }
}

package com.daoyou.betterdisplay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelPartFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * SAO 风格菜单面板的绘制。几何全部沿用 1.19.4 SaoMenuRenderer：
 * 面板 376x142，档案框在 x=-92、宽 83、高 128，快捷栏环中心 (-51,58)、半径 (31,43)、12 格。
 */
public final class SaoMenuRenderer {
    public static final int WIDTH = SaoMenuModel.PANEL_WIDTH;
    public static final int HEIGHT = SaoMenuModel.PANEL_HEIGHT;
    public static final int PROFILE_LEFT = SaoMenuModel.PROFILE_LEFT;

    private static final int RING_CENTER_X = -51;
    private static final int RING_CENTER_Y = 58;
    private static final int WORLD_RING_CENTER_Y = 48;
    private static final int WORLD_RING_RADIUS_Y = 29;
    private static final int RING_RADIUS_X = 31;
    private static final int RING_RADIUS_Y = 43;
    private static final int RING_SLOTS = 12;
    private static final double RING_STEP_DEGREES = 30.0D;
    private static final double RING_HIT_HALF = 10.0D;

    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_TEXT_DIM = 0x555555;
    private static final int COLOR_HIGHLIGHT = 0xC99713;
    private static final int COLOR_UNAVAILABLE = 0x7C7C7C;

    private static final float ROW_OPACITY_DIM = 0.68F;
    private static final float ROW_OPACITY_NORMAL = 0.94F;
    private static final ItemFeatureRenderer WORLD_ITEMS = new ItemFeatureRenderer();
    private static final ModelFeatureRenderer WORLD_ITEM_MODELS = new ModelFeatureRenderer();
    private static final ModelPartFeatureRenderer WORLD_ITEM_PARTS = new ModelPartFeatureRenderer();
    private static final List<EquipmentSlot> PROFILE_ARMOR_SLOTS = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private static final Identifier SAO_ATLAS = Identifier.fromNamespaceAndPath("saoui", "textures/sao/gui.png");
    private static final Identifier SAO_ROW = Identifier.fromNamespaceAndPath("saoui", "themes/sao/textures/slot.png");
    private static final Identifier SAO_PROFILE = Identifier.fromNamespaceAndPath("saoui", "textures/menu/parts/profilebg.png");

    private static final Identifier GUI_TEXTURE = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/gui_hex.png");
    private static final Identifier SLOT_TEXTURE = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/slot.png");
    private static final Identifier PROFILE_BACKGROUND = Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/profilebg.png");

    private static final Map<String, Identifier> ICON_CACHE = new LinkedHashMap<>();
    private static final Map<String, Identifier> WORLD_ICON_CACHE = new LinkedHashMap<>();
    private static final ByteBufferBuilder BUFFER_BUILDER = new ByteBufferBuilder(8192);
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(BUFFER_BUILDER);

    private SaoMenuRenderer() {
    }

    public static void renderInWorld(PoseStack poseStack, Font font, SaoMenuState state, Player target, int light) {
        List<SaoMenuModel.Row> rows = SaoMenuModel.rows(state);
        boolean showsProfile = SaoMenuModel.showsProfile(state) && state.hasProfile();
        Matrix4f matrix = poseStack.last().pose();
        Minecraft minecraft = Minecraft.getInstance();
        SubmitNodeStorage submits = new SubmitNodeStorage();
        try (ByteBufferBuilder builder = new ByteBufferBuilder(8192)) {
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
            if (showsProfile) {
                worldSprite(buffers, matrix, SAO_PROFILE, PROFILE_LEFT, 7, 83, 128, 0.0F,
                    0, 0, 165, 256, 256, tint(COLOR_TEXT, ROW_OPACITY_NORMAL), light);
                renderWorldSlots(buffers, matrix, state, target, light);
            }

            Matrix4f textMatrix = new Matrix4f(matrix).translate(0.0F, 0.0F, 0.3F);
            for (SaoMenuModel.Row row : rows) {
                boolean selected = row.selected();
                float opacity = row.root() && !selected ? ROW_OPACITY_DIM : ROW_OPACITY_NORMAL;
                int rowColor = tint(selected ? COLOR_HIGHLIGHT : COLOR_TEXT, opacity);
                if (row.root()) {
                    worldSprite(buffers, matrix, SAO_ATLAS, row.x(), row.y(), row.width(), row.height(), 0.0F,
                        1, 26, 19, 19, 256, rowColor, light);
                } else {
                    worldSprite(buffers, matrix, SAO_ROW, row.x(), row.y(), row.width(), row.height(), 0.0F,
                        0, 40, 84, 18, 256, rowColor, light);
                }

                int textColor = selected ? COLOR_TEXT : COLOR_TEXT_DIM;
                ItemStack item = rowStack(row);
                String icon = row.entry().icon();
                if (!item.isEmpty()) {
                    submitWorldStack(minecraft, poseStack, submits, item, row.x() + 10, row.y() + 9, 13.0F, light);
                } else if (icon != null && !icon.isBlank()) {
                    worldSprite(buffers, matrix, worldIconLocation(icon), row.x() + 1, row.y() + 1, 16, 16, 0.125F,
                        0, 0, 64, 64, 64, tint(textColor, opacity), light);
                }
                if (!row.root()) {
                    float labelScale = 1.15F;
                    Matrix4f label = new Matrix4f(textMatrix).translate(row.x() + 21, row.y() + 4, 0.0F)
                        .scale(labelScale, labelScale, 1.0F);
                    String title = row.item() != null && !item.isEmpty() ? item.getHoverName().getString() : rowLabel(row, state);
                    int textWidth = (int) ((row.width() - 25) / labelScale);
                    String count = row.item() != null && row.item().count() > 1 ? "×" + row.item().count() : "";
                    int countWidth = font.width(count);
                    worldText(font, buffers, label, title, 0, 0,
                        Math.max(0, textWidth - countWidth - (count.isEmpty() ? 0 : 3)), textColor, light);
                    if (!count.isEmpty()) {
                        worldText(font, buffers, label, count, textWidth - countWidth, 0, countWidth, textColor, light);
                    }
                }
            }
            if (showsProfile) {
                renderWorldProfile(minecraft, poseStack, submits, buffers, font, textMatrix, state, target, light);
            }
            var outlines = minecraft.renderBuffers().outlineBufferSource();
            for (var group : submits.getSubmitsPerOrder().values()) {
                WORLD_ITEMS.render(group, buffers, outlines);
                WORLD_ITEM_MODELS.render(group, buffers, outlines, buffers);
                WORLD_ITEM_PARTS.render(group, buffers, outlines, buffers);
            }
            buffers.endBatch();
        }
    }

    private static ItemStack rowStack(SaoMenuModel.Row row) {
        if (row.item() != null) {
            ItemStack stack = stackFor(row.item().id());
            if (!stack.isEmpty()) {
                stack.setCount(row.item().count());
            }
            return stack;
        }
        String itemIcon = row.entry().itemIcon();
        return itemIcon == null ? ItemStack.EMPTY : stackFor(Identifier.parse(itemIcon));
    }

    private static String rowLabel(SaoMenuModel.Row row, SaoMenuState state) {
        if (row.entry().id().equals("share_ui_state")) {
            return row.entry().label() + ((state.optionMask() & SaoMenuModel.SHARE_UI_STATE_ENABLED) != 0 ? "：开" : "：关");
        }
        return row.entry().label();
    }

    private static void renderWorldSlots(MultiBufferSource buffers, Matrix4f matrix, SaoMenuState state,
                                         Player target, int light) {
        for (int i = 0; i < RING_SLOTS; i++) {
            int slot = hotbarIndex(i);
            boolean selected = slot >= 0 && slot == target.getInventory().getSelectedSlot();
            worldSlot(buffers, matrix, worldRingX(i), worldRingY(i), selected, light);
        }
        // 战斗槽是此版本新增的功能，沿用同一套 SAO 槽位贴图。
        worldSlot(buffers, matrix, RING_CENTER_X - 10, WORLD_RING_CENTER_Y, state.drawn(), light);
        worldSlot(buffers, matrix, RING_CENTER_X + 10, WORLD_RING_CENTER_Y, state.drawn(), light);
    }

    private static void worldSlot(MultiBufferSource buffers, Matrix4f matrix, int x, int y,
                                  boolean selected, int light) {
        worldSprite(buffers, matrix, SAO_ATLAS, x - 10, y - 10, 20, 20, 0.125F,
            0, 25, 20, 20, 256, tint(selected ? COLOR_HIGHLIGHT : COLOR_TEXT, ROW_OPACITY_NORMAL), light);
    }

    private static void worldSprite(MultiBufferSource buffers, Matrix4f matrix, Identifier texture,
                                    float x, float y, float width, float height, float z,
                                    int u, int v, int regionWidth, int regionHeight, int textureSize,
                                    int argb, int light) {
        // Atlas coordinates use the original GUI's logical texture size, including HD resources.
        float u0 = (float) u / textureSize;
        float v0 = (float) v / textureSize;
        float u1 = (float) (u + regionWidth) / textureSize;
        float v1 = (float) (v + regionHeight) / textureSize;
        VertexConsumer consumer = buffers.getBuffer(RenderTypes.text(texture));
        consumer.addVertex(matrix, x, y + height, z).setColor(argb).setUv(u0, v1).setLight(light);
        consumer.addVertex(matrix, x + width, y + height, z).setColor(argb).setUv(u1, v1).setLight(light);
        consumer.addVertex(matrix, x + width, y, z).setColor(argb).setUv(u1, v0).setLight(light);
        consumer.addVertex(matrix, x, y, z).setColor(argb).setUv(u0, v0).setLight(light);
    }

    private static int tint(int rgb, float opacity) {
        return (Math.round(opacity * 255.0F) << 24) | rgb;
    }

    private static void worldText(Font font, MultiBufferSource buffers, Matrix4f matrix, String value,
                                  float x, float y, int width, int color, int light) {
        String text = font.plainSubstrByWidth(value, width);
        font.drawInBatch(text, x, y, 0xFF000000 | color, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
    }

    private static void renderWorldProfile(Minecraft minecraft, PoseStack poseStack, SubmitNodeStorage submits,
                                           MultiBufferSource buffers,
                                           Font font, Matrix4f textMatrix, SaoMenuState state, Player target, int light) {
        SaoMenuProfile profile = state.profile();
        String name = font.plainSubstrByWidth(profile.name() == null ? "" : profile.name(), 63);
        worldText(font, buffers, textMatrix, name, PROFILE_LEFT + (75 - font.width(name)) / 2, 12,
            63, COLOR_TEXT_DIM, light);

        // 左侧资料卡只显示数值：装备与手中物品的信息应出现在右侧“装备”选项里，
        // 不再画在资料卡上。
        renderWorldStats(font, buffers, textMatrix, profile, light);
    }

    private static void renderWorldStats(Font font, MultiBufferSource buffers, Matrix4f matrix,
                                         SaoMenuProfile profile, int light) {
        String[] stats = profileStats(profile);
        for (int row = 0; row < stats.length; row++) {
            Matrix4f line = new Matrix4f(matrix).translate(PROFILE_LEFT + 7, 91 + row * 7, 0.0F)
                .scale(0.85F, 0.78F, 1.0F);
            worldText(font, buffers, line, stats[row], 0, 0, 62, COLOR_TEXT_DIM, light);
        }
    }

    private static void submitWorldItem(Minecraft minecraft, PoseStack poseStack, SubmitNodeStorage submits,
                                        SaoMenuItem item, float x, float y, int light) {
        if (item == null) {
            return;
        }
        ItemStack stack = stackFor(item.id());
        if (stack.isEmpty()) {
            return;
        }
        stack.setCount(item.count());
        submitWorldStack(minecraft, poseStack, submits, stack, x, y, 10.0F, light);
    }

    private static void submitWorldStack(Minecraft minecraft, PoseStack poseStack, SubmitNodeStorage submits,
                                         ItemStack stack, float x, float y, float scale, int light) {
        if (stack.isEmpty()) {
            return;
        }
        // Submitted nodes reference their render state's quad and tint lists until they draw.
        ItemStackRenderState itemState = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(itemState, stack, ItemDisplayContext.GUI,
            minecraft.level, null, 0);
        poseStack.pushPose();
        try {
            poseStack.translate(x, y, 3.0F);
            poseStack.scale(scale, -scale, 5.0F);
            itemState.submit(poseStack, submits, light, OverlayTexture.NO_OVERLAY, 0);
        } finally {
            poseStack.popPose();
        }
    }

    private static int worldRingX(int slot) {
        return (int) (RING_CENTER_X + Math.sin(Math.toRadians(slot * RING_STEP_DEGREES)) * RING_RADIUS_X);
    }

    private static int worldRingY(int slot) {
        return (int) (WORLD_RING_CENTER_Y + Math.cos(Math.toRadians(slot * RING_STEP_DEGREES)) * WORLD_RING_RADIUS_Y);
    }

    public static void render(GuiGraphics graphics, Font font, SaoMenuState state, int light, boolean inWorld,
                              List<ItemStack> localItems, Player player) {
        List<SaoMenuModel.Row> rows = SaoMenuModel.rows(state);
        if (SaoMenuModel.showsProfile(state) && state.profile() != null) {
            drawProfile(graphics, font, state.profile(), player, light, inWorld);
        }
        for (SaoMenuModel.Row row : rows) {
            int textColor = row.selected() ? COLOR_HIGHLIGHT : COLOR_TEXT;
            int iconColor = row.selected() ? COLOR_TEXT : COLOR_TEXT_DIM;
            float opacity = row.root() && !row.selected() ? ROW_OPACITY_DIM : ROW_OPACITY_NORMAL;

            if (row.root()) {
                sprite(graphics, GUI_TEXTURE, row.x(), row.y(), row.width(), row.height(),
                    26, 19, 19, 256, textColor, opacity);
            } else {
                sprite(graphics, SLOT_TEXTURE, row.x(), row.y(), row.width(), row.height(),
                    40, 84, 18, 256, textColor, opacity);
            }

            String iconName = row.entry().icon();
            if (iconName != null && !iconName.isBlank()) {
                Identifier icon = iconLocation(iconName);
                sprite(graphics, icon, row.x() + 1, row.y() + 1, 16, 16, 0, 0, 64, 64, iconColor, opacity);
            }

            ItemStack displayed = localItems != null && !localItems.isEmpty()
                ? localItems.get(Math.floorMod(row.entry().id().hashCode(), localItems.size()))
                : ItemStack.EMPTY;
            if (!displayed.isEmpty()) {
                drawItem(graphics, displayed, row.x() + 9, row.y() + 9, light, 14.0F);
            }

            if (!row.root()) {
                String label = Component.literal(row.entry().label()).getString();
                int reserved = 25;
                String trimmed = font.plainSubstrByWidth(label, Math.max(1, row.width() - reserved));
                drawText(graphics, font, trimmed, row.x() + 21, row.y() + 5, iconColor);
            }
        }
        BUFFERS.endBatch();
    }

    private static void drawProfile(GuiGraphics graphics, Font font, SaoMenuProfile profile, Player player,
                                    int light, boolean inWorld) {
        int left = PROFILE_LEFT;
        int top = 7;
        sprite(graphics, PROFILE_BACKGROUND, left, top, 83, 128, 0, 0, 165, 256, COLOR_TEXT, ROW_OPACITY_NORMAL);

        String name = font.plainSubstrByWidth(profile.name() == null ? "" : profile.name(), 71);
        drawText(graphics, font, name, left + 83 / 2 - font.width(name) / 2, top + 8, COLOR_TEXT_DIM);

        if (!inWorld && player instanceof LivingEntity living) {
            int entityX = RING_CENTER_X;
            int entityY = top + 78;
            drawEntityOnPlayer(graphics, entityX, entityY, player, light);
        }

        int index = 0;
        for (String line : profileStats(profile)) {
            String trimmed = font.plainSubstrByWidth(line, 58);
            drawText(graphics, font, trimmed, left + 3, top + 84 + index * 7, COLOR_TEXT_DIM);
            index++;
        }

        drawHotbarRing(graphics, player, light, inWorld);
    }

    private static String[] profileStats(SaoMenuProfile profile) {
        return new String[]{
            Component.translatable("sao.profile.level", profile.level()).getString(),
            Component.translatable("sao.profile.experience", profile.experiencePct()).getString(),
            Component.translatable("sao.profile.health", profile.health(), profile.maxHealth()).getString(),
            // 攻击力来自手上武器（服务端按战斗槽主手重算），防御力来自护甲属性。
            Component.translatable("sao.profile.attack", formatStat(profile.attackDamage())).getString(),
            Component.translatable("sao.profile.agility",
                String.format(Locale.ROOT, "%.3f", profile.movementSpeed())).getString(),
            Component.translatable("sao.profile.defense", profile.armor()).getString()
        };
    }

    private static String formatStat(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 1.19.4 调用 InventoryScreen.renderEntityInInventory；1.21.11 只剩
     * renderEntityInInventoryFollowsMouse(GuiGraphics,x,y,scale,offsetX,offsetY,angleX,angleY,entity)。
     */
    private static void drawEntityOnPlayer(GuiGraphics graphics, int x, int y, Player player, int light) {
        if (!(player instanceof LivingEntity living)) {
            return;
        }
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x, y, 28, 0, 0, 0.0F, 0.0F, 0.0F, living);
        } catch (RuntimeException ignored) {
            // 远端玩家模型在某些状态下不可渲染，忽略即可，不影响其他元素。
        }
    }

    private static void drawHotbarRing(GuiGraphics graphics, Player player, int light, boolean inWorld) {
        for (int i = 0; i < RING_SLOTS; i++) {
            double radians = Math.toRadians(i * RING_STEP_DEGREES);
            int x = (int) (RING_CENTER_X + Math.sin(radians) * RING_RADIUS_X);
            int y = (int) (RING_CENTER_Y + Math.cos(radians) * RING_RADIUS_Y);
            int hotbarIndex = hotbarIndex(i);
            boolean selected = player != null && hotbarIndex >= 0
                && player.getInventory().getSelectedSlot() == hotbarIndex;
            int color = selected ? COLOR_HIGHLIGHT : COLOR_TEXT;
            sprite(graphics, GUI_TEXTURE, x - 10, y - 10, 20, 20, 25, 20, 20, 256, color, ROW_OPACITY_NORMAL);
            if (player != null && hotbarIndex >= 0) {
                ItemStack stack = player.getInventory().getItem(hotbarIndex);
                if (!stack.isEmpty()) {
                    drawItem(graphics, stack, x, y, light, 10.0F);
                }
            }
        }
    }

    /**
     * 1.19.4 SaoMenuRenderer.ringHotbarAt：把环上位置映射回快捷栏槽位。
     */
    public static Integer ringHotbarAt(double mouseX, double mouseY) {
        for (int i = 0; i < RING_SLOTS; i++) {
            double radians = Math.toRadians(i * RING_STEP_DEGREES);
            double x = RING_CENTER_X + Math.sin(radians) * RING_RADIUS_X;
            double y = RING_CENTER_Y + Math.cos(radians) * RING_RADIUS_Y;
            if (Math.abs(mouseX - x) <= RING_HIT_HALF && Math.abs(mouseY - y) <= RING_HIT_HALF) {
                int index = hotbarIndex(i);
                return index >= 0 ? index : null;
            }
        }
        return null;
    }

    /**
     * 1.19.4 hotbarIndex：i in 0..4 -> i+4，i >= 8 -> i-8，其余为 -1。
     */
    private static int hotbarIndex(int index) {
        if (index >= 0 && index < 5) {
            return index + 4;
        }
        if (index >= 8) {
            return index - 8;
        }
        return -1;
    }

    private static void drawText(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, 0xFF000000 | color, false);
    }

    private static void drawItem(GuiGraphics graphics, ItemStack stack, int x, int y, int light, float scale) {
        float actual = scale <= 0.0F ? 14.0F : scale;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(actual / 16.0F, actual / 16.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popMatrix();
    }

    private static void sprite(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height,
                               int u, int v, int regionSize, int textureSize, int tint, float opacity) {
        int alpha = tint == 0 ? (int) (opacity * 255.0F) : (tint >>> 24);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) u, (float) v,
            width, height, regionSize, regionSize, textureSize, textureSize,
            (tint & 0xFFFFFF) | (alpha << 24));
    }

    private static Identifier iconLocation(String name) {
        return ICON_CACHE.computeIfAbsent(name,
            key -> Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, "textures/gui/icons/" + key + ".png"));
    }

    private static Identifier worldIconLocation(String name) {
        return WORLD_ICON_CACHE.computeIfAbsent(name,
            key -> Identifier.fromNamespaceAndPath("saoui", "textures/sao/menu/icons/" + key + ".png"));
    }

    public static ItemStack stackFor(Identifier id) {
        if (!SaoMenuCodec.isKnownItem(id)) {
            return ItemStack.EMPTY;
        }
        var item = BuiltInRegistries.ITEM.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}

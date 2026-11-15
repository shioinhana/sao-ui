package com.daoyou.betterdisplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 把其他玩家的菜单画在他们打开菜单时的视线方向上。
 */
public final class RemoteUiRenderer {
    /** 1.19.4 MAX_DISTANCE_SQUARED：32 格。 */
    private static final double MAX_DISTANCE_SQUARED = 1024.0D;
    /** 1.19.4 BLOCKS_PER_PIXEL：一个世界方块对应多少像素。 */
    private static final float BLOCKS_PER_PIXEL = 0.016F;
    private static final double SCREEN_MARGIN = 0.97D;
    private static final double PANEL_DISTANCE = 2.0D;
    private static final double MIN_READABLE_DOT = 0.45D;
    /** 面板至少离相机近裁剪面这么远，避免贴脸闪烁。 */
    private static final double MIN_VIEW_DOT = 0.05D;

    private RemoteUiRenderer() {
    }

    public static void renderWorld(PoseStack poseStack, Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer localPlayer = minecraft.player;
        if (level == null || localPlayer == null) {
            return;
        }
        if (minecraft.options.hideGui || !BetterDisplayConfig.showRemoteUi || camera.isDetached()) {
            return;
        }

        Vec3 cameraPos = camera.position();
        Vec3 cameraForward = new Vec3(camera.forwardVector());
        Vec3 cameraRight = new Vec3(camera.leftVector()).scale(-1.0D);
        Vec3 cameraUp = new Vec3(camera.upVector());

        Map<UUID, UiStateSnapshot> snapshots = new java.util.HashMap<>();
        for (UiStateSnapshot snapshot : RemoteUiStateStore.values()) {
            if (snapshot.saoMenu() != null) {
                snapshots.put(snapshot.playerId(), snapshot);
            }
        }
        if (snapshots.isEmpty()) {
            return;
        }

        List<AbstractClientPlayer> candidates = new ArrayList<>();
        for (AbstractClientPlayer player : level.players()) {
            if (player == localPlayer || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (player.getUUID() == null || !snapshots.containsKey(player.getUUID())) {
                continue;
            }
            if (player.distanceToSqr(cameraPos) > MAX_DISTANCE_SQUARED) {
                continue;
            }
            candidates.add(player);
        }
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingDouble(player -> -player.distanceToSqr(cameraPos)));

        ViewProjection view = viewProjection(minecraft.gameRenderer, cameraPos, cameraForward, cameraRight, cameraUp);
        Font font = minecraft.font;
        for (AbstractClientPlayer target : candidates) {
            UiStateSnapshot snapshot = snapshots.get(target.getUUID());
            SaoMenuState state = snapshot == null ? null : snapshot.saoMenu();
            if (state == null) {
                continue;
            }

            Vec3 position = target.getPosition(partialTick);
            Vec3 headAnchor = position.add(0.0D, target.getEyeHeight(), 0.0D);
            Vec3 openingDirection = openingDirection(snapshot.anchorYaw(), snapshot.anchorPitch());
            Vec3 panelAnchor = headAnchor.add(openingDirection.scale(PANEL_DISTANCE));
            BlockHitResult obstruction = level.clip(new ClipContext(
                headAnchor, panelAnchor, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target));
            if (obstruction.getType() != HitResult.Type.MISS) {
                double distance = Math.max(0.05D, headAnchor.distanceTo(obstruction.getLocation()) - 0.05D);
                panelAnchor = headAnchor.add(openingDirection.scale(distance));
            }
            Vec3 headRelative = cameraRelative(headAnchor, cameraPos);
            Vec3 panelRelative = cameraRelative(panelAnchor, cameraPos);

            if (headRelative.dot(cameraForward) <= MIN_VIEW_DOT || panelRelative.dot(cameraForward) <= MIN_VIEW_DOT) {
                continue;
            }
            if (!hasClearView(level, localPlayer, cameraPos, panelAnchor)) {
                continue;
            }
            if (!hasClearView(level, localPlayer, cameraPos, headAnchor)) {
                continue;
            }

            PanelPlacement placement = placePanel(state);
            Quaternionf orientation = panelOrientation(snapshot.anchorYaw(), snapshot.anchorPitch(), panelRelative);
            float scale = fitToScreen(view, panelRelative, orientation, placement);
            if (scale <= 0.0F) {
                continue;
            }
            poseStack.pushPose();
            try {
                poseStack.translate(panelRelative.x, panelRelative.y, panelRelative.z);
                poseStack.mulPose(orientation);
                poseStack.scale(scale, -scale, scale);
                poseStack.translate(-placement.centerX(), -placement.centerY(), 0.0D);
                SaoMenuRenderer.renderInWorld(poseStack, font, state, target, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
    }

    /** 返回以相机为原点的世界坐标。 */
    public static Vec3 cameraRelative(Vec3 position, Vec3 cameraPos) {
        return position.subtract(cameraPos);
    }

    /**
     * 面板在镜头空间里的投影结果，供自测与几何校验使用。
     *
     * @param center          面板中心的镜头空间坐标
     * @param viewDot         目标与视线方向的一致性
     * @param normalDot       面板朝向与视线方向的一致性（≈1 表示正面朝向镜头）
     * @param halfWidthBlocks 面板半宽（世界单位）
     * @param halfHeightBlocks 面板半高（世界单位）
     * @param centerDepth     面板中心的景深
     */
    public record Layout(Vec3 center, double viewDot, double normalDot,
                         double halfWidthBlocks, double halfHeightBlocks, double centerDepth) {

        /**
         * 面板中心是否落在相机的正前方视锥里（归一化后的屏幕坐标在 ±1 内）。
         */
        public boolean centeredInView() {
            if (centerDepth <= 0.01D) {
                return false;
            }
            double halfFovFactor = Math.tan(Math.toRadians(70.0D) / 2.0D);
            double nx = center.x / (centerDepth * halfFovFactor);
            double ny = center.y / (centerDepth * halfFovFactor);
            return Math.abs(nx) <= 1.0D && Math.abs(ny) <= 1.0D;
        }

        /**
         * 面板尺寸是否和玩家距离匹配：世界尺寸应在 0.5~6 格之间。
         */
        public boolean pixelAreaNear(Object player, Object state) {
            return halfWidthBlocks >= 0.1D && halfWidthBlocks <= 3.1D
                && halfHeightBlocks >= 0.05D && halfHeightBlocks <= 3.0D;
        }

        public String describe() {
            return String.format(java.util.Locale.ROOT,
                "center=%.3f,%.3f,%.3f depth=%.3f viewDot=%.3f normalDot=%.3f halfW=%.3f halfH=%.3f",
                center.x, center.y, center.z, centerDepth, viewDot, normalDot, halfWidthBlocks, halfHeightBlocks);
        }
    }

    /**
     * 复算一次面板摆放，但只做数学，不碰图形栈。与 {@link #renderWorld} 使用同一组常量。
     */
    public static Layout layoutFor(Vec3 targetPosition, float eyeHeight, float anchorYaw,
                                   Vec3 cameraPos, SaoMenuState state) {
        return layoutFor(targetPosition, eyeHeight, anchorYaw, 0.0F, cameraPos, state);
    }

    public static Layout layoutFor(Vec3 targetPosition, float eyeHeight, float anchorYaw, float anchorPitch,
                                   Vec3 cameraPos, SaoMenuState state) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 forward = new Vec3(camera.forwardVector());
        Vec3 right = new Vec3(camera.leftVector()).scale(-1.0D);
        Vec3 up = new Vec3(camera.upVector());
        Vec3 panelAnchor = panelAnchor(targetPosition, eyeHeight, anchorYaw, anchorPitch);
        Vec3 headAnchor = targetPosition.add(0.0D, eyeHeight, 0.0D);
        Vec3 panelRelative = cameraRelative(panelAnchor, cameraPos);
        Vec3 headRelative = cameraRelative(headAnchor, cameraPos);
        PanelPlacement placement = placePanel(state);
        Quaternionf orientation = panelOrientation(anchorYaw, anchorPitch, panelRelative);
        Vec3 panelRight = new Vec3(orientation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
        Vec3 panelUp = new Vec3(orientation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
        double depth = panelRelative.dot(forward);
        float scale = depth > MIN_VIEW_DOT
            ? fitToScreen(viewProjection(minecraft.gameRenderer, cameraPos, forward, right, up),
                panelRelative, orientation, placement)
            : BLOCKS_PER_PIXEL;
        Vec3 contentCenter = panelRelative
            .add(panelRight.scale(((placement.left() + placement.right()) / 2.0D - placement.centerX()) * scale))
            .add(panelUp.scale((placement.centerY() - (placement.top() + placement.bottom()) / 2.0D) * scale));
        Vec3 center = new Vec3(
            contentCenter.dot(right), contentCenter.dot(up), contentCenter.dot(forward));
        Vec3 normal = new Vec3(orientation.transform(new Vector3f(0.0F, 0.0F, 1.0F)));
        return new Layout(center, headRelative.dot(forward), normal.dot(panelRelative.normalize().scale(-1.0D)),
            (placement.right() - placement.left()) / 2.0D * scale,
            (placement.bottom() - placement.top()) / 2.0D * scale, center.z);
    }

    private static PanelPlacement placePanel(SaoMenuState state) {
        int contentLeft = SaoMenuModel.showsProfile(state) && state.profile() != null
            ? SaoMenuModel.PROFILE_LEFT : 0;
        List<SaoMenuModel.Row> rows = SaoMenuModel.rows(state);
        int right = SaoMenuModel.contentRight(rows);
        int bottom = SaoMenuModel.PANEL_HEIGHT;
        int rootBottom = 0;
        for (SaoMenuModel.Row row : rows) {
            bottom = Math.max(bottom, row.y() + row.height());
            if (row.root()) {
                rootBottom = Math.max(rootBottom, row.y() + row.height());
            }
        }
        return new PanelPlacement(SaoMenuModel.SMALL_ROOT_WIDTH / 2.0D, rootBottom / 2.0D,
            contentLeft, right, 0, bottom);
    }

    private record PanelPlacement(double centerX, double centerY, int left, int right, int top, int bottom) {}

    private static Quaternionf panelOrientation(float yaw, float pitch, Vec3 panelRelative) {
        Quaternionf orientation = new Quaternionf().rotationYXZ(
            (float) (Math.PI - Math.toRadians(yaw)), (float) -Math.toRadians(pitch), 0.0F);
        Vector3f normal = orientation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
        Vector3f toViewer = panelRelative.normalize().scale(-1.0D).toVector3f();
        float facing = normal.dot(toViewer);
        if (facing < 0.0F) {
            // Turn the readable face towards the viewer while retaining the same opening axis.
            orientation.rotateY((float) Math.PI);
            normal.negate();
            facing = -facing;
        }
        if (facing < MIN_READABLE_DOT) {
            // Only the nearly edge-on view needs a tilt. Face-on views stay fixed.
            Vector3f axis = normal.cross(toViewer, new Vector3f()).normalize();
            float tilt = (float) (Math.acos(facing) - Math.acos(MIN_READABLE_DOT));
            orientation.premul(new Quaternionf().rotationAxis(tilt, axis));
        }
        return orientation;
    }

    private static ViewProjection viewProjection(GameRenderer renderer, Vec3 position, Vec3 forward, Vec3 right, Vec3 up) {
        Vec3 focal = renderer.projectPointToScreen(position.add(forward).add(right).add(up));
        return new ViewProjection(forward, right, up, focal.x, focal.y);
    }

    private static float fitToScreen(ViewProjection view, Vec3 anchor, Quaternionf orientation, PanelPlacement placement) {
        double anchorDepth = anchor.dot(view.forward());
        double anchorX = anchor.dot(view.right()) * view.focalX();
        double anchorY = anchor.dot(view.up()) * view.focalY();
        boolean centerOnScreen = Math.abs(anchorX) < anchorDepth * SCREEN_MARGIN
            && Math.abs(anchorY) < anchorDepth * SCREEN_MARGIN;
        double scale = BLOCKS_PER_PIXEL;
        for (int x : new int[]{placement.left(), placement.right()}) {
            for (int y : new int[]{placement.top(), placement.bottom()}) {
                Vec3 offset = new Vec3(orientation.transform(new Vector3f(
                    (float) (x - placement.centerX()), (float) (placement.centerY() - y), 0.0F)));
                double offsetDepth = offset.dot(view.forward());
                scale = constrainScale(scale, anchorDepth - MIN_VIEW_DOT, -offsetDepth);
                if (centerOnScreen) {
                    // The perspective divide puts depth in the denominator. Account for each corner's depth.
                    double offsetX = offset.dot(view.right()) * view.focalX();
                    double offsetY = offset.dot(view.up()) * view.focalY();
                    double margin = anchorDepth * SCREEN_MARGIN;
                    double depthMargin = offsetDepth * SCREEN_MARGIN;
                    scale = constrainScale(scale, margin - anchorX, offsetX - depthMargin);
                    scale = constrainScale(scale, margin + anchorX, -offsetX - depthMargin);
                    scale = constrainScale(scale, margin - anchorY, offsetY - depthMargin);
                    scale = constrainScale(scale, margin + anchorY, -offsetY - depthMargin);
                }
            }
        }
        return (float) Math.max(0.0D, scale);
    }

    private static double constrainScale(double scale, double available, double growth) {
        return growth > 0.0D ? Math.min(scale, available / growth) : scale;
    }

    private record ViewProjection(Vec3 forward, Vec3 right, Vec3 up, double focalX, double focalY) {}

    public static Vec3 panelAnchor(Vec3 position, float eyeHeight, float yaw) {
        return panelAnchor(position, eyeHeight, yaw, 0.0F);
    }

    public static Vec3 panelAnchor(Vec3 position, float eyeHeight, float yaw, float pitch) {
        return position.add(0.0D, eyeHeight, 0.0D)
            .add(openingDirection(yaw, pitch).scale(PANEL_DISTANCE));
    }

    private static Vec3 openingDirection(float yaw, float pitch) {
        return Vec3.directionFromRotation(pitch, yaw).normalize();
    }

    private static boolean hasClearView(ClientLevel level, Entity source, Vec3 from, Vec3 to) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, source));
        return hit.getType() == HitResult.Type.MISS;
    }
}

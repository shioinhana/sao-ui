package com.daoyou.betterdisplay;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = BetterDisplayMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BetterDisplayConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue CLOSE_DISTANCE_BLOCKS = BUILDER.defineInRange("closeDistanceBlocks", 8, 1, 128);
    private static final ForgeConfigSpec.BooleanValue SHOW_REMOTE_UI = BUILDER.define("showRemoteUi", true);
    private static final ForgeConfigSpec.BooleanValue SHARE_UI_STATE = BUILDER.define("shareUiState", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int closeDistanceBlocks;
    public static boolean showRemoteUi = true;
    public static boolean shareUiState = true;

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        closeDistanceBlocks = CLOSE_DISTANCE_BLOCKS.get();
        showRemoteUi = SHOW_REMOTE_UI.get();
        shareUiState = SHARE_UI_STATE.get();
    }

    public static void setCloseDistanceBlocks(int value) {
        int clamped = Math.max(1, Math.min(128, value));
        CLOSE_DISTANCE_BLOCKS.set(clamped);
        CLOSE_DISTANCE_BLOCKS.save();
        closeDistanceBlocks = clamped;
    }

    public static void setShowRemoteUi(boolean value) {
        SHOW_REMOTE_UI.set(value);
        SHOW_REMOTE_UI.save();
        showRemoteUi = value;
    }

    public static void toggleShareUiState() {
        setShareUiState(!shareUiState);
    }

    public static void setShareUiState(boolean value) {
        SHARE_UI_STATE.set(value);
        SHARE_UI_STATE.save();
        shareUiState = value;
    }
}

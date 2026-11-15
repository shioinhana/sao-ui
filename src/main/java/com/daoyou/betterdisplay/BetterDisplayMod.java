package com.daoyou.betterdisplay;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(BetterDisplayMod.MODID)
public final class BetterDisplayMod {
    public static final String MODID = "betterdisplay";

    public BetterDisplayMod(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, BetterDisplayConfig.SPEC);
        BetterDisplaySounds.register(context.getModBusGroup());
        BetterDisplayInventory.init();
        BetterDisplayCombat.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            BetterDisplayClient.init();
            BetterDisplayInventoryInput.init();
            registerSelfTestHook();
        }
    }

    /**
     * 自测钩子只在测试 jar 里存在；发布包里这个类不存在，这里静默跳过。
     */
    private static void registerSelfTestHook() {
        try {
            Class.forName("com.daoyou.betterdisplay.BetterDisplayTestHook")
                .getMethod("init")
                .invoke(null);
        } catch (ClassNotFoundException ignored) {
            // 发布包：没有自测钩子，正常。
        } catch (ReflectiveOperationException exception) {
            BetterDisplayMod.class.getName();
        }
    }
}
 

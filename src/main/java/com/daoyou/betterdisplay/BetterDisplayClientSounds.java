package com.daoyou.betterdisplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class BetterDisplayClientSounds {
    private static final Identifier MENU_OPEN_ID = soundId("menu_open");
    private static final Identifier MENU_SELECT_ID = soundId("menu_select");
    private static final Identifier EQUIP_ID = soundId("equip");

    private BetterDisplayClientSounds() {
    }

    public static void playMenuOpen() {
        play(MENU_OPEN_ID, 0.7F);
    }

    public static void playMenuSelect() {
        play(MENU_SELECT_ID, 0.7F);
    }

    public static void playEquip() {
        play(EQUIP_ID, 0.8F);
    }

    private static void play(Identifier id, float volume) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        try {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1.0F, volume));
        } catch (RuntimeException ignored) {
        }
    }

    private static Identifier soundId(String name) {
        return Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, name);
    }
}

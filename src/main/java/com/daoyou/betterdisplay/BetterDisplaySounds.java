package com.daoyou.betterdisplay;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class BetterDisplaySounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BetterDisplayMod.MODID);

    public static final RegistryObject<SoundEvent> MENU_OPEN = register("menu_open");
    public static final RegistryObject<SoundEvent> MENU_SELECT = register("menu_select");
    public static final RegistryObject<SoundEvent> EQUIP = register("equip");

    private BetterDisplaySounds() {
    }

    public static void register(BusGroup modBusGroup) {
        SOUND_EVENTS.register(modBusGroup);
    }

    private static RegistryObject<SoundEvent> register(String name) {
        Identifier id = soundId(name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    private static Identifier soundId(String name) {
        return Identifier.fromNamespaceAndPath(BetterDisplayMod.MODID, name);
    }
}

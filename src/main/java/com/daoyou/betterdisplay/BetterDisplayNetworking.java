package com.daoyou.betterdisplay;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * 联机 UI 网络入口。复用 {@link BetterDisplayCombat} 已经建好的 betterdisplay:combat 通道，
 * 新增两个消息 id，避免再开一条通道。
 */
public final class BetterDisplayNetworking {
    private BetterDisplayNetworking() {
    }

    public static void sendToPlayer(ServerPlayer target, RemoteUiStatePacket packet) {
        BetterDisplayCombat.sendRemoteUiState(target, packet);
    }

    public static void sendSaoMenuUpdate(SaoMenuUpdatePacket packet) {
        BetterDisplayCombat.sendSaoMenuUpdate(packet);
    }

    public static void sendToServer(PacketDistributor.PacketTarget target, RemoteUiStatePacket packet) {
        BetterDisplayCombat.sendRemoteUiState(target, packet);
    }
}

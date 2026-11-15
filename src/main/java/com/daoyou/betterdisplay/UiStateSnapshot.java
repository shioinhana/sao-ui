package com.daoyou.betterdisplay;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * 一位远程玩家 UI 状态的快照。1.19.4：UiStateSnapshot(playerId: UUID, saoMenu: SaoMenuState?)。
 */
public record UiStateSnapshot(UUID playerId, SaoMenuState saoMenu, float anchorYaw, float anchorPitch) {

    public static final StreamCodec<ByteBuf, UiStateSnapshot> STREAM_CODEC = StreamCodec.of(
        UiStateSnapshot::encode,
        UiStateSnapshot::decode
    );

    public UiStateSnapshot(UUID playerId, SaoMenuState saoMenu) {
        this(playerId, saoMenu, 0.0F, 0.0F);
    }

    public UiStateSnapshot {
        anchorYaw = saoMenu != null && Float.isFinite(anchorYaw) ? Mth.wrapDegrees(anchorYaw) : 0.0F;
        anchorPitch = saoMenu != null && Float.isFinite(anchorPitch) ? Mth.clamp(anchorPitch, -90.0F, 90.0F) : 0.0F;
    }

    public static void encode(ByteBuf buffer, UiStateSnapshot snapshot) {
        writeUuid(buffer, snapshot.playerId());
        SaoMenuCodec.STREAM_CODEC.encode(buffer, snapshot.saoMenu());
        if (snapshot.saoMenu() != null) {
            buffer.writeBoolean(snapshot.saoMenu().drawn());
            writeHand(buffer, snapshot.saoMenu().leftHand());
            writeHand(buffer, snapshot.saoMenu().rightHand());
            buffer.writeFloat(snapshot.anchorYaw());
            buffer.writeFloat(snapshot.anchorPitch());
        }
    }

    public static UiStateSnapshot decode(ByteBuf buffer) {
        UUID playerId = readUuid(buffer);
        SaoMenuState state = SaoMenuCodec.STREAM_CODEC.decode(buffer);
        if (state != null) {
            state = SaoMenuCodec.withCombat(state, buffer.readBoolean(), readHand(buffer), readHand(buffer));
            return new UiStateSnapshot(playerId, state, buffer.readFloat(), buffer.readFloat());
        }
        return new UiStateSnapshot(playerId, null);
    }

    private static void writeHand(ByteBuf buffer, SaoMenuItem item) {
        buffer.writeBoolean(item != null);
        if (item != null) {
            net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(buffer, item.id().toString());
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buffer, item.count());
        }
    }

    private static SaoMenuItem readHand(ByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return null;
        }
        var id = SaoMenuCodec.parseId(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.decode(buffer));
        int count = net.minecraft.network.codec.ByteBufCodecs.VAR_INT.decode(buffer);
        return id == null || count < 1 ? null : new SaoMenuItem(id, count);
    }

    private static void writeUuid(ByteBuf buffer, UUID uuid) {
        buffer.writeLong(uuid.getMostSignificantBits());
        buffer.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }
}

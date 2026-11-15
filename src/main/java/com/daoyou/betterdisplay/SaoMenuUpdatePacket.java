package com.daoyou.betterdisplay;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/** The local menu selection, or null when the screen closes or stops sharing. */
public record SaoMenuUpdatePacket(SaoMenuState menu, float anchorYaw, float anchorPitch) {
    public static final StreamCodec<ByteBuf, SaoMenuUpdatePacket> STREAM_CODEC = StreamCodec.of(
        (buffer, packet) -> {
            SaoMenuCodec.STREAM_CODEC.encode(buffer, packet.menu());
            if (packet.menu() != null) {
                buffer.writeFloat(packet.anchorYaw());
                buffer.writeFloat(packet.anchorPitch());
            }
        },
        buffer -> {
            SaoMenuState menu = SaoMenuCodec.STREAM_CODEC.decode(buffer);
            return menu == null ? new SaoMenuUpdatePacket(null)
                : new SaoMenuUpdatePacket(menu, buffer.readFloat(), buffer.readFloat());
        }
    );

    public SaoMenuUpdatePacket(SaoMenuState menu) {
        this(menu, 0.0F, 0.0F);
    }

    public SaoMenuUpdatePacket {
        if (menu != null) {
            menu = new SaoMenuState(menu.path(), menu.focus(), menu.inventory(), null,
                menu.scroll(), menu.optionMask(), false, null, null);
        }
        anchorYaw = menu != null && Float.isFinite(anchorYaw) ? Mth.wrapDegrees(anchorYaw) : 0.0F;
        anchorPitch = menu != null && Float.isFinite(anchorPitch) ? Mth.clamp(anchorPitch, -90.0F, 90.0F) : 0.0F;
    }
}

package com.daoyou.betterdisplay;

import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 服务端广播给附近玩家的 UI 快照。1.19.4 对应 SaoMenuUpdate + UiStateSnapshot 的推送链路。
 */
public record RemoteUiStatePacket(UiStateSnapshot snapshot) {

    public static final StreamCodec<ByteBuf, RemoteUiStatePacket> STREAM_CODEC = StreamCodec.of(
        (buffer, packet) -> UiStateSnapshot.STREAM_CODEC.encode(buffer, packet.snapshot()),
        buffer -> new RemoteUiStatePacket(UiStateSnapshot.STREAM_CODEC.decode(buffer))
    );

    public void handle(Consumer<UiStateSnapshot> sink) {
        sink.accept(snapshot);
    }
}

package com.daoyou.betterdisplay;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * 当前 Forge 版本的菜单状态编解码。
 *
 * <pre>
 * boolean present
 * if !present -> end
 * VarInt pathCount(<=8), 每段 UTF(32)
 * UTF(32)  focus，空串 -> null
 * varInt inventoryCount，每项 UTF(128) id + VarInt count —————————————— 见下方说明
 * boolean hasProfile
 * if hasProfile: UTF(32) name, VarInt health, maxHealth, level, armor,
 *                VarInt expPct(0..100), float attack(0..1024), float speed(0..1024)
 * unsigned byte scroll(0..255)
 * unsigned byte optionMask(&0x7F)
 * </pre>
 *
 * 1.19.4 的 count 字段是 <b>定长</b>循环读取（没有写长度前缀），只有 1.21.11 新增的
 * inventory 列表写成带长度的形式，因此两端必须同版本。
 */
public final class SaoMenuCodec {
    /**
     * 1.19.4 UiStateSnapshotKt.MAX_NAME_LENGTH。
     */
    public static final int MAX_NAME_LENGTH = 32;
    /**
     * 1.19.4 UiStateSnapshotKt.MAX_ENTRY_ID_LENGTH。
     */
    public static final int MAX_ENTRY_ID_LENGTH = 128;

    public static final StreamCodec<ByteBuf, SaoMenuState> STREAM_CODEC = StreamCodec.of(
        SaoMenuCodec::encode,
        SaoMenuCodec::decode
    );

    private SaoMenuCodec() {
    }

    public static void encode(ByteBuf buffer, SaoMenuState state) {
        if (state == null) {
            writeBoolean(buffer, false);
            return;
        }
        writeBoolean(buffer, true);

        List<String> path = state.path();
        int pathSize = Math.min(path.size(), SaoMenuState.MAX_DEPTH);
        writeVarInt(buffer, pathSize);
        for (int i = 0; i < pathSize; i++) {
            writeUtf(buffer, path.get(i), MAX_NAME_LENGTH);
        }

        String focus = state.focus() == null ? "" : state.focus();
        writeUtf(buffer, focus, MAX_NAME_LENGTH);

        List<SaoMenuItem> items = new ArrayList<>();
        for (SaoMenuItem item : state.inventory()) {
            if (item == null || item.id() == null) {
                continue;
            }
            if (item.id().toString().length() > MAX_ENTRY_ID_LENGTH) {
                continue;
            }
            if (items.size() >= SaoMenuState.MAX_ITEMS) {
                break;
            }
            items.add(item);
        }
        writeVarInt(buffer, items.size());
        for (SaoMenuItem item : items) {
            writeUtf(buffer, item.id().toString(), MAX_ENTRY_ID_LENGTH);
            writeVarInt(buffer, Math.max(1, item.count()));
        }

        SaoMenuProfile profile = state.profile();
        writeBoolean(buffer, profile != null);
        if (profile != null) {
            writeUtf(buffer, profile.name(), MAX_NAME_LENGTH);
            writeVarInt(buffer, profile.health());
            writeVarInt(buffer, profile.maxHealth());
            writeVarInt(buffer, profile.level());
            writeVarInt(buffer, profile.armor());
            writeVarInt(buffer, clampInt(profile.experiencePct(), 0, 100));
            writeFloat(buffer, clampFloat(profile.attackDamage(), 0.0F, 1024.0F));
            writeFloat(buffer, clampFloat(profile.movementSpeed(), 0.0F, 1024.0F));
        }

        writeUnsignedByte(buffer, state.scroll());
        writeUnsignedByte(buffer, state.optionMask() & 0x7F);
    }

    public static SaoMenuState decode(ByteBuf buffer) {
        if (!readBoolean(buffer)) {
            return null;
        }

        int pathCount = readVarInt(buffer);
        if (pathCount < 0 || pathCount > SaoMenuState.MAX_DEPTH) {
            throw new DecodeException("sao menu path size out of range: " + pathCount);
        }
        List<String> path = new ArrayList<>(pathCount);
        for (int i = 0; i < pathCount; i++) {
            path.add(readUtf(buffer, MAX_NAME_LENGTH));
        }

        String rawFocus = readUtf(buffer, MAX_NAME_LENGTH);
        String focus = rawFocus.isEmpty() ? null : rawFocus;

        int itemCount = readVarInt(buffer);
        if (itemCount < 0 || itemCount > SaoMenuState.MAX_ITEMS) {
            throw new DecodeException("sao menu item count out of range: " + itemCount);
        }
        List<SaoMenuItem> inventory = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            Identifier id = Identifier.parse(readUtf(buffer, MAX_ENTRY_ID_LENGTH));
            int count = Math.max(1, readVarInt(buffer));
            inventory.add(new SaoMenuItem(id, count));
        }

        SaoMenuProfile profile = null;
        if (readBoolean(buffer)) {
            String name = readUtf(buffer, MAX_NAME_LENGTH);
            int health = readVarInt(buffer);
            int maxHealth = readVarInt(buffer);
            int level = readVarInt(buffer);
            int armor = readVarInt(buffer);
            int experiencePct = clampInt(readVarInt(buffer), 0, 100);
            float attackDamage = clampFloat(readFloat(buffer), 0.0F, 1024.0F);
            float movementSpeed = clampFloat(readFloat(buffer), 0.0F, 1024.0F);
            profile = new SaoMenuProfile(name, health, maxHealth, level, armor, experiencePct, attackDamage, movementSpeed);
        }

        int scroll = readUnsignedByte(buffer);
        int optionMask = readUnsignedByte(buffer) & 0x7F;

        return new SaoMenuState(path, focus == null ? "" : focus, inventory, profile, scroll, optionMask, false, null, null);
    }

    /** 快照的服务端战斗槽字段不属于客户端上报的菜单状态。 */
    public static SaoMenuState withCombat(SaoMenuState base, boolean drawn, SaoMenuItem leftHand, SaoMenuItem rightHand) {
        SaoMenuState state = base == null ? SaoMenuState.EMPTY : base;
        return new SaoMenuState(state.path(), state.focus(), state.inventory(), state.profile(),
            state.scroll(), state.optionMask(), drawn, leftHand, rightHand);
    }

    public static Identifier parseId(String value) {
        try {
            return Identifier.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public static boolean isKnownItem(Identifier id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private static void writeBoolean(ByteBuf buffer, boolean value) {
        buffer.writeByte(value ? 1 : 0);
    }

    private static boolean readBoolean(ByteBuf buffer) {
        return buffer.readByte() != 0;
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte read = buffer.readByte();
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 35) {
                throw new DecodeException("varint too big");
            }
        }
    }

    private static void writeUtf(ByteBuf buffer, String value, int maxLength) {
        String text = value == null ? "" : value;
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        int byteLength = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLength > maxLength * 3) {
            throw new IllegalArgumentException("string utf8 size " + byteLength + " exceeds " + (maxLength * 3));
        }
        writeVarInt(buffer, byteLength);
        buffer.writeCharSequence(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readUtf(ByteBuf buffer, int maxLength) {
        int byteLength = readVarInt(buffer);
        if (byteLength < 0 || byteLength > maxLength * 3) {
            throw new DecodeException("string utf8 size " + byteLength + " out of range");
        }
        String text = buffer.readCharSequence(byteLength, java.nio.charset.StandardCharsets.UTF_8).toString();
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        return text;
    }

    private static void writeFloat(ByteBuf buffer, float value) {
        buffer.writeFloat(value);
    }

    private static float readFloat(ByteBuf buffer) {
        return buffer.readFloat();
    }

    private static void writeUnsignedByte(ByteBuf buffer, int value) {
        buffer.writeByte(value & 0xFF);
    }

    private static int readUnsignedByte(ByteBuf buffer) {
        return buffer.readByte() & 0xFF;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static final class DecodeException extends RuntimeException {
        public DecodeException(String message) {
            super(message);
        }
    }
}

package com.daoyou.betterdisplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的远程 UI 状态缓存。对应 1.19.4 {@code RemoteUiStateStore}：
 * 快照按玩家 UUID 存，超过 staleAfterNanos 没有心跳就过期。
 */
public final class RemoteUiStateStore {
    /**
     * 1.19.4 RemoteUiStateStore.staleAfterNanos 的等价物：5 秒。
     */
    private static final long STALE_AFTER_NANOS = 5_000_000_000L;

    private static final Map<UUID, Entry> SNAPSHOTS = new ConcurrentHashMap<>();

    private RemoteUiStateStore() {
    }

    public static void update(UiStateSnapshot snapshot) {
        if (snapshot == null || snapshot.playerId() == null) {
            return;
        }
        if (snapshot.saoMenu() == null) {
            SNAPSHOTS.remove(snapshot.playerId());
            return;
        }
        SNAPSHOTS.put(snapshot.playerId(), new Entry(snapshot, System.nanoTime()));
    }

    public static void remove(UUID playerId) {
        if (playerId != null) {
            SNAPSHOTS.remove(playerId);
        }
    }

    public static UiStateSnapshot get(UUID playerId) {
        Entry entry = playerId == null ? null : SNAPSHOTS.get(playerId);
        if (entry == null) {
            return null;
        }
        if (isStale(entry)) {
            SNAPSHOTS.remove(playerId);
            return null;
        }
        return entry.snapshot();
    }

    public static Collection<UiStateSnapshot> values() {
        prune();
        Collection<UiStateSnapshot> result = new ArrayList<>(SNAPSHOTS.size());
        for (Entry entry : SNAPSHOTS.values()) {
            result.add(entry.snapshot());
        }
        return result;
    }

    public static void clear() {
        SNAPSHOTS.clear();
    }

    private static void prune() {
        long now = System.nanoTime();
        SNAPSHOTS.entrySet().removeIf(entry -> now - entry.getValue().timestampNanos() > STALE_AFTER_NANOS);
    }

    private static boolean isStale(Entry entry) {
        return System.nanoTime() - entry.timestampNanos() > STALE_AFTER_NANOS;
    }

    private record Entry(UiStateSnapshot snapshot, long timestampNanos) {
    }
}

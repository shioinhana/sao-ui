package com.daoyou.betterdisplay;

import java.util.List;

/**
 * 远程玩家的菜单状态。1.19.4 的根数据是 SaoMenuState(path, focus, inventory, profile, scroll, optionMask)，
 * 1.21.11 在此基础上把拔刀状态与双手武器也带过来，让别人的战斗条能一并显示。
 *
 * @param path       当前展开的菜单路径，空列表表示只打开了根菜单
 * @param focus      当前焦点条目 id
 * @param inventory  当前物品页可见的物品（最多 7 个）
 * @param profile    档案数值；不是 profile 页时为 null
 * @param scroll     当前列滚动起点
 * @param optionMask 选项位掩码，只有低 7 位有效
 * @param drawn      战斗槽是否拔刀
 * @param leftHand   战斗槽左手物品
 * @param rightHand  战斗槽右手物品
 */
public record SaoMenuState(List<String> path, String focus, List<SaoMenuItem> inventory,
                           SaoMenuProfile profile, int scroll, int optionMask,
                           boolean drawn, SaoMenuItem leftHand, SaoMenuItem rightHand) {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_SCROLL = 255;
    /**
     * 1.19.4 SaoMenuState.MAX_ITEMS：环上最多 7 个物品。
     */
    public static final int MAX_ITEMS = 7;

    public static final SaoMenuState EMPTY = new SaoMenuState(List.of(), "", List.of(), null, 0, 0, false, null, null);

    public SaoMenuState {
        path = path == null ? List.of() : List.copyOf(path);
        focus = focus == null ? "" : focus;
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
        scroll = Math.max(0, Math.min(MAX_SCROLL, scroll));
        optionMask &= 0x7F;
    }

    public boolean hasProfile() {
        return profile != null;
    }

    public String rootPath() {
        return path.isEmpty() ? "" : path.get(0);
    }
}

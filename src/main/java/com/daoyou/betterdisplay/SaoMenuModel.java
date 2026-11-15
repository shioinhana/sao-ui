package com.daoyou.betterdisplay;

import java.util.ArrayList;
import java.util.List;

/** The built-in menu IDs shared with the inventory screen and their world layout. */
public final class SaoMenuModel {
    public static final int PANEL_WIDTH = 376;
    public static final int PANEL_HEIGHT = 142;
    public static final int PROFILE_LEFT = -92;
    public static final int SMALL_ROOT_WIDTH = 25;

    private static final int ROOT_HEIGHT = 20;
    private static final int ROOT_SPACING = 25;
    private static final int ROW_WIDTH = 89;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_SPACING = 20;
    private static final int COLUMN_GAP = 5;
    private static final int VISIBLE_ROWS = 7;
    private static final String ITEM_PREFIX = "item_";
    private static final Entry EMPTY = leaf("empty", "空", "cancel");

    static final int SHARE_UI_STATE_ENABLED = 0x02;
    public static final int ARMOR_AVAILABLE = 0x04;
    public static final int WEAPON_AVAILABLE = 0x08;
    public static final int TOOL_AVAILABLE = 0x10;
    public static final int EQUIPMENT_MASK_PRESENT = 0x20;

    static final Entry ONLINE_PLAYERS = leaf("online_players", "在线玩家", "social");

    private static final Entry EQUIPMENT = branch("equipment", "装备", "equipment",
        filter("armor", "防具", "armor", "minecraft:iron_chestplate"),
        filter("weapon", "武器", "items", "minecraft:iron_sword"),
        filter("tool", "工具", "items", "minecraft:iron_pickaxe"));

    private static final List<Entry> ROOTS = List.of(
        branch("profile", "资料", "profile",
            EQUIPMENT,
            branch("items", "物品", "items",
                filter("consumable", "消耗品", "items", "minecraft:apple"),
                filter("block", "方块", "items", "minecraft:cobblestone"),
                filter("misc", "杂项", "items", "minecraft:iron_ingot")),
            leaf("skills", "技能", "skills"),
            leaf("crafting", "合成", "crafting"),
            leaf("character_profile", "角色资料", "profile")),
        branch("social", "社交", "social",
            ONLINE_PLAYERS,
            leaf("guild", "公会", "guild"),
            branch("party", "队伍", "party", leaf("invite", "邀请", "invite"), leaf("leave_party", "离开", "cancel")),
            branch("friends", "好友", "friend", leaf("add_friend", "添加好友", "friend"), leaf("offline_friends", "离线好友", "logout"))),
        branch("message", "消息", "message", leaf("inbox", "消息盒", "messagereceived")),
        branch("navigation", "导航", "navigation",
            branch("quest", "任务", "quest", leaf("incomplete_quests", "未完成任务", "cancel"), leaf("completed_quests", "已完成任务", "confirm")),
            branch("recipes", "配方", "crafting", leaf("unlocked_recipes", "已解锁配方", "confirm"), leaf("locked_recipes", "未解锁配方", "cancel")),
            leaf("fieldmap", "区域地图", "fieldmap"),
            leaf("dungeonmap", "迷宫地图", "dungeonmap")),
        branch("settings", "设置", "settings",
            options(), leaf("menu", "菜单", "help"), leaf("logout", "退出", "logout")));

    private SaoMenuModel() {
    }

    public static boolean showsProfile(List<String> path) {
        return path != null && !path.isEmpty() && "profile".equals(path.getFirst());
    }

    public static boolean showsProfile(SaoMenuState state) {
        return state != null && showsProfile(state.path());
    }

    public record Row(Entry entry, boolean root, boolean selected, int x, int y, int width, int height,
                      SaoMenuItem item) {
    }

    public record Entry(String id, String label, String icon, List<Entry> children, String itemIcon) {
        public boolean itemFilter() {
            return itemIcon != null;
        }

        boolean expandable() {
            return itemFilter() || !children.isEmpty();
        }
    }

    public static boolean validPath(List<String> path) {
        return path != null && (path.isEmpty() || findPage(path) != null);
    }

    public static boolean isItemFilter(List<String> path) {
        Entry page = findPage(path);
        return page != null && page.itemFilter();
    }

    public static boolean validFocus(List<String> path, String focus, int visibleItemCount) {
        if (focus == null || path == null) {
            return false;
        }
        if (path.isEmpty()) {
            return focus.isEmpty() || indexOf(ROOTS, focus) >= 0;
        }
        Entry page = findPage(path);
        if (page == null) {
            return false;
        }
        if (focus.isEmpty()) {
            return true;
        }
        if (page.itemFilter()) {
            if (visibleItemCount == 0) {
                return EMPTY.id().equals(focus);
            }
            for (int i = 0; i < Math.min(visibleItemCount, VISIBLE_ROWS); i++) {
                if ((ITEM_PREFIX + i).equals(focus)) {
                    return true;
                }
            }
            return false;
        }
        return indexOf(page.children(), focus) >= 0 || (page == EQUIPMENT && EMPTY.id().equals(focus));
    }

    public static List<Row> rows(SaoMenuState state) {
        if (state == null) {
            return List.of();
        }
        List<Row> result = new ArrayList<>();
        List<String> path = state.path();
        String selectedRoot = path.isEmpty() ? state.focus() : path.getFirst();
        int rootIndex = indexOf(ROOTS, selectedRoot);
        for (int i = 0; i < ROOTS.size(); i++) {
            result.add(new Row(ROOTS.get(i), true, i == rootIndex, 0, i * ROOT_SPACING,
                SMALL_ROOT_WIDTH, ROOT_HEIGHT, null));
        }
        if (path.isEmpty() || rootIndex < 0) {
            return result;
        }

        Entry page = ROOTS.get(rootIndex);
        int x = SMALL_ROOT_WIDTH + COLUMN_GAP;
        int y = rootIndex * ROOT_SPACING;
        for (int level = 1; level <= path.size(); level++) {
            boolean deepest = level == path.size();
            String selected = deepest ? state.focus() : path.get(level);
            if (page.itemFilter()) {
                if (deepest) {
                    appendItems(result, state.inventory(), x, y, selected);
                }
                break;
            }

            List<Entry> entries = availableRows(page, state.optionMask());
            int selectedIndex = indexOf(entries, selected);
            int maxStart = Math.max(0, entries.size() - VISIBLE_ROWS);
            int start = deepest ? Math.min(state.scroll(), maxStart) : Math.max(0, selectedIndex - VISIBLE_ROWS + 1);
            for (int i = start; i < Math.min(entries.size(), start + VISIBLE_ROWS); i++) {
                result.add(row(entries.get(i), i == selectedIndex, x, y + (i - start) * ROW_SPACING, null));
            }
            if (deepest || selectedIndex < 0 || !entries.get(selectedIndex).expandable()) {
                break;
            }
            page = entries.get(selectedIndex);
            x += ROW_WIDTH + COLUMN_GAP;
            y += (selectedIndex - start) * ROW_SPACING;
        }
        return result;
    }

    private static void appendItems(List<Row> rows, List<SaoMenuItem> items, int x, int y, String focus) {
        if (items.isEmpty()) {
            rows.add(row(EMPTY, EMPTY.id().equals(focus), x, y, null));
        }
        for (int i = 0; i < Math.min(items.size(), VISIBLE_ROWS); i++) {
            String id = ITEM_PREFIX + i;
            rows.add(row(leaf(id, "", "items"), id.equals(focus), x, y + i * ROW_SPACING, items.get(i)));
        }
    }

    private static Row row(Entry entry, boolean selected, int x, int y, SaoMenuItem item) {
        return new Row(entry, false, selected, x, y, ROW_WIDTH, ROW_HEIGHT, item);
    }

    private static List<Entry> availableRows(Entry page, int optionMask) {
        if (page != EQUIPMENT || (optionMask & EQUIPMENT_MASK_PRESENT) == 0) {
            return page.children();
        }
        List<Entry> entries = new ArrayList<>(3);
        int bit = ARMOR_AVAILABLE;
        for (Entry category : EQUIPMENT.children()) {
            if ((optionMask & bit) != 0) {
                entries.add(category);
            }
            bit <<= 1;
        }
        return entries.isEmpty() ? List.of(EMPTY) : entries;
    }

    private static Entry findPage(List<String> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        List<Entry> entries = ROOTS;
        Entry page = null;
        for (String id : path) {
            int index = indexOf(entries, id);
            if (index < 0 || !entries.get(index).expandable()) {
                return null;
            }
            page = entries.get(index);
            entries = page.children();
        }
        return page;
    }

    private static int indexOf(List<Entry> entries, String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public static int contentRight(List<Row> rows) {
        int right = 0;
        for (Row row : rows) {
            right = Math.max(right, row.x() + row.width());
        }
        return right;
    }

    public static boolean rowContains(Row row, double mouseX, double mouseY) {
        return mouseX >= row.x() && mouseX < row.x() + row.width()
            && mouseY >= row.y() && mouseY < row.y() + row.height();
    }

    private static Entry branch(String id, String title, String icon, Entry... children) {
        return new Entry(id, title, icon, List.of(children), null);
    }

    private static Entry leaf(String id, String title, String icon) {
        return branch(id, title, icon);
    }

    private static Entry filter(String id, String title, String icon, String itemIcon) {
        return new Entry(id, title, icon, List.of(), itemIcon);
    }

    private static Entry options() {
        return branch("options", "选项", "option",
            branch("interface", "界面", "option",
                leaf("minecraft_options", "Minecraft 设置", "option"), leaf("keybinds", "战斗槽按键", "settings"),
                leaf("share_ui_state", "共享界面状态", "confirm"),
                leaf("close_distance", "关闭距离", "navigation"), leaf("close_distance_up", "关闭距离 +1", "navigation"),
                leaf("close_distance_down", "关闭距离 -1", "navigation"), leaf("default_death_screen", "默认死亡画面", "cancel"),
                leaf("pause_gui", "GUI 暂停", "cancel"), leaf("move_gui", "界面移动", "sprinting")),
            branch("theme", "主题", "settings",
                leaf("default_ui", "默认 UI", "cancel"), leaf("hex2", "Hex2", "cancel"),
                leaf("ggo", "GGO", "cancel"), leaf("sao", "SAO", "cancel")),
            branch("entity", "实体", "profile",
                leaf("entity_health_bar", "实体生命条", "cancel"), leaf("entity_crystal", "实体水晶", "cancel"),
                branch("entity_health_bar_options", "生命条", "profile",
                    leaf("friendly", "友好", "cancel"), leaf("hostile", "敌对", "cancel"), leaf("killer", "杀手", "cancel"),
                    leaf("boss", "Boss", "cancel"), leaf("creative", "创造", "cancel"), leaf("op", "OP", "cancel"),
                    leaf("invalid", "无效", "cancel"))),
            branch("health", "生命", "profile",
                leaf("smooth_health", "平滑生命", "cancel"), leaf("lightweight_hud", "轻量 HUD", "cancel"),
                leaf("absorption_position", "吸收值位置", "cancel"), leaf("screen_enemy_health", "屏幕敌人血条", "cancel"),
                leaf("hide_offline_party", "隐藏离线队伍", "cancel")),
            branch("hotbar", "快捷栏", "items",
                leaf("vanilla_hotbar", "原版快捷栏", "cancel"), leaf("horizontal_hotbar", "横向快捷栏", "cancel"),
                leaf("vertical_hotbar", "纵向快捷栏", "cancel")),
            branch("effects", "特效", "skills",
                leaf("rotating_crystal", "旋转水晶", "cancel"), leaf("particles", "粒子", "cancel"),
                leaf("sound", "声音", "cancel"), leaf("mouse_hover", "鼠标悬停", "cancel")),
            branch("more", "更多", "help",
                branch("misc_options", "杂项", "help",
                    leaf("aggro", "仇恨系统", "cancel"), leaf("mount_status", "坐骑状态", "cancel"),
                    leaf("custom_font", "自定义字体", "cancel"), leaf("text_shadow", "文本阴影", "cancel")),
                branch("debug", "调试", "settings", leaf("rendering_summary", "渲染系统", "cancel"),
                    branch("rendering", "渲染系统", "settings",
                    leaf("crosshair", "准星", "cancel"), leaf("armor", "护甲", "cancel"), leaf("hotbar", "快捷栏", "cancel"),
                    leaf("air", "氧气", "cancel"), leaf("potion_icons", "药水图标", "cancel"),
                    leaf("health", "生命", "cancel"), leaf("hunger", "饥饿", "cancel")))));
    }
}

package com.daoyou.betterdisplay;

/**
 * 一位远程玩家档案面板要显示的数值。字段顺序与 1.19.4 mcui 的 SaoMenuProfile 一致。
 */
public record SaoMenuProfile(String name, int health, int maxHealth, int level, int armor,
                             int experiencePct, float attackDamage, float movementSpeed) {

    public static final SaoMenuProfile EMPTY = new SaoMenuProfile("", 0, 0, 0, 0, 0, 0.0F, 0.0F);
}

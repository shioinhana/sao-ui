package com.daoyou.betterdisplay;

import net.minecraft.resources.Identifier;

/**
 * 远程玩家快捷栏环上的一个物品条目（1.19.4：id + count）。
 */
public record SaoMenuItem(Identifier id, int count) {
}

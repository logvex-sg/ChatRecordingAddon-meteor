package com.logvex.addon.bot.core;

/**
 * One nearby entity, reduced to what the bot's decision logic needs.
 *
 * <p>Minecraft-free on purpose, so combat and threat policy can be tested without a client. The
 * {@code id} is the entity's network id: it is the only handle that stays valid between the scan and
 * the attack, because a live {@code Entity} object can be replaced while the bot is deciding.
 *
 * @param id       the entity's network id
 * @param typeId   the registry id, e.g. {@code minecraft:zombie}
 * @param pos      the entity's block position at scan time
 * @param distance distance from the player in blocks, at scan time
 * @param hostile  true when the entity is a monster that attacks the player
 */
public record EntityRef(int id, String typeId, BlockPos3 pos, double distance, boolean hostile) {
}

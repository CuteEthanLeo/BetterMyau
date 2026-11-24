package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.List;

/** Shared "is this entity something a combat module may act on" test. */
public class TargetUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /**
     * @param players  accept other players (never friends)
     * @param mobs     accept hostile mobs, slimes and bosses
     * @param animals  accept animals, villagers and golems
     * @param teams    reject players on the same team
     * @param botCheck reject players detected as bots
     */
    public static boolean isSelected(Entity entity, boolean players, boolean mobs, boolean animals, boolean teams, boolean botCheck) {
        if (!(entity instanceof EntityLivingBase) || mc.thePlayer == null) {
            return false;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        if (living == mc.thePlayer || living == mc.thePlayer.ridingEntity || living instanceof EntityArmorStand) {
            return false;
        }
        if (living.isDead || living.deathTime > 0 || living.getHealth() <= 0.0f) {
            return false;
        }
        if (living instanceof EntityPlayer) {
            if (!players || !(living instanceof EntityOtherPlayerMP)) {
                return false;
            }
            EntityPlayer player = (EntityPlayer) living;
            if (player.isSpectator() || TeamUtil.isFriend(player)) {
                return false;
            }
            return (!teams || !TeamUtil.isSameTeam(player)) && (!botCheck || !TeamUtil.isBot(player));
        }
        if (living instanceof EntityMob || living instanceof EntitySlime || living instanceof EntityDragon || living instanceof EntityWither) {
            return mobs;
        }
        if (living instanceof EntityAnimal || living instanceof EntityBat || living instanceof EntitySquid
                || living instanceof EntityVillager || living instanceof EntityGolem) {
            return animals;
        }
        return false;
    }

    /** Players only, with team and bot checks. */
    public static boolean isSelected(Entity entity) {
        return isSelected(entity, true, false, false, true, true);
    }

    /** Selected entities within {@code range} blocks (hitbox distance), nearest first. */
    public static List<EntityLivingBase> getTargets(double range, boolean players, boolean mobs, boolean animals, boolean teams, boolean botCheck) {
        List<EntityLivingBase> result = new ArrayList<>();
        if (mc.theWorld == null) {
            return result;
        }
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (isSelected(entity, players, mobs, animals, teams, botCheck) && RotationUtil.distanceToEntity(entity) <= range) {
                result.add((EntityLivingBase) entity);
            }
        }
        result.sort((a, b) -> Double.compare(RotationUtil.distanceToEntity(a), RotationUtil.distanceToEntity(b)));
        return result;
    }

    public static EntityLivingBase getNearest(double range, boolean players, boolean mobs, boolean animals, boolean teams, boolean botCheck) {
        List<EntityLivingBase> targets = getTargets(range, players, mobs, animals, teams, botCheck);
        return targets.isEmpty() ? null : targets.get(0);
    }
}

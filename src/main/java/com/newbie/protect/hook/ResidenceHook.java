package com.newbie.protect.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Residence 领地接入（软依赖）。
 *
 * <p>全程使用反射调用，所以：</p>
 * <ul>
 *     <li>服务器没装 Residence 时不会报错（{@link #isAvailable()} 返回 false）</li>
 *     <li>编译期不需要 Residence 依赖</li>
 *     <li>Residence 换了版本、方法签名略变也只是退化为「不生效」，不会崩</li>
 * </ul>
 *
 * <p>实际调用链（Residence 6.x）：</p>
 * <pre>
 * ResidenceApi.getResidenceManager().getByLoc(loc)   → ClaimedResidence
 * residence.isOwner(player)                          → 是否房主
 * residence.getOwner()                               → 房主名（兜底比较）
 * </pre>
 */
public final class ResidenceHook {

    private final boolean available;
    /** ResidenceApi.getResidenceManager() */
    private Method getResidenceManager;
    /** ResidenceInterface.getByLoc(Location) */
    private Method getByLoc;
    /** ClaimedResidence.isOwner(Player) */
    private Method isOwnerPlayer;
    /** ClaimedResidence.isOwner(UUID) */
    private Method isOwnerUuid;
    /** ClaimedResidence.getOwner() */
    private Method getOwner;
    /** ClaimedResidence.getName() */
    private Method getName;

    public ResidenceHook() {
        boolean ok = false;
        try {
            if (Bukkit.getPluginManager().getPlugin("Residence") != null) {
                Class<?> apiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceApi");
                Class<?> resClass = Class.forName("com.bekvon.bukkit.residence.protection.ClaimedResidence");

                getResidenceManager = apiClass.getMethod("getResidenceManager");
                Class<?> managerInterface = Class.forName(
                        "com.bekvon.bukkit.residence.api.ResidenceInterface");
                getByLoc = managerInterface.getMethod("getByLoc", Location.class);

                isOwnerPlayer = safeMethod(resClass, "isOwner", Player.class);
                isOwnerUuid = safeMethod(resClass, "isOwner", UUID.class);
                getOwner = safeMethod(resClass, "getOwner");
                getName = safeMethod(resClass, "getName");
                ok = true;
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        this.available = ok;
    }

    private static Method safeMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Residence 是否存在且 API 可用。 */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 取该位置所在的领地（含子领地）；不在任何领地里返回 null。
     */
    public Object getResidenceAt(Location location) {
        if (!available || location == null) {
            return null;
        }
        try {
            Object manager = getResidenceManager.invoke(null);
            if (manager == null) {
                return null;
            }
            return getByLoc.invoke(manager, location);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 该位置的领地是否属于这名玩家。
     *
     * @param residence 由 {@link #getResidenceAt} 取到的对象
     */
    public boolean isOwner(Object residence, Player player) {
        if (!available || residence == null || player == null) {
            return false;
        }
        try {
            if (isOwnerPlayer != null) {
                Object r = isOwnerPlayer.invoke(residence, player);
                if (r instanceof Boolean b) {
                    return b;
                }
            }
            if (isOwnerUuid != null) {
                Object r = isOwnerUuid.invoke(residence, player.getUniqueId());
                if (r instanceof Boolean b) {
                    return b;
                }
            }
            // 兜底：比较房主名字
            if (getOwner != null) {
                Object owner = getOwner.invoke(residence);
                if (owner != null) {
                    return String.valueOf(owner).equalsIgnoreCase(player.getName());
                }
            }
        } catch (Throwable ignored) {
            // 忽略，视为不是房主
        }
        return false;
    }

    /** 领地名字（用于提示/诊断），失败返回 null。 */
    public String getName(Object residence) {
        if (residence == null || getName == null) {
            return null;
        }
        try {
            Object n = getName.invoke(residence);
            return n == null ? null : String.valueOf(n);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 该玩家此刻是否站在「自己的领地」里，且按配置应暂停计时。
     *
     * @param ownerOnly true = 只有房主自己享受暂停（成员/访客不算）
     */
    public boolean isInOwnResidence(Player player, boolean ownerOnly) {
        if (!available || player == null) {
            return false;
        }
        try {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world == null) {
                return false;
            }
            Object residence = getResidenceAt(loc);
            if (residence == null) {
                return false;
            }
            if (ownerOnly) {
                return isOwner(residence, player);
            }
            // ownerOnly=false 时，只要是领地就算（含成员/租客）
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

package fi.alavesa.doors;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Moves the doors. A door is a captured region of real blocks; opening slides
 * the whole slab one block per step along its motion face, clipped to the
 * original region - visually it retracts into the wall like an SCP facility
 * door, without needing any pocket space to actually exist. Closing runs the
 * same steps backwards. Behaves like a redstone iron door: binary open/shut,
 * driven by keycard readers, redstone, commands - and refused while its
 * wired lock says no.
 */
public final class DoorEngine implements Listener {

    private final DoorsPlugin plugin;
    private final DoorRegistry registry;
    private final Set<String> animating = new HashSet<>();
    private final Map<String, BukkitTask> autoClose = new HashMap<>();

    public DoorEngine(DoorsPlugin plugin, DoorRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** On enable: make the world match each door's remembered state. */
    public void applyAll() {
        for (DoorRegistry.Door door : registry.doors().values()) {
            if (door.bukkitWorld() != null) {
                paint(door, door.open ? door.span() : 0);
            }
        }
    }

    /**
     * The wired lock, read live from the world: an unpowered lever means
     * LOCKED (flip with lock-inverted). No lever bound = never locked.
     * Live block state instead of cached flags - survives restarts and
     * op tinkering by definition.
     */
    public boolean isLocked(DoorRegistry.Door door) {
        if (door.lockLever == null) return false;
        Block block = door.lockLever.getBlock();
        if (!(block.getBlockData() instanceof Powerable lever)) return false;
        boolean unlocked = lever.isPowered();
        return door.lockInverted ? unlocked : !unlocked;
    }

    public boolean isOpen(DoorRegistry.Door door) { return door.open; }
    public boolean isBusy(DoorRegistry.Door door) { return animating.contains(door.id()); }

    public void open(DoorRegistry.Door door) { animate(door, true); }
    public void close(DoorRegistry.Door door) { animate(door, false); }

    public void toggle(DoorRegistry.Door door) {
        if (door.open) close(door); else open(door);
    }

    /** A granted keycard swipe: open, and auto-close if the type says so. */
    public void keycardActivate(DoorRegistry.Door door) {
        if (door.open) {
            close(door);
            return;
        }
        open(door);
    }

    private void animate(DoorRegistry.Door door, boolean opening) {
        if (door.bukkitWorld() == null || isBusy(door) || door.open == opening) return;
        DoorRegistry.DoorType type = registry.types().getOrDefault(door.type,
            new DoorRegistry.DoorType("fallback", 4, 0, "", "", ""));
        animating.add(door.id());
        cancelAutoClose(door);
        int span = door.span();
        Location center = centerOf(door);
        sound(center, type.soundStart(), 0.8f, opening ? 1.0f : 0.9f);

        // steps run k=1..span (opening) or k=span-1..0 (closing)
        final int[] step = {opening ? 1 : span - 1};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            paint(door, step[0]);
            sound(centerOf(door), type.soundStep(), 0.5f, 0.8f);
            boolean done = opening ? step[0] >= span : step[0] <= 0;
            if (done) {
                door.open = opening;
                animating.remove(door.id());
                registry.saveAll();
                sound(centerOf(door), type.soundEnd(), 0.8f, opening ? 1.1f : 0.8f);
                task[0].cancel();
                if (opening && type.autoCloseSeconds() > 0) {
                    autoClose.put(door.id(), plugin.getServer().getScheduler().runTaskLater(
                        plugin, () -> close(door), type.autoCloseSeconds() * 20L));
                }
                return;
            }
            step[0] += opening ? 1 : -1;
        }, 0L, type.stepTicks());
    }

    private void cancelAutoClose(DoorRegistry.Door door) {
        BukkitTask task = autoClose.remove(door.id());
        if (task != null) task.cancel();
    }

    /**
     * Renders the door at slide offset k: every captured block shifted k
     * along the motion face, clipped to the original region. k=0 closed,
     * k=span fully retracted (region empty).
     */
    private void paint(DoorRegistry.Door door, int k) {
        World world = door.bukkitWorld();
        int dx = door.motion.getModX() * k, dy = door.motion.getModY() * k, dz = door.motion.getModZ() * k;
        int sx = door.max.getBlockX() - door.min.getBlockX();
        int sy = door.max.getBlockY() - door.min.getBlockY();
        int sz = door.max.getBlockZ() - door.min.getBlockZ();
        Set<BlockVector> occupied = new HashSet<>();
        for (Map.Entry<BlockVector, String> cell : door.blocks.entrySet()) {
            int x = cell.getKey().getBlockX() + dx;
            int y = cell.getKey().getBlockY() + dy;
            int z = cell.getKey().getBlockZ() + dz;
            if (x < 0 || y < 0 || z < 0 || x > sx || y > sy || z > sz) continue;
            BlockData data = plugin.getServer().createBlockData(cell.getValue());
            world.getBlockAt(door.min.getBlockX() + x, door.min.getBlockY() + y,
                door.min.getBlockZ() + z).setBlockData(data, false);
            occupied.add(new BlockVector(x, y, z));
        }
        for (int x = 0; x <= sx; x++) {
            for (int y = 0; y <= sy; y++) {
                for (int z = 0; z <= sz; z++) {
                    if (!occupied.contains(new BlockVector(x, y, z))) {
                        Block block = world.getBlockAt(door.min.getBlockX() + x,
                            door.min.getBlockY() + y, door.min.getBlockZ() + z);
                        if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** Redstone drives bound doors exactly like an iron door: power opens,
     *  loss of power closes. The lock outranks redstone. */
    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        for (DoorRegistry.Door door : registry.doors().values()) {
            if (door.redstoneAnchor == null) continue;
            Block anchor = door.redstoneAnchor.getBlock();
            if (!anchor.equals(event.getBlock())) continue;
            boolean powered = event.getNewCurrent() > 0;
            if (powered && !door.open && !isLocked(door)) open(door);
            if (!powered && door.open) close(door);
        }
    }

    private Location centerOf(DoorRegistry.Door door) {
        return new Location(door.bukkitWorld(),
            (door.min.getBlockX() + door.max.getBlockX()) / 2.0 + 0.5,
            (door.min.getBlockY() + door.max.getBlockY()) / 2.0 + 0.5,
            (door.min.getBlockZ() + door.max.getBlockZ()) / 2.0 + 0.5);
    }

    private void sound(Location at, String key, float volume, float pitch) {
        if (key == null || key.isEmpty()) return;
        at.getWorld().playSound(at, key, volume, pitch);
    }
}

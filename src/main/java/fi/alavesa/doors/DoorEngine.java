package fi.alavesa.doors;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves the doors - properly this time. The visible door is an ItemDisplay
 * wearing a doors: namespace model (via the item_model component, so no
 * other resource pack can shadow it); opening SLIDES it smoothly using
 * display interpolation over the type's open-ticks. Collision is a slab of
 * barrier blocks in the doorway that retreats slice by slice in sync with
 * the animation - trailing edge first, exactly the way a real slab would
 * uncover the opening. Iron-door semantics stay: readers, redstone,
 * commands, and a wired lock that outranks everything.
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
            if (door.bukkitWorld() != null) applyDoor(door);
        }
    }

    /** Materialize one door: display present, barriers matching its state. */
    public void applyDoor(DoorRegistry.Door door) {
        ensureDisplay(door);
        setBarriers(door, door.open ? door.travel() : 0);
        positionDisplay(door, door.open, 0);
    }

    // ------------------------------------------------------------ the model

    /** The display that IS the visible door; respawned if it went missing. */
    private ItemDisplay ensureDisplay(DoorRegistry.Door door) {
        if (door.display != null
            && plugin.getServer().getEntity(door.display) instanceof ItemDisplay existing) {
            return existing;
        }
        DoorRegistry.DoorType type = typeOf(door);
        Location at = door.center();
        at.setYaw(0);   // rotation lives in the transformation, never on the entity
        at.setPitch(0);
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setShadowStrength(0);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            // item_model resolves straight through the doors: namespace -
            // the lesson the keycard readers taught us about pack shadowing
            meta.setItemModel(new NamespacedKey("doors", type.model()));
            item.setItemMeta(meta);
            d.setItemStack(item);
            d.addScoreboardTag("doors.model");
            d.setTransformation(transformation(door, door.open));
        });
        door.display = display.getUniqueId();
        registry.saveAll();
        return display;
    }

    /** Closed = centered in the doorway; open = slid `travel` blocks along
     *  the motion face. Facing is baked into the left rotation; translation
     *  is world-axis because the entity itself never rotates. */
    private Transformation transformation(DoorRegistry.Door door, boolean open) {
        float yaw = switch (door.facing) {
            case EAST -> 90f;
            case NORTH -> 180f;
            case WEST -> 270f;
            default -> 0f; // SOUTH - the model's authored face
        };
        Vector3f translation = new Vector3f();
        if (open) {
            int travel = door.travel();
            translation.set(door.motion.getModX() * travel,
                door.motion.getModY() * travel, door.motion.getModZ() * travel);
        }
        return new Transformation(translation,
            new Quaternionf().rotationY((float) Math.toRadians(yaw)),
            new Vector3f(door.width(), door.height(), 1f),
            new Quaternionf());
    }

    private void positionDisplay(DoorRegistry.Door door, boolean open, int interpolateTicks) {
        ItemDisplay display = ensureDisplay(door);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(interpolateTicks);
        display.setTransformation(transformation(door, open));
    }

    // ------------------------------------------------------------- barriers

    /** The doorway slices ordered TRAILING-first along the motion axis: the
     *  side the slab uncovers first as it retreats. slice(k) = cleared when
     *  the slab has moved k+1 blocks. */
    private List<List<Block>> slices(DoorRegistry.Door door) {
        World world = door.bukkitWorld();
        List<List<Block>> slices = new ArrayList<>();
        int travel = door.travel();
        boolean vertical = door.motion == BlockFace.UP || door.motion == BlockFace.DOWN;
        for (int i = 0; i < travel; i++) {
            List<Block> slice = new ArrayList<>();
            for (int x = door.min.getBlockX(); x <= door.max.getBlockX(); x++) {
                for (int y = door.min.getBlockY(); y <= door.max.getBlockY(); y++) {
                    for (int z = door.min.getBlockZ(); z <= door.max.getBlockZ(); z++) {
                        int along = vertical
                            ? (door.motion == BlockFace.UP ? y - door.min.getBlockY()
                                                           : door.max.getBlockY() - y)
                            : switch (door.motion) {
                                case EAST -> x - door.min.getBlockX();
                                case WEST -> door.max.getBlockX() - x;
                                case SOUTH -> z - door.min.getBlockZ();
                                default -> door.max.getBlockZ() - z; // NORTH
                            };
                        // trailing = opposite end from the motion direction
                        if (travel - 1 - along == i) slice.add(world.getBlockAt(x, y, z));
                    }
                }
            }
            slices.add(slice);
        }
        return slices;
    }

    /** Barrier state for "slab has moved k blocks": first k slices open. */
    private void setBarriers(DoorRegistry.Door door, int moved) {
        List<List<Block>> slices = slices(door);
        for (int i = 0; i < slices.size(); i++) {
            Material want = i < moved ? Material.AIR : Material.BARRIER;
            for (Block block : slices.get(i)) {
                if (block.getType() == Material.AIR || block.getType() == Material.BARRIER) {
                    if (block.getType() != want) block.setType(want, false);
                }
            }
        }
    }

    // ------------------------------------------------------------- movement

    public boolean isLocked(DoorRegistry.Door door) {
        if (door.lockLever == null) return false;
        Block block = door.lockLever.getBlock();
        if (!(block.getBlockData() instanceof Powerable lever)) return false;
        boolean unlocked = lever.isPowered();
        return door.lockInverted ? unlocked : !unlocked;
    }

    public boolean isBusy(DoorRegistry.Door door) { return animating.contains(door.id()); }

    public void open(DoorRegistry.Door door) { animate(door, true); }
    public void close(DoorRegistry.Door door) { animate(door, false); }

    public void toggle(DoorRegistry.Door door) {
        if (door.open) close(door); else open(door);
    }

    public void keycardActivate(DoorRegistry.Door door) {
        toggle(door);
    }

    private void animate(DoorRegistry.Door door, boolean opening) {
        if (door.bukkitWorld() == null || isBusy(door) || door.open == opening) return;
        DoorRegistry.DoorType type = typeOf(door);
        animating.add(door.id());
        cancelAutoClose(door);
        sound(door.center(), type.soundStart(), 0.8f, opening ? 1.0f : 0.9f);

        // the model glides in one smooth interpolated move...
        positionDisplay(door, opening, type.openTicks());

        // ...while the collision slab follows it slice by slice
        int travel = door.travel();
        for (int k = 1; k <= travel; k++) {
            final int moved = opening ? k : travel - k;
            long when = Math.max(1, (long) type.openTicks() * k / travel);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> setBarriers(door, moved), when);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            door.open = opening;
            animating.remove(door.id());
            registry.saveAll();
            sound(door.center(), type.soundEnd(), 0.8f, opening ? 1.1f : 0.8f);
            if (opening && type.autoCloseSeconds() > 0) {
                autoClose.put(door.id(), plugin.getServer().getScheduler().runTaskLater(
                    plugin, () -> close(door), type.autoCloseSeconds() * 20L));
            }
        }, type.openTicks() + 1L);
    }

    private void cancelAutoClose(DoorRegistry.Door door) {
        BukkitTask task = autoClose.remove(door.id());
        if (task != null) task.cancel();
    }

    /** Full removal: display gone, doorway left open (all barriers cleared). */
    public void dismantle(DoorRegistry.Door door) {
        cancelAutoClose(door);
        if (door.display != null
            && plugin.getServer().getEntity(door.display) instanceof ItemDisplay display) {
            display.remove();
        }
        setBarriers(door, door.travel());
    }

    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        for (DoorRegistry.Door door : registry.doors().values()) {
            if (door.redstoneAnchor == null) continue;
            if (!door.redstoneAnchor.getBlock().equals(event.getBlock())) continue;
            boolean powered = event.getNewCurrent() > 0;
            if (powered && !door.open && !isLocked(door)) open(door);
            if (!powered && door.open) close(door);
        }
    }

    private DoorRegistry.DoorType typeOf(DoorRegistry.Door door) {
        return registry.types().getOrDefault(door.type,
            new DoorRegistry.DoorType("fallback", "door_sliding", 12, 0, "", ""));
    }

    private void sound(Location at, String key, float volume, float pitch) {
        if (key == null || key.isEmpty()) return;
        at.getWorld().playSound(at, key, volume, pitch);
    }
}

package fi.alavesa.doors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * doors.yml - the same file-driven configuration idea as guns.yml and
 * cars.yml. A "types" section defines door families: which MODEL the door
 * wears (an item definition in the doors: namespace of the resource pack),
 * how long the slide takes, whether it auto-closes, what it sounds like.
 * The "doors" section persists every placed door: doorway region, facing,
 * motion, bindings, state and its display entity.
 */
public final class DoorRegistry {

    /** How a door family looks, moves and sounds. */
    public record DoorType(String name, String model, int openTicks, int autoCloseSeconds,
                           String soundStart, String soundEnd) { }

    /** One placed door: an EMPTY doorway (width x height x 1) filled with
     *  barriers when shut, fronted by an ItemDisplay wearing the model. */
    public static final class Door {
        final String id;
        final String world;
        final BlockVector min;     // doorway region corners (world coords)
        final BlockVector max;
        final BlockFace facing;    // which way the door face looks (toward the player who created it)
        final BlockFace motion;    // which way the slab slides open
        final String type;
        boolean open;
        UUID display;              // the model entity
        final List<UUID> readers = new ArrayList<>();
        Location lockLever;
        boolean lockInverted;
        Location redstoneAnchor;

        Door(String id, String world, BlockVector min, BlockVector max,
             BlockFace facing, BlockFace motion, String type) {
            this.id = id;
            this.world = world;
            this.min = min;
            this.max = max;
            this.facing = facing;
            this.motion = motion;
            this.type = type;
        }

        public String id() { return id; }
        public World bukkitWorld() { return Bukkit.getWorld(world); }

        public int width() {
            return facing == BlockFace.NORTH || facing == BlockFace.SOUTH
                ? max.getBlockX() - min.getBlockX() + 1
                : max.getBlockZ() - min.getBlockZ() + 1;
        }

        public int height() { return max.getBlockY() - min.getBlockY() + 1; }

        /** How far (blocks) the slab travels to clear the doorway. */
        public int travel() {
            return motion == BlockFace.UP || motion == BlockFace.DOWN ? height() : width();
        }

        public Location center() {
            return new Location(bukkitWorld(),
                (min.getBlockX() + max.getBlockX()) / 2.0 + 0.5,
                (min.getBlockY() + max.getBlockY()) / 2.0 + 0.5,
                (min.getBlockZ() + max.getBlockZ()) / 2.0 + 0.5);
        }
    }

    private final Plugin plugin;
    private final Map<String, DoorType> types = new LinkedHashMap<>();
    private final Map<String, Door> doors = new LinkedHashMap<>();
    private File file;

    public DoorRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public Map<String, DoorType> types() { return types; }
    public Map<String, Door> doors() { return doors; }

    public void load() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "doors.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // fresh install, or a types section from the 0.1.x block-door era
        // (recognized by the missing "model" field) - write the current defaults
        boolean stale = yaml.isConfigurationSection("types")
            && yaml.getConfigurationSection("types").getKeys(false).stream()
                .anyMatch(k -> !yaml.isSet("types." + k + ".model"));
        if (!yaml.isConfigurationSection("types") || stale) {
            yaml.set("types", null);
            yaml.set("types.sliding.model", "door_sliding");
            yaml.set("types.sliding.open-ticks", 12);
            yaml.set("types.sliding.auto-close-seconds", 4);
            yaml.set("types.sliding.sound-start", "block.piston.extend");
            yaml.set("types.sliding.sound-end", "block.iron_trapdoor.close");
            yaml.set("types.blast.model", "door_blast");
            yaml.set("types.blast.open-ticks", 40);
            yaml.set("types.blast.auto-close-seconds", 0);
            yaml.set("types.blast.sound-start", "block.piston.contract");
            yaml.set("types.blast.sound-end", "block.anvil.place");
            yaml.set("types.vertical.model", "door_sliding");
            yaml.set("types.vertical.open-ticks", 16);
            yaml.set("types.vertical.auto-close-seconds", 4);
            yaml.set("types.vertical.sound-start", "block.piston.extend");
            yaml.set("types.vertical.sound-end", "block.iron_trapdoor.close");
            save(yaml);
        }

        types.clear();
        ConfigurationSection typeRoot = yaml.getConfigurationSection("types");
        for (String name : typeRoot.getKeys(false)) {
            ConfigurationSection s = typeRoot.getConfigurationSection(name);
            if (s == null) continue;
            types.put(name.toLowerCase(Locale.ROOT), new DoorType(
                name.toLowerCase(Locale.ROOT),
                s.getString("model", "door_sliding"),
                Math.max(2, s.getInt("open-ticks", 12)),
                Math.max(0, s.getInt("auto-close-seconds", 0)),
                s.getString("sound-start", ""), s.getString("sound-end", "")));
        }

        doors.clear();
        ConfigurationSection doorRoot = yaml.getConfigurationSection("doors");
        if (doorRoot != null) {
            for (String id : doorRoot.getKeys(false)) {
                ConfigurationSection s = doorRoot.getConfigurationSection(id);
                if (s == null) continue;
                Door door = new Door(id, s.getString("world"),
                    vector(s.getString("min")), vector(s.getString("max")),
                    BlockFace.valueOf(s.getString("facing", "NORTH")),
                    BlockFace.valueOf(s.getString("motion", "UP")),
                    s.getString("type", "sliding"));
                door.open = s.getBoolean("open", false);
                String display = s.getString("display", "");
                if (!display.isEmpty()) door.display = UUID.fromString(display);
                for (String u : s.getStringList("readers")) door.readers.add(UUID.fromString(u));
                door.lockLever = location(s.getString("lock-lever"));
                door.lockInverted = s.getBoolean("lock-inverted", false);
                door.redstoneAnchor = location(s.getString("redstone"));
                doors.put(id.toLowerCase(Locale.ROOT), door);
            }
        }
    }

    public void saveAll() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("doors", null);
        for (Door door : doors.values()) {
            String base = "doors." + door.id + ".";
            yaml.set(base + "world", door.world);
            yaml.set(base + "min", csv(door.min));
            yaml.set(base + "max", csv(door.max));
            yaml.set(base + "facing", door.facing.name());
            yaml.set(base + "motion", door.motion.name());
            yaml.set(base + "type", door.type);
            yaml.set(base + "open", door.open);
            yaml.set(base + "display", door.display == null ? "" : door.display.toString());
            yaml.set(base + "readers", door.readers.stream().map(UUID::toString).toList());
            yaml.set(base + "lock-lever", string(door.lockLever));
            yaml.set(base + "lock-inverted", door.lockInverted);
            yaml.set(base + "redstone", string(door.redstoneAnchor));
        }
        save(yaml);
    }

    private void save(YamlConfiguration yaml) {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save doors.yml: " + e.getMessage());
        }
    }

    private String csv(BlockVector v) {
        return v.getBlockX() + "," + v.getBlockY() + "," + v.getBlockZ();
    }

    private BlockVector vector(String csv) {
        String[] p = csv.split(",");
        return new BlockVector(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    private Location location(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        World world = Bukkit.getWorld(p[0]);
        return world == null ? null : new Location(world,
            Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    private String string(Location loc) {
        return loc == null ? "" : loc.getWorld().getName() + ","
            + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}

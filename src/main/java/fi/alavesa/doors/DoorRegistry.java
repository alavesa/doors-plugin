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
 * cars.yml: a "types" section defines how a family of doors behaves (speed,
 * sounds, auto-close), and ops can add their own types with a text editor +
 * /doors reload. The "doors" section is the plugin's own persistence: every
 * created door with its captured blocks, motion, bindings and state.
 */
public final class DoorRegistry {

    /** How a door family moves and sounds. */
    public record DoorType(String name, int stepTicks, int autoCloseSeconds,
                           String soundStart, String soundStep, String soundEnd) { }

    /** One placed door: a captured block region that slides along `motion`. */
    public static final class Door {
        final String id;
        final String world;
        final BlockVector min;
        final BlockVector max;
        final BlockFace motion;
        final String type;
        final Map<BlockVector, String> blocks;   // rel pos (from min) -> blockdata string
        boolean open;
        final List<UUID> readers = new ArrayList<>();
        Location lockLever;        // lever position, null = never locked
        boolean lockInverted;      // false: lever ON = unlocked
        Location redstoneAnchor;   // watched block, null = none

        Door(String id, String world, BlockVector min, BlockVector max,
             BlockFace motion, String type, Map<BlockVector, String> blocks) {
            this.id = id;
            this.world = world;
            this.min = min;
            this.max = max;
            this.motion = motion;
            this.type = type;
            this.blocks = blocks;
        }

        public String id() { return id; }
        public World bukkitWorld() { return Bukkit.getWorld(world); }

        /** Steps to fully open = the door's extent along the motion axis. */
        public int span() {
            return switch (motion) {
                case UP, DOWN -> max.getBlockY() - min.getBlockY() + 1;
                case EAST, WEST -> max.getBlockX() - min.getBlockX() + 1;
                default -> max.getBlockZ() - min.getBlockZ() + 1;
            };
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

        if (!yaml.isConfigurationSection("types")) {
            // the built-in families - a starting point, not a limit
            yaml.set("types.sliding.step-ticks", 3);
            yaml.set("types.sliding.auto-close-seconds", 4);
            yaml.set("types.sliding.sound-start", "block.piston.extend");
            yaml.set("types.sliding.sound-step", "block.stone.step");
            yaml.set("types.sliding.sound-end", "block.iron_trapdoor.close");
            yaml.set("types.blast.step-ticks", 10);
            yaml.set("types.blast.auto-close-seconds", 0);
            yaml.set("types.blast.sound-start", "block.piston.contract");
            yaml.set("types.blast.sound-step", "block.grindstone.use");
            yaml.set("types.blast.sound-end", "block.anvil.place");
            yaml.set("types.vertical.step-ticks", 4);
            yaml.set("types.vertical.auto-close-seconds", 4);
            yaml.set("types.vertical.sound-start", "block.piston.extend");
            yaml.set("types.vertical.sound-step", "block.stone.step");
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
                Math.max(1, s.getInt("step-ticks", 4)),
                Math.max(0, s.getInt("auto-close-seconds", 0)),
                s.getString("sound-start", ""), s.getString("sound-step", ""),
                s.getString("sound-end", "")));
        }

        doors.clear();
        ConfigurationSection doorRoot = yaml.getConfigurationSection("doors");
        if (doorRoot != null) {
            for (String id : doorRoot.getKeys(false)) {
                ConfigurationSection s = doorRoot.getConfigurationSection(id);
                if (s == null) continue;
                Map<BlockVector, String> blocks = new LinkedHashMap<>();
                for (String line : s.getStringList("blocks")) {
                    int bar = line.indexOf('|');
                    String[] p = line.substring(0, bar).split(",");
                    blocks.put(new BlockVector(Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]), Integer.parseInt(p[2])), line.substring(bar + 1));
                }
                Door door = new Door(id, s.getString("world"),
                    vector(s.getString("min")), vector(s.getString("max")),
                    BlockFace.valueOf(s.getString("motion", "UP")),
                    s.getString("type", "sliding"), blocks);
                door.open = s.getBoolean("open", false);
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
            yaml.set(base + "min", door.min.getBlockX() + "," + door.min.getBlockY() + "," + door.min.getBlockZ());
            yaml.set(base + "max", door.max.getBlockX() + "," + door.max.getBlockY() + "," + door.max.getBlockZ());
            yaml.set(base + "motion", door.motion.name());
            yaml.set(base + "type", door.type);
            yaml.set(base + "open", door.open);
            List<String> lines = new ArrayList<>();
            door.blocks.forEach((v, data) -> lines.add(
                v.getBlockX() + "," + v.getBlockY() + "," + v.getBlockZ() + "|" + data));
            yaml.set(base + "blocks", lines);
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

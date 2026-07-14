package fi.alavesa.doors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Facility doors: MODELED doors (an ItemDisplay wearing a doors: namespace
 * model) that slide smoothly over an invisible barrier slab. Families are
 * doors.yml types, guns.yml/cars.yml style. Iron-door semantics: keycard
 * readers, redstone or commands move them; a wired lever lock refuses them
 * ("Door is locked."). Created onto an EMPTY doorway - look at the floor
 * block under its left edge as you face it.
 */
public final class DoorsPlugin extends JavaPlugin {

    private DoorRegistry registry;
    private DoorEngine engine;

    @Override
    public void onEnable() {
        registry = new DoorRegistry(this);
        registry.load();
        engine = new DoorEngine(this, registry);
        getServer().getPluginManager().registerEvents(engine, this);
        if (getServer().getPluginManager().getPlugin("Keycards") != null) {
            getServer().getPluginManager().registerEvents(
                new SwipeBridge(this, registry, engine), this);
            getLogger().info("Keycards found - readers can drive doors (/doors bind).");
        } else {
            getLogger().info("Keycards NOT found - doors work via redstone and commands only.");
        }
        getServer().getScheduler().runTask(this, engine::applyAll);
        getLogger().info("Doors enabled - " + registry.doors().size() + " door(s), types: "
            + registry.types().keySet());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("doors.admin")) return error(sender, "No permission.");
        if (args.length == 0) return usage(sender);
        Player player = sender instanceof Player p ? p : null;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (player == null) return error(sender, "Players only.");
                if (args.length < 4) return usage(sender);
                String id = args[1].toLowerCase(Locale.ROOT);
                if (registry.doors().containsKey(id)) return error(sender, "Door '" + id + "' exists.");
                DoorRegistry.DoorType type = registry.types().get(args[2].toLowerCase(Locale.ROOT));
                if (type == null) return error(sender, "Unknown type. Types: " + registry.types().keySet());
                BlockFace motion;
                try {
                    motion = BlockFace.valueOf(args[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return error(sender, "Motion: up, down, north, south, east or west.");
                }
                if (Math.abs(motion.getModX()) + Math.abs(motion.getModY()) + Math.abs(motion.getModZ()) != 1) {
                    return error(sender, "Motion: up, down, north, south, east or west.");
                }
                int width = args.length >= 5 ? Math.max(1, Math.min(6, Integer.parseInt(args[4]))) : 2;
                int height = args.length >= 6 ? Math.max(1, Math.min(6, Integer.parseInt(args[5]))) : 3;
                Block floor = player.getTargetBlockExact(8);
                if (floor == null || floor.getType() == Material.AIR) {
                    return error(sender, "Look at the FLOOR block under the doorway's left edge (as you face it).");
                }
                BlockFace playerFacing = player.getFacing();
                BlockFace facing = playerFacing.getOppositeFace(); // the door looks back at you
                BlockFace right = switch (playerFacing) {          // your right as you face it
                    case NORTH -> BlockFace.EAST;
                    case EAST -> BlockFace.SOUTH;
                    case SOUTH -> BlockFace.WEST;
                    default -> BlockFace.NORTH;
                };
                Block base = floor.getRelative(BlockFace.UP);
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (int i = 0; i < width; i++) {
                    for (int j = 0; j < height; j++) {
                        Block cell = base.getRelative(right, i).getRelative(BlockFace.UP, j);
                        if (cell.getType() != Material.AIR && cell.getType() != Material.BARRIER) {
                            return error(sender, "The doorway must be EMPTY - " + cell.getType()
                                + " at " + cell.getX() + " " + cell.getY() + " " + cell.getZ()
                                + ". The door is a model, not blocks.");
                        }
                        minX = Math.min(minX, cell.getX()); maxX = Math.max(maxX, cell.getX());
                        minY = Math.min(minY, cell.getY()); maxY = Math.max(maxY, cell.getY());
                        minZ = Math.min(minZ, cell.getZ()); maxZ = Math.max(maxZ, cell.getZ());
                    }
                }
                DoorRegistry.Door door = new DoorRegistry.Door(id, player.getWorld().getName(),
                    new BlockVector(minX, minY, minZ), new BlockVector(maxX, maxY, maxZ),
                    facing, motion, type.name());
                registry.doors().put(id, door);
                engine.applyDoor(door);
                registry.saveAll();
                sender.sendMessage(Component.text("Door '" + id + "' created: " + width + "x" + height
                    + " " + type.name() + ", slides " + motion.name().toLowerCase()
                    + ", facing " + facing.name().toLowerCase()
                    + ". Bind a reader with /doors bind " + id, NamedTextColor.AQUA));
                return true;
            }
            case "remove" -> {
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                engine.dismantle(door);
                registry.doors().remove(door.id());
                registry.saveAll();
                sender.sendMessage(Component.text("Door '" + door.id() + "' removed (doorway left open).",
                    NamedTextColor.AQUA));
                return true;
            }
            case "open", "close", "toggle" -> {
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                if (engine.isBusy(door)) return error(sender, "Door is moving.");
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "open" -> engine.open(door);
                    case "close" -> engine.close(door);
                    default -> engine.toggle(door);
                }
                return true;
            }
            case "bind" -> {
                if (player == null) return error(sender, "Players only.");
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                Interaction reader = player.getLocation().getNearbyEntitiesByType(Interaction.class, 5).stream()
                    .filter(i -> i.getScoreboardTags().contains("kc.reader"))
                    .min((a, b) -> Double.compare(a.getLocation().distanceSquared(player.getLocation()),
                        b.getLocation().distanceSquared(player.getLocation())))
                    .orElse(null);
                if (reader == null) return error(sender, "No keycard reader within 5 blocks.");
                if (!door.readers.contains(reader.getUniqueId())) door.readers.add(reader.getUniqueId());
                registry.saveAll();
                sender.sendMessage(Component.text("Reader bound to door '" + door.id()
                    + "' (" + door.readers.size() + " reader(s)).", NamedTextColor.AQUA));
                return true;
            }
            case "unbind" -> {
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                door.readers.clear();
                registry.saveAll();
                sender.sendMessage(Component.text("All readers unbound from '" + door.id() + "'.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "bindlock" -> {
                if (player == null) return error(sender, "Players only.");
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                Block target = player.getTargetBlockExact(8);
                if (target == null || target.getType() != Material.LEVER) {
                    return error(sender, "Look at a LEVER (within 8 blocks).");
                }
                door.lockLever = target.getLocation();
                door.lockInverted = args.length >= 3 && args[2].equalsIgnoreCase("inverted");
                registry.saveAll();
                sender.sendMessage(Component.text("Lock wired to '" + door.id() + "': lever "
                    + (door.lockInverted ? "OFF" : "ON") + " = unlocked. Readers answer 'Door is locked.' otherwise.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "unbindlock" -> {
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                door.lockLever = null;
                registry.saveAll();
                sender.sendMessage(Component.text("Lock removed from '" + door.id() + "'.", NamedTextColor.AQUA));
                return true;
            }
            case "bindredstone" -> {
                if (player == null) return error(sender, "Players only.");
                DoorRegistry.Door door = door(sender, args);
                if (door == null) return true;
                Block target = player.getTargetBlockExact(8);
                if (target == null) return error(sender, "Look at the block to watch (within 8).");
                door.redstoneAnchor = target.getLocation();
                registry.saveAll();
                sender.sendMessage(Component.text("Door '" + door.id() + "' now follows redstone at "
                    + target.getX() + " " + target.getY() + " " + target.getZ() + ".", NamedTextColor.AQUA));
                return true;
            }
            case "list" -> {
                if (registry.doors().isEmpty()) {
                    sender.sendMessage(Component.text("No doors yet. /doors pos1+pos2, then /doors create.",
                        NamedTextColor.GRAY));
                } else {
                    registry.doors().values().forEach(d -> sender.sendMessage(Component.text(
                        d.id() + " - " + d.type + ", slides " + d.motion.name().toLowerCase()
                        + ", " + (d.open ? "OPEN" : "closed")
                        + (engine.isLocked(d) ? ", LOCKED" : "")
                        + ", readers: " + d.readers.size(), NamedTextColor.AQUA)));
                }
                return true;
            }
            case "types" -> {
                registry.types().values().forEach(t -> sender.sendMessage(Component.text(
                    t.name() + " - model " + t.model() + ", " + t.openTicks() + " ticks to open, auto-close "
                    + (t.autoCloseSeconds() > 0 ? t.autoCloseSeconds() + "s" : "off"), NamedTextColor.AQUA)));
                return true;
            }
            case "reload" -> {
                registry.load();
                sender.sendMessage(Component.text("doors.yml reloaded - " + registry.doors().size()
                    + " door(s), types: " + registry.types().keySet(), NamedTextColor.AQUA));
                return true;
            }
            default -> { return usage(sender); }
        }
    }

    private DoorRegistry.Door door(CommandSender sender, String[] args) {
        if (args.length < 2) { usage(sender); return null; }
        DoorRegistry.Door door = registry.doors().get(args[1].toLowerCase(Locale.ROOT));
        if (door == null) error(sender, "No door named '" + args[1] + "'.");
        return door;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> Stream.of("create", "remove", "open", "close", "toggle",
                    "bind", "unbind", "bindlock", "unbindlock", "bindredstone", "list", "types", "reload")
                .filter(o -> o.startsWith(args[0].toLowerCase())).toList();
            case 2 -> args[0].equalsIgnoreCase("create") ? List.of("<id>")
                : registry.doors().keySet().stream()
                    .filter(o -> o.startsWith(args[1].toLowerCase())).toList();
            case 3 -> args[0].equalsIgnoreCase("create")
                ? registry.types().keySet().stream().filter(o -> o.startsWith(args[2].toLowerCase())).toList()
                : args[0].equalsIgnoreCase("bindlock") ? List.of("inverted") : List.of();
            case 4 -> args[0].equalsIgnoreCase("create")
                ? Stream.of("up", "down", "north", "south", "east", "west")
                    .filter(o -> o.startsWith(args[3].toLowerCase())).toList()
                : List.of();
            default -> List.of();
        };
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/doors create <id> <type> <up|down|north|south|east|west> [width] [height] | open|close|toggle <id> | bind|unbind <id> | bindlock <id> [inverted] | unbindlock <id> | bindredstone <id> | remove <id> | list | types | reload",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}

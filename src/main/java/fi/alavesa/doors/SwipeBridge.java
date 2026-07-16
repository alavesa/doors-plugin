package fi.alavesa.doors;

import fi.alavesa.keycards.KeycardSwipeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * The keycard reader side. Registered only when the Keycards plugin is
 * present. A GRANTED swipe at a reader bound to a door moves the door -
 * unless its wired lock says otherwise, in which case the reader answers
 * "Door is locked" and swallows the whole interaction (no grant chirp, no
 * iron-door scan, nothing).
 */
public final class SwipeBridge implements Listener {

    private final DoorsPlugin plugin;
    private final DoorRegistry registry;
    private final DoorEngine engine;

    public SwipeBridge(DoorsPlugin plugin, DoorRegistry registry, DoorEngine engine) {
        this.plugin = plugin;
        this.registry = registry;
        this.engine = engine;
    }

    /** SCP-005, the Skeleton Key: bypasses the wired lock entirely. */
    private boolean holdsSkeletonKey(org.bukkit.entity.Player player) {
        for (var item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()
                && item.getItemMeta().getCustomModelDataComponent().getStrings().contains("scp005")) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onSwipe(KeycardSwipeEvent event) {
        UUID reader = event.getReader().getUniqueId();
        boolean any = false;
        boolean locked = false;
        for (DoorRegistry.Door door : registry.doors().values()) {
            if (!door.readers.contains(reader)) continue;
            any = true;
            if (!event.isGranted()) continue; // normal deny flow handles it
            if (engine.isLocked(door) && !holdsSkeletonKey(event.getPlayer())) {
                locked = true;
                continue;
            }
            engine.keycardActivate(door); // SCP-005 opens even locked doors
        }
        if (any && locked && event.isGranted()) {
            event.setCancelled(true); // we own the answer: no grant feedback
            Msg.actionbar(event.getPlayer(), Component.text("Door is locked.",
                NamedTextColor.GRAY, TextDecoration.ITALIC));
            event.getPlayer().playSound(event.getReader().getLocation(),
                Sound.BLOCK_IRON_DOOR_CLOSE, 0.6f, 0.5f);
        }
    }
}

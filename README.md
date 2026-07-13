# doors-plugin

Facility doors for SCP RP servers (Paper 1.21.5+): real-block sliding doors,
blast doors and vertical doors that behave exactly like redstone-activated
iron doors - driven by keycard readers, redstone or commands, and refused by
a wired lever lock ("Door is locked.").

Part of the SCP server family - pairs with
[keycard-datapack](https://github.com/alavesa/keycard-datapack) (v0.10.0+ plugin).

## Door families (doors.yml, guns.yml/cars.yml style)

Built-in: `sliding` (fast, auto-closes in 4s), `blast` (slow, heavy, stays
open), `vertical` (for up/down doors). Add your own in `doors.yml`:
step-ticks, auto-close-seconds, three sounds. `/doors reload` applies.

## Building a door

1. Build the door CLOSED out of real blocks (any blocks, up to 256).
2. `/doors pos1` + `/doors pos2` looking at opposite corners.
3. `/doors create <id> <type> <up|down|north|south|east|west>` - the motion
   is the direction the slab retracts (a door 3 high sliding `up` vanishes
   into the ceiling in 3 steps).

## Wiring

- `/doors bind <id>` near a keycard reader: granted swipes move the door
  (open->auto-close, or toggle for families with auto-close off).
- `/doors bindlock <id> [inverted]` looking at a LEVER: lever off = locked -
  readers answer **"Door is locked."** and nothing moves. Flip the lever
  (the remote door control switch) and the same card now works.
- `/doors bindredstone <id>` looking at any block: power opens, no power
  closes - iron door semantics for the custom slab.
- `/doors open|close|toggle <id>` for ops, locks notwithstanding.

## Install

`Doors-x.y.z.jar` -> plugins/. Optional but intended: Keycards plugin
v0.10.0+ (readers fire the swipe event this plugin listens to). No resource
pack needed - the doors are real blocks.

## License

MIT

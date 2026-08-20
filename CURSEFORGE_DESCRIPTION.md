# Truly Best Friends Forever

**Keep your pets by your side, forever.**

Truly Best Friends Forever is a Minecraft pet management mod that tracks and persistently stores your tamed pets. Its dedicated management screen lets you inspect, store, summon, heal, and revive them.

Even if a pet is in an unloaded chunk, in another dimension, or already dead, you can still manage it from the pet screen.

[GitHub / Issue Tracker](https://github.com/whidte/trulybestfriends) | [MC Encyclopedia](https://www.mcmod.cn/class/28331.html)

## Supported Versions

| Minecraft | Mod Loader | Java |
|---|---|---|
| 1.20.1 | Forge 47.4.20+ | Java 17 |
| 1.21.1 | NeoForge 21.1.228+ | Java 21 |

> The mod must be installed on both the client and server. For singleplayer, installing it in your local client is sufficient.

## Main Features

### Automatic Tracking and Persistent Storage
<div class="spoiler">
<p>Tamed wolves, cats, horses, parrots, and pets from other mods are registered automatically. The complete NBT data of each pet is stored separately and managed through an index.</p>
<p>Servers that need to reduce scanning overhead can enable <code>performanceMode</code> in the configuration. This disables automatic tracking and other expensive scans. Pets must then be registered manually by right-clicking them with a feather or by using <code>/tbf load</code>. The registration item and whether it is consumed are configurable.</p>
</div>

### Pet Management Screen
<div class="spoiler">
<p>The pet screen can be opened with a configurable keybind. When a compatible version of <a href="https://www.curseforge.com/minecraft/mc-mods/l2library" rel="nofollow">L2 Library</a> is installed, it is also available through the Pet Companions inventory tab.</p>
<p>The screen displays each pet's name, health, dimension, and coordinates. Its latest status is synchronized from the server in real time.</p>
</div>

### Store, Release, and Area Recall
<div class="spoiler">
<ul>
<li>Click the store button to save a pet and safely remove it from the world. Click it again to release the pet.</li>
<li>Storing pets has configurable distance and cooldown limits.</li>
<li>Hold <strong>Shift</strong> while clicking the store button to recall multiple nearby pets at once.</li>
<li>Use the mouse wheel to adjust the area recall radius from 1 to 16 blocks. The default is 8 blocks.</li>
</ul>
</div>

### Cross-Dimensional Summoning
<div class="spoiler">
<p>Pets can be summoned to their owner from any dimension. Sitting pets automatically stand up after being summoned.</p>
<p>If the pet's chunk is not loaded, the mod creates a pending summon and completes the teleport after the chunk becomes available. Forced chunk loading and entity deduplication prevent duplicate pets from being created.</p>
</div>

### Mount Swapping
<div class="spoiler">
<p>While riding a registered pet, the Summon button for another pet that has previously been ridden changes to Swap Mount. Using it summons the new mount and automatically stores the previous one.</p>
<p>Hold <strong>Shift</strong> to change the Swap Mount button back to a normal Summon button.</p>
</div>

### Pet Healing
<div class="spoiler">
<ul>
<li>Standard healing consumes 3 food points and heals the selected pet for 15 seconds.</li>
<li>Hold <strong>Shift</strong> to use advanced healing, which consumes 9 food points and heals more frequently.</li>
<li>The remaining healing duration can be extended up to 60 seconds.</li>
<li>Creative mode players do not consume food points.</li>
</ul>
</div>

### Death and Revival
<div class="spoiler">
<p>When a tracked pet dies, the mod stores its state and prevents item drops, avoiding item duplication through repeated revivals. The pet can then be revived from the management screen by consuming the configured revival item. By default, revival costs one Totem of Undying.</p>
<p>Revived pets receive the same Regeneration, Absorption, and Fire Resistance effects granted by a Totem of Undying.</p>
<p>Entity types that must keep their normal death and drop behavior can be added to <code>noReviveWhitelist</code> or <code>clearOnDeathWhitelist</code>.</p>
</div>

### Safe Tracking Removal
<div class="spoiler">
<p>Removing a living pet from tracking requires two confirmation steps to prevent accidental deletion:</p>
<ol>
<li>Left-click the delete button to enter the pending deletion state.</li>
<li>Hold <strong>Shift</strong> and left-click again to confirm.</li>
</ol>
<p>When a stored pet is removed from tracking, it is first released back into the world. Removing a dead pet allows it to continue through its normal death process.</p>
<p>If <code>deleteStoredPetsDirectly</code> is enabled, the stored data of recalled or dead pets is permanently deleted without releasing the entity.</p>
</div>

### Search, Filters, and Priority
<div class="spoiler">
<ul>
<li>Filter the list by pet species.</li>
<li>Search for pets by name.</li>
<li>Hold <strong>Shift</strong> and left-click a pet icon to assign a priority from 1 to 6 for list sorting.</li>
<li>Pets carried on a player's shoulders, such as parrots, remain tracked and are automatically linked to their new UUID after leaving the shoulder.</li>
</ul>
</div>

### Pet Teams
<div class="spoiler">
<p>The mod includes a dedicated team editor:</p>
<ul>
<li>Create up to 8 pet teams.</li>
<li>Each team holds 6 pets by default. This can be adjusted up to 8 with <code>maxPendingSummons</code>.</li>
<li>Drag pets into team slots, or select a pet and click the plus button in an empty slot.</li>
</ul>
<p>Hold the configurable team wheel key to open a radial menu for the current team. Point at a pet and release the key, or left-click, to summon it. The wheel also supports mount swapping. Click the bottle icon to summon the entire team.</p>
</div>

### Inventory and Accessory Backups
<div class="spoiler">
<p>The mod separately backs up the inventory NBT of container-carrying entities, such as horses equipped with chests. Modded entities with arbitrary slot counts are supported.</p>
<p>On Minecraft 1.21.1, installing Curios 9.5.1+ also allows pet accessories to be backed up and restored as part of the complete entity snapshot.</p>
</div>

### Dimension Names and Offline NBT Editing
<div class="spoiler">
<p>The mod includes English and Chinese display names for more than 20 common dimensions. These include the three vanilla dimensions, Twilight Forest, Blue Skies, The Aether, Everbright/Everdawn, The Undergarden, Bumblezone, Tropicraft, Gaia Dimension, The Midnight, and the planets and orbits from Ad Astra. Server administrators can also add custom dimension names through the configuration.</p>
<p>While the server is stopped, the <code>.nbt</code> files of stored pets can be edited directly. Changes are preserved and applied to the entity the next time the server starts.</p>
</div>

## Installation

1. Install the appropriate Forge or NeoForge version for your Minecraft version.
2. Download the matching version of Truly Best Friends Forever.
3. Place the mod JAR in `.minecraft/mods/`.
4. Optionally install a compatible L2 Library version to enable the Pet Companions inventory tab.
5. Start the game and configure the pet screen and team wheel keybinds under Options > Controls.

## Commands

The following commands require permission level 2. Registering your own pet with the configured manual registration item does not require operator permissions.

| Command | Description |
|---|---|
| `/tbf load` | Registers the targeted entity as a pet after checking its owner, blacklist status, and pet limit |
| `/tbf load master` | Forces the targeted non-player living entity to be registered to the command user |
| `/tbf autoRegisterBlacklist` | Adds the targeted entity type to the automatic registration blacklist |
| `/tbf noReviveWhitelist` | Adds the targeted entity type to the non-revivable list |
| `/tbf clearOnDeathWhitelist` | Adds the targeted entity type to the list that clears tracking data after death |
| `/tbf clear` | Clears your pet list after a second confirmation within 30 seconds |

## Data Storage

All pet data is stored inside the corresponding world save. Deleting the world also deletes its pet data. Removing the mod does not damage the world.

```text
<world>/trulybestfriends/
|-- pets_index.nbt
`-- <player UUID>/
    `-- <pet UUID>.nbt
```

While the server is stopped, `<pet UUID>.nbt` can be edited to change the health, name, maximum health, and other properties of a stored pet. Coordinates and dimension data are replaced by the summon positioning logic.

## Compatibility

- Designed to support pets from any mod that follows the standard ownable entity interface.
- For entities that are not detected automatically, `ownerNbtFields` can define the NBT path containing the owner's UUID. Nested NBT paths are supported.
- The default configuration includes a nested owner path example for [Mob Controller](https://www.curseforge.com/minecraft/mc-mods/mob-controller-mob-pet).
- Registered pets support [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge).
- The Minecraft 1.21.1 version supports [Create: Aeronautics](https://www.curseforge.com/minecraft/mc-mods/create-aeronautics).
- The mod is incompatible with [InvMove](https://www.curseforge.com/minecraft/mc-mods/invmove). Installing both causes a crash when opening the Pet Companions inventory tab.

If an ownable entity should not be tracked, such as a temporary summon, add its entity ID or a `namespace:*` wildcard to `autoRegisterBlacklist`.

## Known Limitations

- If a newly tamed pet dies before completing its first synchronization cycle, a complete snapshot may not yet exist for revival.
- Cross-dimensional summoning on multiplayer servers is limited by `maxPendingSummons`.
- Setting a pet's NBT `Health` value directly to `0` before summoning places it in a dead-but-not-stored state. Use the revival feature to restore it.
- Before teleporting to a pet, make sure the destination area is safe to avoid suffocation or fall damage.
- Extremely large entity NBT data may exceed the network packet limit and disconnect the player. [Packet Fixer](https://www.curseforge.com/minecraft/mc-mods/packet-fixer) can raise this limit.

## Screenshots

| Pet List |
|---|
| High-priority Canine Metal Golem |
| ![High-priority pet](https://media.forgecdn.net/attachments/1874/506/qq20260817-223821-png.png) |
| Pet List |
|---|
| Stored Fire Dragon |
| ![Stored pet](https://media.forgecdn.net/attachments/1874/539/qq20260817-230404-png.png) |
| Team Editor |
|---|
| White team members |
| ![Team editor](https://media.forgecdn.net/attachments/1874/538/qq20260817-230426-png.png) |
| Selected Wheel Member |
|---|
| Selected Ghast Servant |
| ![Selected wheel member](https://media.forgecdn.net/attachments/1874/508/qq20260817-225538-png.png) |

## Planned Features

- Add a larger pet management interface with selectable layout sizes.

## Feedback and License

Please report bugs and feature requests through [GitHub Issues](https://github.com/whidte/trulybestfriends/issues). Include:

- Your Minecraft, Forge or NeoForge, and mod versions;
- The complete `latest.log` or crash report;
- Clear steps to reproduce the issue.

Truly Best Friends Forever is licensed under **GPL-3.0**. Follow the license terms when modifying, including, or redistributing the mod, and respect the licenses of compatible mods.

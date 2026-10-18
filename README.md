# Easy Waypoints

A lightweight, modern waypoint mod for Fabric that works alongside vanilla Minecraft and is tuned for performance. Client-side only: install it on your client and it works on any server, no server install needed.

[![Modrinth](https://img.shields.io/modrinth/dt/easywaypoints?logo=modrinth&label=Modrinth&color=00AF5C)](https://modrinth.com/mod/easywaypoints)
[![CurseForge](https://img.shields.io/badge/CurseForge-Easy%20Waypoints-F16436?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/easy-waypoints)
![License](https://img.shields.io/badge/license-CC0--1.0-blue)

## Download

| Platform | Link |
| --- | --- |
| Modrinth | https://modrinth.com/mod/easywaypoints |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/easy-waypoints |

## Features

**Waypoints**
- Toggle all waypoints on and off with one key, or hide and highlight individual points from the list.
- Place a waypoint by pointing at a block (Ping / Fast Ping), or create one at your position.
- Waypoints you are not looking at fade out; favorites fade less.
- Automatic death marker that is removed when you return to it. Configurable radius, grace time and maximum number of markers.

**Organization**
- Up to four groups per waypoint, with a group filter in the list and the option to hide a whole group.
- Favorites go first in the list.
- Copy coordinates by double-clicking a waypoint.
- Duplicate a waypoint into the other dimension (Overworld ↔ Nether).

**Sharing**
- Send a waypoint to chat or privately to a player, and save the ones others share with one click.
- Copy and paste whole lists through the clipboard.
- Client commands that work on any server (see below).

**Integration and settings**
- Import and export with Xaero's Minimap.
- Import waypoints from other worlds or servers.
- Customize marker size, opacity, distance units (blocks or km), label case and how many waypoints are drawn at once.
- Optional Mod Menu integration.

## Default keys

All keys can be rebound in Controls → Easy Waypoints.

| Key | Action |
| --- | --- |
| `K` | Toggle waypoint visibility |
| `J` | Open the waypoint list |
| `N` | Create a new waypoint |
| `V` | Fast Ping |
| `H` | Ping the block at the crosshair |

## Commands

Client commands, available on any server:

```
/easywp list
/easywp add <name> [x y z] [color]
/easywp remove <name>
/easywp focus <name>
/easywp share <name>
/easywp tp <name>      (requires operator permissions)
```

Coordinates accept numbers, `~` and `~5`. Colors use `#RRGGBB`.

## Compatibility

| Minecraft | Loader | Requires |
| --- | --- | --- |
| 26.1.x | Fabric | Fabric API |
| 26.2 | Fabric | Fabric API |
| 26.3 | Fabric | Fabric API |

Optional: [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) and [Mod Menu](https://modrinth.com/mod/modmenu).

The interface is available in English and Spanish (Spain, Mexico, Argentina, Chile, Uruguay, Ecuador and Venezuela).

## Building from source

Requires JDK 25.

```bash
git clone https://github.com/cflorians/easyWaypoints.git
cd easyWaypoints
./gradlew build
```

The jars are written to `versions/26.1/build/libs/` (Minecraft 26.1.x and 26.2) and `versions/26.3/build/libs/` (Minecraft 26.3).

To launch a development client for one version:

```bash
./gradlew :versions:26.3:runClient
```

The repository is a multi-module Gradle project: `common/` holds the code that does not depend on Minecraft (storage, formats, tests) and `versions/<x>/` holds the client code for each supported version. `./gradlew :common:test` runs the unit tests.

## Issues and suggestions

Found a bug or have an idea? Open an [issue](https://github.com/cflorians/easyWaypoints/issues) and include your Minecraft version, the mod version and, if there was a crash, the log.

## Development

Developed by [cflorians](https://github.com/cflorians) with the help of [Claude Code](https://claude.com/claude-code).

## License

Released under the [CC0 1.0 Universal](LICENSE) license.

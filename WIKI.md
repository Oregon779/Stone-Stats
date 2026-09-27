# Stone Stats Wiki

Stone Stats is a Paper plugin that shows every player's statistics in a fast, fully configurable inventory GUI: kills, deaths, K/D, mob kills, blocks broken and placed, playtime, first join, last login and XP level. Players can compare themselves with each other, and admins can view offline players and reset stats.

**Version:** 1.0.0 · **Server:** Paper 26.2+ · **Java:** 25

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation and Updating](#installation-and-updating)
- [Commands](#commands)
- [Permissions](#permissions)
- [How Stats Are Counted](#how-stats-are-counted)
- [Rival Comparison](#rival-comparison)
- [Resetting Stats](#resetting-stats)
- [Configuration](#configuration)
- [Placeholders](#placeholders)
- [Text Formatting](#text-formatting)
- [Languages and Messages](#languages-and-messages)
- [Data Storage](#data-storage)
- [Performance on Large Servers](#performance-on-large-servers)
- [FAQ and Troubleshooting](#faq-and-troubleshooting)
- [Building from Source](#building-from-source)

---

## Features

- **Stats GUI** – `/stats` opens your own stats, `/stats <player>` another player's (online or offline, with permission).
- **Rival comparison** – `/stats rival <player>` compares your stats side by side with another player's and shows who leads in each stat.
- **Stat resets** – admins reset a single stat or all stats of any player, online or offline.
- **Fully configurable GUIs** – size, every item, slot, material, name, lore, sounds and filler are set in `config.yml`.
- **Rich text** – `&` color codes, `&#RRGGBB` hex colors and MiniMessage (gradients, …) work everywhere, even mixed.
- **PlaceholderAPI support** (optional) – use any `%placeholder%` in GUI texts and messages.
- **English and German** included, more languages can be added.
- **Automatic config updates** – new options are added on plugin updates without touching your changes.
- **Update checker** – checks Modrinth and tells admins when a new version is out.
- **Built for big servers** – stat tracking costs almost nothing per event, saving runs in the background, data is written crash-safe.

## Requirements

| | |
|---|---|
| Server software | **Paper 26.2 or newer** (or a Paper fork). Spigot/CraftBukkit are not supported, Folia is not supported. |
| Java | **25** |
| Optional | [PlaceholderAPI](https://wiki.placeholderapi.com/) for `%placeholders%` in texts |

## Installation and Updating

**Install**

1. Put `StoneStats-1.0.0.jar` into your server's `plugins/` folder.
2. (Optional) Install PlaceholderAPI.
3. Start the server. Stone Stats creates its folder `plugins/StoneStats/`:

| File | Purpose |
|---|---|
| `config.yml` | All settings and the complete GUI layouts ([Configuration](#configuration)) |
| `languages/en/messages.yml`, `languages/de/messages.yml` | Chat messages ([Languages and Messages](#languages-and-messages)) |
| `stats.yml` | Everyone's stats, created on the first save ([Data Storage](#data-storage)) |

**Update**

Stop the server, replace the jar, start the server. On startup Stone Stats adds options that are new in the update to your `config.yml` and `messages.yml`. It never overwrites values you changed, and GUI items you deleted stay deleted. The console tells you how many options were added.

**Reloading:** after editing `config.yml` or a `messages.yml`, run `/stonestats reload`. A full server restart is not needed. The server's own `/reload` also works (stats are saved and open GUIs are closed first), but a proper restart is always the safer choice.

## Commands

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/stats` | `/mystats` | `stonestats.use` | Opens your own stats GUI. |
| `/stats <player>` | `/mystats <player>` | `stonestats.others` | Opens another player's stats GUI – online players and players who are offline but have played on this server before. |
| `/stats rival <player>` | | `stonestats.rival` | Opens the comparison GUI: your stats vs. theirs. See [Rival Comparison](#rival-comparison). |
| `/stonestats help` | `/ss`, `/sstats` | `stonestats.admin` or `stonestats.reset` | Shows the command overview. `/stonestats` without arguments does the same. |
| `/stonestats reload` | | `stonestats.admin` | Reloads `config.yml`, all messages and both GUI layouts. |
| `/stonestats checkupdate` | | `stonestats.admin` | Checks Modrinth for a new version right now; the result appears in the console. |
| `/stonestats reset <player> <stat\|all>` | | `stonestats.reset` | Resets one stat or all stats of a player. See [Resetting Stats](#resetting-stats). |

Good to know:

- `/stats` can only be used by players, not from the console. `/stonestats` works from the console.
- Opening a GUI again within `gui.open-cooldown-ms` (default 500 ms) is ignored. This stops macros and command spam.
- Looking up an offline player happens in the background, so it never freezes the server even if the name first has to be resolved with Mojang.
- Player names must be real Minecraft names: 1–16 characters, letters, digits and `_`. The characters `.` `*` `-` are also accepted for Bedrock players (Geyser/Floodgate prefixes). Anything else is answered with "has never played on this server".
- **Tab completion** only ever suggests players who are online right now:
  - `/stats` suggests `rival` (with `stonestats.rival`) and player names (with `stonestats.others`).
  - `/stats rival` suggests online players except yourself. Vanished players are hidden unless you have `stonestats.others`.
  - `/stonestats` suggests only the subcommands you are allowed to use, then player names and stat names for `reset`.

## Permissions

| Permission | Default | What it allows |
|---|---|---|
| `stonestats.use` | everyone | Open your own stats with `/stats`. Also required for `/stats rival`. |
| `stonestats.rival` | everyone | Compare your stats with a player who is online with `/stats rival <player>`. |
| `stonestats.others` | OP | View any player's stats with `/stats <player>`, including offline players. Also lets `/stats rival` compare against offline and vanished players. |
| `stonestats.reset` | OP | Reset stats with `/stonestats reset`. |
| `stonestats.admin` | OP | `/stonestats reload`, `checkupdate` and `help`, plus in-game notifications about new versions. **Includes `stonestats.reset`.** |

"everyone" means `default: true`, "OP" means `default: op`: without a permissions plugin, OPs have these automatically.

Examples with [LuckPerms](https://luckperms.net/):

```
# Players may not use /stats rival
/lp group default permission set stonestats.rival false

# Moderators may look at other players' stats
/lp group moderator permission set stonestats.others true

# Admins get everything (reset is included)
/lp group admin permission set stonestats.admin true

# A single user may reset stats, but not reload the plugin
/lp user Steve permission set stonestats.reset true
```

## How Stats Are Counted

| Stat | Counted when … |
|---|---|
| **Kills** | a player dies and another player is their killer. Minecraft counts you as the killer if you damaged them shortly before (melee, arrows, …). Killing yourself (your own arrow or TNT) is a death, but **not** a kill. |
| **Deaths** | a player dies, from any cause. |
| **K/D** | calculated as kills ÷ deaths with one decimal place. With 0 deaths it equals the number of kills. |
| **Mob kills** | a player kills any creature that is not a player (monsters, animals, villagers, …). |
| **Blocks broken / placed** | a player breaks or places a block, in any game mode. |
| **Playtime** | a player is online. The GUI shows it live, including the current session. AFK time counts too. |
| **First join** | the first time a player joins after Stone Stats was installed. |
| **Last login** | the player's most recent join. |
| **Level / XP** | not stored – read live from the game, so only shown while the player is online (otherwise `-`). |

- **Cancelled actions don't count.** If another plugin cancels a block break (region protection), a death (revive or duel plugins) or a mob death, Stone Stats ignores it.
- **Turning stats off:** every stat except first join, last login and level can be switched off under `track` in `config.yml`. A disabled stat is not counted at all and shows `-` in the GUIs.

## Rival Comparison

`/stats rival <player>` opens a second GUI that puts your stats and the other player's side by side:

- **Row 1:** your head – "VS" – their head. Each head shows in how many of the 7 compared stats that player leads.
- **Row 2:** one item each for kills, deaths, K/D, mob kills, playtime, blocks broken and blocks placed – both values plus a verdict line, e.g. `▲ you lead by 12`, `▼ Alex leads by 5` or `● tied`.

Rules:

- For **deaths, fewer is better**; for all other stats, more is better.
- K/D is compared to one decimal place and playtime to the minute, so there is never a verdict like "leads by 0.0".
- You can't compare yourself with yourself.
- Normal players can only compare with players who are **online and visible** to them. With `stonestats.others` you can also compare with offline and vanished players.
- The layout and all texts are configured under `rival-gui` in `config.yml` ([Rival GUI](#rival-gui)).

## Resetting Stats

```
/stonestats reset <player> <stat|all>
```

| Stat name | Resets |
|---|---|
| `kills` | Kills |
| `deaths` | Deaths |
| `mob-kills` | Mob kills |
| `blocks-broken` | Blocks broken |
| `blocks-placed` | Blocks placed |
| `playtime` | Playtime. For an online player, counting starts again from now. |
| `all` | All of the above |

- Works for online and offline players. The player must have stats recorded already.
- First join and last login can't be reset. They are history, not counters.
- **A reset can't be undone.** Every reset is written to the console (who reset what for whom), and the change is saved to disk within about 10 seconds.
- Needs `stonestats.reset` (included in `stonestats.admin`).

Examples:

```
/stonestats reset Steve kills
/stonestats reset Steve all
```

## Configuration

Everything lives in `plugins/StoneStats/config.yml`. Apply changes with `/stonestats reload`. The file itself is commented, so this section explains what each option does and what happens with wrong values.

### General

```yaml
language: en                     # en or de (or your own language, see "Languages")
date-format: "dd.MM.yyyy HH:mm"  # how first join / last login are shown
autosave-interval-minutes: 5     # how often stats are saved in the background
```

- `date-format` uses Java's *SimpleDateFormat* letters (`dd` day, `MM` month, `yyyy` year, `HH` hour, `mm` minute). An invalid pattern is reported once in the console and replaced by the default.
- `autosave-interval-minutes` has a minimum of 1. The save only runs if something changed since the last one. See [Data Storage](#data-storage) for all save moments.

### Tracked stats

```yaml
track:
  kills: true
  deaths: true
  mob-kills: true
  blocks-broken: true
  blocks-placed: true
  playtime: true
```

Set a stat to `false` to stop counting it. It then shows `-` in both GUIs. K/D shows `-` if kills or deaths is off. Turning `playtime` off or on via `/stonestats reload` takes effect immediately for players who are online.

### Stats GUI

```yaml
gui:
  title: "<gradient:#6A11CB:#2575FC><bold>» {player_sc}'s sᴛᴀᴛs «</bold></gradient>"
  rows: 4                          # 1-6 rows (9-54 slots)
  fill-empty-slots: true           # fill every empty slot with the filler item
  filler-item: GRAY_STAINED_GLASS_PANE
  filler-name: " "
  open-cooldown-ms: 500            # min. time between two opens per player, 0 = off
  sounds: ...
  equipment: ...
  items: ...
```

The open cooldown and the sounds are shared by the stats GUI and the rival GUI.

#### Sounds

```yaml
  sounds:
    open:                # when a GUI opens
      enabled: true
      sound: BLOCK_CHEST_OPEN
      volume: 1.0
      pitch: 1.0
    click:               # when a player clicks an item inside a GUI
      enabled: true
      sound: UI_BUTTON_CLICK
      volume: 0.6
      pitch: 1.4
```

`sound` takes Bukkit sound names (see `org.bukkit.Sound` in the [Paper Javadocs](https://jd.papermc.io/)). An unknown name is reported in the console and that sound is turned off.

#### Items

Every entry under `gui.items` becomes one item in the GUI. The entry name (`profile`, `kills`, …) is only a label and can be anything.

```yaml
  items:
    kills:
      slot: 12                 # position, 0-indexed (row 1 = 0-8, row 2 = 9-17, …)
      material: IRON_SWORD     # any item material
      name: "<gradient:#F857A6:#FF5858>ᴋɪʟʟs</gradient>"
      lore:
        - "&8▪ &7ᴘʟᴀʏᴇʀs ᴅᴇꜰᴇᴀᴛᴇᴅ"
        - "&f&l{stat_kills}"
    profile:
      slot: 4
      material: PLAYER_HEAD
      skull-owner: true        # show the viewed player's real head
      name: "<gradient:#F7971E:#FFD200><bold>{player}</bold></gradient>"
      lore: []
```

| Key | Meaning | If it's wrong |
|---|---|---|
| `slot` | Position in the GUI, from 0 to rows × 9 − 1 | Outside the GUI → item skipped, warning in console |
| `material` | Item material (e.g. `DIAMOND`, `CLOCK`) | Unknown, or a block without an item form (e.g. `WALL_TORCH`) → `STONE`, warning in console |
| `name` | Display name, supports [placeholders](#placeholders) and [formatting](#text-formatting) | Missing → the entry's label is used |
| `lore` | List of lines below the name | Missing → no lore |
| `skull-owner` | Only for `PLAYER_HEAD`: `true` shows the viewed player's head | – |

- You can add, move and delete items freely; every placeholder works in every item.
- Items are purely for display: nothing can be taken out of or put into the GUIs. Attribute tooltips (e.g. a sword's attack damage) are hidden automatically.

#### Equipment display (optional)

Off by default. Stone Stats can also show what a player is currently wearing and holding, with values calculated automatically: attack damage, attack speed, armor, armor toughness, a mining-speed indicator for Efficiency, and a durability bar.

```yaml
  equipment:
    offline-item: BARRIER              # shown instead when the player is offline or the slot is empty
    offline-name: "&8Player Offline"
    offline-lore: []
    slots:
      mainhand:
        slot: 28
        accent: "gradient:#55FFFF:#88FFFF"
      helmet:
        slot: 30
        accent: "gradient:#FFD200:#F7971E"
```

- Possible slot keys: `mainhand` (or `tool`), `offhand`, `helmet`, `chestplate`, `leggings`, `boots`.
- `accent` must be a MiniMessage **gradient** tag without the angle brackets. It colors the item name and the durability bar.
- Equipment can only be read from players who are online. For offline players – and for empty equipment slots – the `offline-item` is shown.
- Leave `slots: {}` empty to turn the feature off.

### Rival GUI

The GUI for `/stats rival` has its own section with the same building blocks as the stats GUI:

```yaml
rival-gui:
  title: "<gradient:#6A11CB:#2575FC><bold>» {self_player_sc} ᴠs {rival_player_sc} «</bold></gradient>"
  rows: 3
  fill-empty-slots: true
  filler-item: GRAY_STAINED_GLASS_PANE
  filler-name: " "

  compare:                       # the verdict texts behind {compare_...}
    ahead: "&a▲ &7ʏᴏᴜ ʟᴇᴀᴅ ʙʏ &a&l{diff}"
    behind: "&c▼ &7{rival_player} ʟᴇᴀᴅs ʙʏ &c&l{diff}"
    tie: "&e● &7ᴛɪᴇᴅ"

  items:
    self:
      slot: 2
      material: PLAYER_HEAD
      skull-owner: self          # your own head
      ...
    rival:
      slot: 6
      material: PLAYER_HEAD
      skull-owner: rival         # the other player's head
      ...
    kills:
      slot: 10
      material: IRON_SWORD
      name: "<gradient:#F857A6:#FF5858>ᴋɪʟʟs</gradient>"
      lore:
        - "&8▪ &7{self_player}: &f&l{self_stat_kills}"
        - "&8▪ &7{rival_player}: &f&l{rival_stat_kills}"
        - " "
        - "{compare_kills}"
```

- In the `compare` texts, `{diff}` is the gap between both values and `{rival_player}` the other player's name.
- `skull-owner` accepts `self` (the player who ran the command) and `rival` (the other player).
- All placeholders for this GUI are listed under [Placeholders → Rival GUI](#rival-gui-placeholders).

### Update checker

```yaml
update-checker:
  enabled: true
  check-interval-minutes: 60     # minimum 5
```

- The first check runs about 5 seconds after startup (and after `/stonestats reload`), then at the set interval. The result is always written to the console.
- If a newer version is on Modrinth, everyone with `stonestats.admin` (and every OP) is told in chat – right away and again on every join until you update.
- Set `enabled: false` to turn it off completely.

### When something in the config is wrong

| Problem | What happens |
|---|---|
| YAML syntax error (e.g. a missing quote) in `config.yml` or a `messages.yml` | Your file is **not changed**. Stone Stats uses its built-in defaults until you fix it and prints the exact error in the console. |
| Invalid slot | That item is skipped, warning in console. |
| Invalid material | Replaced by a safe default, warning in console. |
| Unknown sound | That sound is turned off, warning in console. |
| Invalid `date-format` | Default format is used, warning in console. |
| Option missing | It is added to your file with its default value on the next startup or `/stonestats reload`. |
| Deleted a GUI item | It stays deleted. Deleting a *whole* `items` section brings the default items back. |

## Placeholders

Placeholders in `{curly braces}` are Stone Stats' own. They work in the GUI title, item names and lore lines.

### Stats GUI

| Placeholder | Value |
|---|---|
| `{player}` | Name of the player whose stats are shown |
| `{player_sc}` | The same name in small caps (for titles) |
| `{uuid}` | That player's UUID |
| `{stat_kills}` | Kills |
| `{stat_deaths}` | Deaths |
| `{stat_kd}` | K/D ratio, 1 decimal place |
| `{stat_mob_kills}` | Mob kills |
| `{stat_playtime}` | Total playtime, e.g. `4d 6h 12m` |
| `{stat_blocks_broken}` | Blocks broken |
| `{stat_blocks_placed}` | Blocks placed |
| `{stat_first_join}` | Date/time of the first join (format: `date-format`) |
| `{stat_last_join}` | Date/time of the last login |
| `{stat_level}` | Current XP level (online only, otherwise `-`) |
| `{stat_xp_bar}` | Progress bar towards the next level, e.g. `██████░░░░` |
| `{stat_xp_percent}` | The same as a number from 0 to 100 |

Stats that are turned off under `track` show `-`.

### Rival GUI placeholders

- **Every placeholder above, twice:** prefixed with `self_` for the player who ran the command, and with `rival_` for the other player – e.g. `{self_player}`, `{rival_player_sc}`, `{self_stat_kills}`, `{rival_stat_playtime}`.
- **Verdicts** – one line per stat, rendered with the `rival-gui.compare` texts:

| Placeholder | Compares |
|---|---|
| `{compare_kills}` | Kills |
| `{compare_deaths}` | Deaths (fewer leads) |
| `{compare_kd}` | K/D |
| `{compare_mob_kills}` | Mob kills |
| `{compare_playtime}` | Playtime |
| `{compare_blocks_broken}` | Blocks broken |
| `{compare_blocks_placed}` | Blocks placed |
| `{compare_self_leads}` | In how many of these 7 stats you lead |
| `{compare_rival_leads}` | In how many the other player leads |

A stat that is turned off shows `-` as its verdict and doesn't count towards the leads.

### PlaceholderAPI

If PlaceholderAPI is installed, any `%placeholder%` works as well – mixed freely with Stone Stats' own placeholders in the same line.

- In the stats GUI they are filled in for the **player whose stats are shown**. For offline players many PlaceholderAPI placeholders have no value.
- In the rival GUI and in chat messages they are filled in for the **player who ran the command**.
- Many placeholders need their expansion first, e.g. `/papi ecloud download Player` and then `/papi reload`.
- Stone Stats does **not** provide its own `%stonestats_…%` placeholders for other plugins.

## Text Formatting

Three formats work in every GUI text and every message, even mixed in one line:

| Format | Example |
|---|---|
| Classic color codes | `&a` green, `&c` red, `&l` bold, `&o` italic, `&r` reset (`&0`–`&f`, `&k`–`&o`, `&r`) |
| Hex colors | `&#FF00AA` |
| MiniMessage | `<gradient:#F857A6:#FF5858>text</gradient>`, `<bold>`, `<rainbow>` … |

MiniMessage is explained in the [MiniMessage docs](https://docs.advntr.dev/minimessage/format.html); the [MiniMessage viewer](https://webui.advntr.dev/) previews texts in the browser.

**Small caps:** the default layout writes labels in Unicode small caps (`ᴘʟᴀʏᴛɪᴍᴇ`). If you do the same, convert *every* letter – a normal capital letter in between (`Pʟᴀʏᴛɪᴍᴇ`) looks broken. `q`, `s` and `x` have no small-caps form and stay lowercase on purpose.

## Languages and Messages

Chat messages (command feedback, help, update notices) are in `plugins/StoneStats/languages/<language>/messages.yml`. The texts of the GUIs themselves are in `config.yml`, so the whole GUI is configured in one place.

- Included: English (`en`) and German (`de`). Choose with `language:` in `config.yml` (upper/lower case doesn't matter).
- **Your own language:** create `languages/<code>/messages.yml` (e.g. copy the English file to `languages/fr/`), translate it and set `language: fr`. Keys that are missing in your file fall back to English.
- New keys from plugin updates are added to the included languages automatically.

| Key | When it's shown | Placeholders |
|---|---|---|
| `prefix` | In front of every chat message except help lines and the update notice | – |
| `general.no-permission` | Missing permission | – |
| `general.reload-success` | After `/stonestats reload` | – |
| `general.player-only` | `/stats` used from the console | – |
| `general.player-not-found` | Name unknown or invalid | `{player}` |
| `general.opening-others` | Before another player's GUI opens | `{player}` |
| `rival.usage` | `/stats rival` without a name | – |
| `rival.self` | Comparing with yourself | – |
| `rival.not-online` | Rival not online / not visible | `{player}` |
| `rival.opening` | Before the rival GUI opens | `{player}` |
| `reset.usage` | `/stonestats reset` with missing arguments | `{stats}` |
| `reset.unknown-stat` | Stat name doesn't exist | `{stat}`, `{stats}` |
| `reset.no-stats` | Player has no stats recorded | `{player}` |
| `reset.success` | One stat was reset | `{stat}`, `{player}` |
| `reset.success-all` | All stats were reset | `{player}` |
| `help.*` | Lines of `/stonestats help` | – |
| `update.available` | New version found (to admins) | `{version}`, `{current}`, `{behind}` |
| `update.versions-behind` | Text for `{behind}` | `{count}` |
| `update.versions-behind-unknown` | Text for `{behind}` if the count is unknown | – |
| `update.check-triggered` | After `/stonestats checkupdate` | – |

## Data Storage

All stats are stored in `plugins/StoneStats/stats.yml`, one entry per player UUID:

```yaml
players:
  0f8fad5b-d9cb-469f-a165-70867728950e:
    kills: 3
    deaths: 1
    mob-kills: 12
    blocks-broken: 540
    blocks-placed: 310
    playtime-seconds: 86400
    first-join: 1758880000000   # milliseconds since 1970
    last-join: 1758966400000
```

**When is it saved?**

- Every `autosave-interval-minutes` (default 5), but only if something changed.
- About 10 seconds after a player leaves. Many players leaving at once (e.g. before a restart) lead to a single save.
- About 10 seconds after `/stonestats reset`.
- When the server stops, including the playtime of everyone still online.

**Crash safety:** a save first writes `stats.yml.tmp` completely to disk and then swaps it in. If the server crashes mid-save, the previous `stats.yml` stays intact. After a crash you lose at most what happened since the last save.

**Important for admins:**

- **Don't edit `stats.yml` while the server is running.** Stone Stats keeps all stats in memory and overwrites the file at the next save. To edit it: stop the server, edit, start. To reset stats while running, use `/stonestats reset`.
- **Backups:** `stats.yml` can be copied at any time, even while the server runs.
- **Damaged file:** if `stats.yml` can't be read on startup, Stone Stats renames it to `stats.yml.corrupt-<timestamp>`, starts with empty stats and prints an error in the console. Your data is still in that file – stop the server, fix it (or restore a backup) and rename it back to `stats.yml`. If the file can't even be renamed, saving is switched off until the problem is fixed, so the data is never overwritten.

## Performance on Large Servers

Stone Stats is designed for servers with hundreds of players online at once:

- Counting a block, kill or death is an in-memory counter increment – no file access, no config lookups.
- Saving and looking up offline players run in the background, never on the main server thread.
- Saving is fast: about 0.1–0.15 s for 100,000 players (≈ 23 MB `stats.yml`), in the background.
- GUI texts without placeholders are prepared once when the config loads, not on every open.
- The click protection of the GUIs doesn't slow down clicks in other inventories such as chests.

Recommendations:

- Keep `autosave-interval-minutes` at 5 or higher and `gui.open-cooldown-ms` above 0.
- All players who ever joined are kept in memory (a few hundred bytes each). With 100,000 players, loading `stats.yml` adds several seconds to server **startup**; it has no effect while the server runs.
- To find the cause of lag on your server, use a profiler such as Spark.

## FAQ and Troubleshooting

**"Player X has never played on this server", but they have.**
The player must have joined *this* server at least once. Check the spelling: names are 1–16 characters (letters, digits, `_`, plus `.` `*` `-` for Bedrock prefixes).

**My changes to `config.yml` don't show up.**
Run `/stonestats reload` and check the console. Invalid slots, materials and sounds are reported there, as are YAML syntax errors (then the built-in defaults are used until the file is fixed).

**PlaceholderAPI placeholders show up as raw `%text%`.**
Install PlaceholderAPI, download the matching expansion (`/papi ecloud download <name>`) and run `/papi reload`. For offline players many placeholders simply have no value.

**Can other plugins use Stone Stats' stats as `%stonestats_...%` placeholders?**
No, Stone Stats doesn't register its own PlaceholderAPI expansion.

**A kill wasn't counted.**
Kills only count if a player damaged the victim shortly before its death. Killing yourself counts as a death only, and deaths cancelled by other plugins don't count at all.

**Blocks aren't counted in some areas.**
Region protection plugins cancel block breaks there. Cancelled actions are not counted.

**Does playtime count AFK players?**
Yes, playtime is all time spent online.

**Do kills of NPCs (e.g. Citizens) count?**
NPCs that are technically players count like real players – killing them is a kill, their deaths are stored as well.

**The equipment display shows "Player Offline" although the player is online.**
That equipment slot is empty; empty slots show the `offline-item` too.

**Stats are gone after a crash.**
At most the time since the last save is lost (see [Data Storage](#data-storage)). If the console says `stats.yml` was moved to `stats.yml.corrupt-…`, your data is in that file.

**Does Stone Stats run on Spigot or Folia?**
No. It uses Paper-only features; Folia isn't supported.

## Building from Source

For developers who want to build the jar themselves:

1. Install **JDK 25** and **Maven 3.9+**.
2. Run `mvn package` in the project folder.
3. The plugin is at `target/StoneStats-1.0.0.jar`.

- `mvn package` also runs the automated tests (MockBukkit simulates a Paper server with players, events and commands). Skip them with `mvn package -DskipTests`.
- Dependencies: `paper-api` from `repo.papermc.io`, PlaceholderAPI from `repo.extendedclip.com` (compile only, never bundled), MockBukkit and JUnit from Maven Central.

Project layout:

```
src/main/java/dev/stonestats/plugin/
  StoneStats.java     main class: startup, shutdown, reload
  command/            /stats and /stonestats
  listener/           stat tracking, GUI click protection
  manager/            config, messages, GUIs, placeholders, stats storage, update checker
  model/              per-player stats, GUI inventory holder
  util/               text helpers, async player lookup
src/main/resources/   plugin.yml, config.yml, languages/
src/test/java/        MockBukkit tests
```

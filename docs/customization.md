# EveryDeeds: customization guide

Everything EveryDeeds asks of the player comes from **data packs**: plain JSON files, no code. You can
add your own deeds, build a whole new difficulty, add milestones, tweak the built-in sets or switch parts
of them off.

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Deed sets (difficulties)](#deed-sets-difficulties)
- [Deed file reference](#deed-file-reference)
- [Categories and actions](#categories-and-actions)
- [Targets](#targets)
- [Goals and variants](#goals-and-variants)
- [Conditions (predicates)](#conditions-predicates)
- [Milestones](#milestones)
- [Rewards](#rewards)
- [Titles and translations](#titles-and-translations)
- [Tags](#tags)
- [Built-in data packs](#built-in-data-packs)
- [Commands](#commands)
- [Config files](#config-files)
- [Troubleshooting](#troubleshooting)

A ready example pack, a small set with one file per feature, lives in [`datapack-example`](datapack-example).

## How it works

- A **deed** is one thing to do with one object: "break Stone", "obtain a diamond sword with three different
  enchantments", "visit the Nether".
- A **deed file** is a rule that expands into many deeds: "break every block that can be mined" becomes one
  deed per block, including blocks added by other mods.
- A **deed set** is a folder of deed files. Every set is a difficulty on the slider of the progress screen;
  the mod ships four: `short`, `extended`, `insane` and `maniac`.
- Each world has one **active set**. The server tracks what every player does and decides what is complete;
  the client only displays it. The mod has to be installed on the server.
- Players in creative or spectator mode are not tracked.

## Quick start

1. Create a folder in your world's `datapacks` folder, for example `my_deeds`.
2. Add `my_deeds/pack.mcmeta`. The formats must match your game version (121 is Minecraft 26.3):

   ```json
   {
     "pack": {
       "description": "My deeds",
       "min_format": 121,
       "max_format": 121
     }
   }
   ```

3. Add a deed file at `my_deeds/data/mypack/everydeeds/deeds/short/lumberjack.json`:

   ```json
   {
     "category": "blocks",
     "action": "broken",
     "targets": { "tag": "minecraft:logs" },
     "goal": { "type": "count", "count": 64 },
     "title": "Lumberjack",
     "reward": { "items": [ { "id": "minecraft:oak_sapling", "count": 4 } ] }
   }
   ```

4. Run `/reload`. The deed appears in the `short` set: every log gets a "Lumberjack" deed.

The file lies in `data/<namespace>/everydeeds/deeds/<set>/<path>.json`:

- `<namespace>` is yours (`mypack` above). Use your own namespace, never `everydeeds`, unless you mean to
  replace a built-in file.
- `<set>`, the first folder after `deeds/`, is the set the deed belongs to.
- `<path>` is free, subfolders included. Together with the namespace it is the deed's identity (see below).

## Deed sets (difficulties)

**Extending a built-in set.** Put files into its folder (`deeds/short/...`, `deeds/maniac/...`), as in the
quick start. They join the set.

**Creating a new set.** Use a new folder name: `deeds/my_challenge/...`. The set exists as soon as it has one
deed file. It shows up on the difficulty slider (after the built-in ones) and in `/everydeeds sets`, and an
operator switches the world to it with `/everydeeds set my_challenge` or with the slider. A new set starts
empty: it contains only what you put into it.

**Shared progress.** Deeds are identified by namespace and path *without the set folder*.
`everydeeds:short/blocks/broken.json` and `everydeeds:extended/blocks/broken.json` are the same deed, so
breaking Stone in `short` also counts in `extended`. Give a deed the same path in several sets when it means
the same thing, and different paths when it doesn't.

**Past work counts.** The mod keeps a counter of every action on every object, even without a deed for it.
Plain count deeds (a count goal and no condition) pick up these counters: a new "break 1 000 blocks"
milestone is already at 340 if the player broke 340 blocks before. Deeds with a condition or a variant goal
start from what happens after they exist.

**Changing or switching off a built-in deed.** The built-in files are in the mod jar at
`data/everydeeds/everydeeds/deeds/<set>/...`. A file with the same path in your data pack replaces them. To
switch a rule off, replace it with one whose targets are an empty tag, so it produces no deeds:

```json
{
  "category": "blocks",
  "action": "seen",
  "targets": { "tag": "mypack:nothing" }
}
```

(A tag that doesn't exist counts as empty.)

## Deed file reference

| Field | Required | Meaning |
|---|---|---|
| `category` | yes | What kind of object: `blocks`, `items`, `entities`, `biomes`, `dimensions`, `structures`, `effects`. |
| `action` | yes | What to do with it; must apply to the category ([table](#categories-and-actions)). |
| `targets` | yes | Which objects of the category get this deed ([targets](#targets)). |
| `goal` | no | How much is needed. Default: do it once. ([goals](#goals-and-variants)) |
| `predicate` | no | An extra condition the action must meet ([conditions](#conditions-predicates)). |
| `aggregate` | no | `true` makes one [milestone](#milestones) for all targets together. Default `false`. |
| `icon` | no | Item id shown for a milestone. |
| `reward` | no | Experience, items or a function, given once ([rewards](#rewards)). |
| `title` | no | Custom title, a text component ([titles](#titles-and-translations)). |

Files are strict JSON: no comments and no trailing commas (the `jsonc` snippets below only annotate
alternatives).

## Categories and actions

| Action | Categories | Counted when |
|---|---|---|
| `seen` | blocks, entities, structures | Blocks: the crosshair lands on the block (up to 24 blocks away, fluids too). Entities: a mob is in view within 32 blocks (the same mob counts again after a minute). Structures: a part of it lies along your line of sight within `structure_sight_distance`, once per structure per session. |
| `broken` | blocks | You break the block. |
| `placed` | blocks | You place the block. |
| `obtained` | items, effects | Items: the item has been in your inventory, however it got there (picked up, crafted, traded, taken from a chest...). Effects: the effect is newly applied to you. |
| `used` | blocks, items | Blocks: a right click the block reacts to (levers, doors, chests...). Items: the vanilla "used" statistic (swinging tools, shooting, eating, tools breaking blocks...). |
| `crafted` | blocks, items | Taken out of a crafting grid, furnace, stonecutter, smithing table, loom and similar (not trades). A block counts when its item is crafted. |
| `eaten` | items | You finish eating a food item. |
| `fished` | items | Caught with a fishing rod. |
| `brewed` | items | Taken out of a brewing stand. |
| `enchanted` | items | Enchanted at an enchanting table (the result item: a book becomes an enchanted book). |
| `grown` | blocks | You harvest a fully grown crop, or bone meal takes effect on the plant. |
| `killed` | entities | You kill the mob. |
| `damaged_by` | blocks, entities | You take damage from the mob (or its projectile), or from the block: cactus, sweet berry bush, magma, fire, campfires, lava, powder snow, stalagmites, falling anvils and stalactites. |
| `traded` | entities | You complete a trade with the merchant. |
| `tamed` | entities | You tame the animal. |
| `bred` | entities | You breed two animals; counted for the baby's type (a mule for a horse and a donkey). |
| `ridden` | entities | You mount the entity. |
| `visited` | biomes, dimensions, structures | You enter it (counted per entry). |
| `looted` | structures | You open a loot container (or brush a suspicious block) inside the structure. |
| `traveled` | biomes, blocks, entities | Distance in blocks. Biomes: moved horizontally inside the biome, any way of moving. Blocks: swum in the liquid. Entities: ridden on the entity. |
| `time_spent` | dimensions | Seconds spent in the dimension. |

Using an action with a category it doesn't apply to is an error, reported in the log.

## Targets

Three forms:

```jsonc
{ "id": "minecraft:diamond_sword" }                                         // one object
{ "tag": "minecraft:logs" }                                                 // every object in a tag
{ "all": { "traits": ["mineable"], "exclude": "everydeeds:technical" } }    // everything, filtered
```

`all` is the one that follows other mods: a rule on "every mineable block" also covers modded blocks. Both
`traits` and `exclude` are optional; `{ "all": {} }` is every object of the category.

Some objects never get deeds, whatever the selector: air, content of experimental features that are off in
the world, and for data-driven categories anything the world can't have: biomes no dimension generates,
dimensions that don't exist, structures that can't be placed. A goal that can't be reached for an object
(see [variants](#goals-and-variants)) skips that object too.

### Traits

Traits only work inside `all`. An object must have every listed trait.

| Trait | Category | Meaning |
|---|---|---|
| `targetable` | blocks | The crosshair can land on it (or it is a fluid). |
| `mineable` | blocks | Targetable, breakable in survival, not a fluid or fire. |
| `placeable` | blocks | Placed from an item a survival player can obtain. |
| `has_states` | blocks | Has more than one block state. |
| `liquid` | blocks | A liquid: water, lava, modded fluids. |
| `craftable` | blocks, items | The result of a recipe (for a block: the item that places it). |
| `usable` | blocks, items | Blocks: react to a right click. Items: have a use (tools, weapons, food, armor...). |
| `enchantable` | items | At least one enchantment can be applied. |
| `damageable` | items | Has durability. |
| `table_enchantable` | items | Can be enchanted at an enchanting table. |
| `edible` | items | Is food. |
| `living` | entities | A living mob (not a boat, an item or a projectile). |
| `merchant` | entities | Can trade with players. |
| `has_variants` | entities | Has variants to collect (cat types, sheep colours, a baby form...). |
| `leveled` | effects | Obtainable at more than one level. |

The mod's own [tags](#tags) are made for `exclude`: `everydeeds:technical` for things nobody can see or
mine, `everydeeds:unobtainable` for things a survival player can't get.

## Goals and variants

A goal always names its `type`: `count` or `unique`.

**Count:** perform the action a number of times. This is the default (`count` 1) when `goal` is left out.

```json
{ "type": "count", "count": 64 }
```

**Unique:** perform the action with a number of *different* variants of the object.

```json
{ "type": "unique", "variant": "enchantment", "required": 3 }
```

`required` is a number or `"all"` (the default). With `"all"`, objects whose variants can't be listed are
skipped, and so are objects with no variants at all. A number larger than the object's variant count is
lowered to that count. Progress is the set of variants the player actually produced.

| Variant | Category | One variant is |
|---|---|---|
| `enchantment` | items | an enchantment, at any level |
| `enchantment_level` | items | an enchantment at one level (Sharpness I and Sharpness II are two) |
| `enchantment_combination` | items | the item's whole set of enchantments with their levels |
| `durability` | items | a damage value (wearing a tool down counts) |
| `potion` | items | a potion type (water, awkward, swiftness, long swiftness...) |
| `dyed_color` | items | a dye colour (RGB) |
| `block_state` | blocks | a complete block state |
| `block_property` | blocks | one `property=value` pair (no combinations) |
| `entity_variant` | entities | one variant value: cat type, sheep colour, villager profession, baby, charged creeper... |
| `entity_variant_combination` | entities | the mob's whole combination of variant values |
| `effect_level` | effects | an obtainable level of the effect |

When the variants can be listed, hovering the goal in the object's details window shows the ones still missing.

## Conditions (predicates)

A `predicate` is checked every time the action happens; the action only counts when it is true. Conditions
nest with `all_of`, `any_of` and `not`:

```json
{
  "type": "all_of",
  "terms": [
    { "type": "has_enchantment", "enchantment": "minecraft:sharpness", "min_level": 5 },
    { "type": "not", "term": { "type": "dimension", "dimension": "minecraft:overworld" } }
  ]
}
```

| Type | Fields | True when |
|---|---|---|
| `all_of` | `terms`: list | every term is true |
| `any_of` | `terms`: list | at least one term is true |
| `not` | `term` | the term is false |
| `has_enchantment` | `enchantment`, `min_level` (default 1) | the item has the enchantment at that level or higher (enchanted books too) |
| `enchanted` | none | the item has any enchantment |
| `durability` | `min`, `max`: 0 to 1 (defaults 0 and 1) | the item's remaining durability, as a fraction, is in the range |
| `block_property` | `property`, `value` | the block state has `property=value` |
| `dimension` | `dimension` | the player is in that dimension |
| `baby` | none | the mob is a baby |
| `named` | none | the mob or item has a custom name |

Item conditions are false for blocks and mobs, and so on: a condition about another kind of object never matches.

## Milestones

A milestone is one deed for a total over many objects: "break 10 000 blocks", "travel 100 000 blocks". Set
`"aggregate": true`; the goal must be a count. The targets pick which objects feed the total.

```json
{
  "category": "blocks",
  "action": "broken",
  "targets": { "tag": "minecraft:logs" },
  "goal": { "type": "count", "count": 1000 },
  "aggregate": true,
  "icon": "minecraft:iron_axe"
}
```

Milestones sit in their own "Milestones" group at the top of their category, ordered by action and amount.
The cell shows the `icon` item (a nether star without one) and the amount; the full title ("Broken: 1 000")
appears in the details window and in the toast. Reaching a milestone shows a yellow toast with the level-up sound.

By convention the built-in milestones end their path with the amount (`milestones/broken_10000.json`),
so an equal path always means an equal goal.

## Rewards

Given once, when the deed completes (never twice, even across sets).

```json
{
  "experience": 50,
  "items": [ { "id": "minecraft:diamond", "count": 2 } ],
  "function": "mypack:rewards/fanfare"
}
```

- `experience`: experience points.
- `items`: item stacks (`id`, `count`, optional `components`). What doesn't fit into the inventory drops at the player's feet.
- `function`: a function from a data pack, run as the player with operator permissions and no chat output.

Every field is optional.

## Titles and translations

Without a `title`, the title is built from the action and the goal: "Broken ×64", "Placed: every block
state", "Traveled: 256 blocks". A deed with a condition gets a ` *` after such a title, as a hint that
something more is asked. A custom `title` is shown as it is, without the mark. It is a text component, so it
can be translated:

```jsonc
"title": "Lumberjack"
"title": { "translate": "mypack.deed.lumberjack", "fallback": "Lumberjack" }
```

In an object's details window, a deed stays a **"?"** until its action has been done with that object at
least once: players find out what there is to do by trying. Milestones are always shown.

A resource pack can name things through its language files (`assets/<namespace>/lang/<language>.json`):

| Key | Used for |
|---|---|
| `gui.everydeeds.set.<set>` | name of a set on the difficulty slider (`gui.everydeeds.set.my_challenge`). Without it, the folder name in title case: `my_challenge` shows as "My Challenge". |
| `gui.everydeeds.action.<action>.<category>` | a different name of an action in one category: the built-in `gui.everydeeds.action.traveled.blocks` is "Swum" instead of "Traveled". |

## Tags

The built-in sets exclude or select objects through these tags. They are ordinary tags: add to them from your
data pack (a file with the same path and `"replace": false`), for example to mark a modded mob as rideable.

| Tag | File | Contents |
|---|---|---|
| `everydeeds:technical` | `tags/block/technical.json` | blocks nobody can meaningfully see or mine: air, barriers, command blocks, piston heads... |
| `everydeeds:unobtainable` | `tags/block/unobtainable.json` | blocks a survival player can't get as an item: bedrock, spawners, farmland, infested stone... |
| `everydeeds:growable` | `tags/block/growable.json` | plants that grow: crops, saplings, cocoa, nether wart, berries, fungi... |
| `everydeeds:damaging` | `tags/block/damaging.json` | blocks that hurt: cactus, magma, fire, lava, powder snow, anvils... |
| `everydeeds:unobtainable` | `tags/item/unobtainable.json` | items a survival player can't get: spawn eggs, command blocks, the debug stick... |
| `everydeeds:dyeable` | `tags/item/dyeable.json` | items that take a dye colour: leather armor, wolf armor |
| `everydeeds:fishable` | `tags/item/fishable.json` | fishing loot: fish, junk and treasure |
| `everydeeds:brewable` | `tags/item/brewable.json` | what comes out of a brewing stand: potions, splash and lingering potions |
| `everydeeds:technical` | `tags/entity_type/technical.json` | the player and mobs a survival world never has |
| `everydeeds:attackers` | `tags/entity_type/attackers.json` | mobs that can hurt a player |
| `everydeeds:tameable` | `tags/entity_type/tameable.json` | animals a player can tame |
| `everydeeds:breedable` | `tags/entity_type/breedable.json` | animals a player can breed |
| `everydeeds:rideable` | `tags/entity_type/rideable.json` | what a player can ride: mounts, boats, the minecart |
| `everydeeds:lootless` | `tags/worldgen/structure/lootless.json` | structures without anything to loot |
| `everydeeds:unobtainable` | `tags/mob_effect/unobtainable.json` | effects only commands give |

For example, `data/everydeeds/tags/entity_type/rideable.json` in your pack:

```json
{
  "replace": false,
  "values": [ "mymod:giant_turtle" ]
}
```

## Built-in data packs

**EveryDeeds: More Milestones** (`everydeeds:more_milestones`) adds 73 milestones to every built-in set:
first steps and in-between tiers of the core milestones, and milestones for the other actions. It is made of
the same files as described here. It is on by default and can be turned off per world, in the data pack
screen when creating the world or with `/datapack disable "everydeeds:more_milestones"`.

## Commands

| Command | Who | What |
|---|---|---|
| `/everydeeds info` | anyone | the active set and how much of it you completed |
| `/everydeeds sets` | anyone | the loaded sets |
| `/everydeeds set <set>` | operators | switch the world's active set; progress in the other sets is kept |
| `/everydeeds reset <players>` | operators | wipe the players' progress and counters |

The progress screen opens with **G** (rebindable in the game's controls).

## Config files

**`config/everydeeds-server.json`**, on the server:

| Key | Default | Meaning |
|---|---|---|
| `structure_sight_distance` | 64 | how far along the view a structure counts as seen, in blocks (8 to 256) |

**`config/everydeeds-client.json`**, on each client (also editable in game through Mod Menu):

| Key | Default | Meaning |
|---|---|---|
| `cell_size` | 64 | size of a grid cell, in GUI pixels (32 to 128) |
| `focus_zoom` | 1.75 | size of the hover card, relative to a cell (1 to 4) |
| `hover_delay_ms` | 350 | how long the cursor rests on a cell before the card opens (0 to 3000) |
| `show_remaining` | false | list objects nobody has started yet |
| `colors` | | `accent`, `complete`, `in_progress`, `remaining`, `text`, `text_muted`, `panel`, `slot`, `slot_inner`, `border`, `milestone`, `finale`: `#RRGGBB` or `#AARRGGBB` |

## Troubleshooting

- **Nothing changed.** Data packs are read on `/reload` or when the world loads. After a reload the deeds are
  rebuilt and every player gets the new state.
- **A file is ignored.** The log names it: `Couldn't parse deed definition 'mypack:short/lumberjack': ...`,
  followed by the reason, such as an unknown field value or `Action 'placed' does not apply to category 'items'`.
- **The deed doesn't show up.** Its targets resolve to nothing: a misspelled id or tag, a trait that none of the
  objects have, a variant goal with `"all"` whose variants can't be listed. Or it is in another set:
  `/everydeeds info` shows the active one.
- **Progress looks wrong after switching sets.** Deeds with the same path share progress on purpose
  ([deed sets](#deed-sets-difficulties)); give them different paths if they shouldn't.

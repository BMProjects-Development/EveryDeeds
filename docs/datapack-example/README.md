# EveryDeeds: example data pack

A small deed set, `example_custom`, with one file per feature of the format. The format itself is
described in the [customization guide](../customization.md).

## Try it

1. Copy this folder into the `datapacks` folder of a world. Its `pack.mcmeta` is for Minecraft 26.3
   (format 121).
2. Run `/reload`.
3. Switch the world to the set: `/everydeeds set example_custom` (operators), or "Example Custom" on the
   difficulty slider of the progress screen.

## Files

The deed files are in `data/mypack/everydeeds/deeds/example_custom/`:

| File | Shows |
|---|---|
| `lumberjack.json` | targets from a tag, a count goal, a custom title, an item reward |
| `nether_miner.json` | a condition on the player: the action counts only in the Nether |
| `baby_zombie_hunter.json` | a condition on the mob (a baby), an experience reward |
| `sharpest_sword.json` | a condition on the item: Sharpness V or higher |
| `pickaxe_enchantments.json` | a variant goal (3 different enchantments) and the title made from the goal |
| `gourmet.json` | `all` targets with a trait and an exclusion tag: one deed per food, modded food too |
| `milestones/logs_1000.json` | a milestone: 1 000 logs of any kind in total, shown with an iron axe |

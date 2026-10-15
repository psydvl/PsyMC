package dev.psydvl.mc.example;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Blocks of the mod and their items. The loader entries register them. */
public final class ModBlocks {
  // serverSmoke puts this block into the world and sends its chunk again.
  public static final String PROBE_BLOCK = "probe_block";

  private ModBlocks() {}

  // From 1.21.2 a block and an item must know their registry key before they are made.
  // Pin 1.21.1 replaces this file: that line has no setId.
  public static Block createProbeBlock() {
    return new Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ModIds.id(PROBE_BLOCK)))
            .strength(1.0f));
  }

  public static Item createProbeBlockItem(Block block) {
    return new BlockItem(
        block,
        new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ModIds.id(PROBE_BLOCK))));
  }
}

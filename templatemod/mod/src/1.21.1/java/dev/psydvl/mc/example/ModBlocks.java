package dev.psydvl.mc.example;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Blocks of the mod and their items. The loader entries register them. Pin 1.21.1: no registry key
 * in the properties.
 */
public final class ModBlocks {
  // serverSmoke puts this block into the world and sends its chunk again.
  public static final String PROBE_BLOCK = "probe_block";

  private ModBlocks() {}

  public static Block createProbeBlock() {
    return new Block(BlockBehaviour.Properties.of().strength(1.0f));
  }

  public static Item createProbeBlockItem(Block block) {
    return new BlockItem(block, new Item.Properties());
  }
}

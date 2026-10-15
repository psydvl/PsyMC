package dev.psydvl.mc.example.fabric;

import dev.psydvl.mc.example.ExampleMod;
import dev.psydvl.mc.example.ModBlocks;
import dev.psydvl.mc.example.ModIds;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** Fabric entry. Fabric Loader calls it on both sides. */
public final class ExampleModFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    ExampleMod.init();
    // Fabric API keeps the built-in registries open during mod setup.
    Block probe =
        Registry.register(
            BuiltInRegistries.BLOCK,
            ModIds.id(ModBlocks.PROBE_BLOCK),
            ModBlocks.createProbeBlock());
    Registry.register(
        BuiltInRegistries.ITEM,
        ModIds.id(ModBlocks.PROBE_BLOCK),
        ModBlocks.createProbeBlockItem(probe));
    ServerLifecycleEvents.SERVER_STARTED.register(ExampleMod::onServerStarted);
  }
}

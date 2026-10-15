package dev.psydvl.mc.example.neoforge;

import dev.psydvl.mc.example.ExampleMod;
import dev.psydvl.mc.example.ModBlocks;
import dev.psydvl.mc.example.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge entry. FML builds this class during mod construction. */
@Mod(ExampleMod.MOD_ID)
public final class ExampleModNeoForge {
  // RegisterEvent comes for the blocks before the items. The item reads the block from here.
  private static Block probeBlock;

  public ExampleModNeoForge(IEventBus modBus) {
    ExampleMod.init();
    // NeoForge freezes the registries. Register in RegisterEvent on the mod bus.
    modBus.addListener(
        RegisterEvent.class,
        event -> {
          event.register(
              Registries.BLOCK,
              ModIds.id(ModBlocks.PROBE_BLOCK),
              () -> probeBlock = ModBlocks.createProbeBlock());
          event.register(
              Registries.ITEM,
              ModIds.id(ModBlocks.PROBE_BLOCK),
              () -> ModBlocks.createProbeBlockItem(probeBlock));
        });
    NeoForge.EVENT_BUS.addListener(
        ServerStartedEvent.class, event -> ExampleMod.onServerStarted(event.getServer()));
  }
}

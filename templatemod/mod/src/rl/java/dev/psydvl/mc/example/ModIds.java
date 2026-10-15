package dev.psydvl.mc.example;

import net.minecraft.resources.ResourceLocation;

/** Resource ids of the mod. Line rl: Minecraft names the id type ResourceLocation. */
public final class ModIds {
  private ModIds() {}

  // tryBuild is on every release of this line, 1.20.5 included.
  // fromNamespaceAndPath starts on 1.21.
  public static ResourceLocation id(String path) {
    return ResourceLocation.tryBuild(ExampleMod.MOD_ID, path);
  }
}

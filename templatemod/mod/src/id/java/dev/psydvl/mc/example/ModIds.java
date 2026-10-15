package dev.psydvl.mc.example;

import net.minecraft.resources.Identifier;

/** Resource ids of the mod. Line id: from 1.21.11 Minecraft names the id type Identifier. */
public final class ModIds {
  private ModIds() {}

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(ExampleMod.MOD_ID, path);
  }
}

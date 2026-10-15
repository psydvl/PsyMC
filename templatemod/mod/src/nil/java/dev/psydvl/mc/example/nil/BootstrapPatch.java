package dev.psydvl.mc.example.nil;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

/** Set up the mod after the game registries are ready, on both sides. */
@Patch.Class("net.minecraft.server.Bootstrap")
public final class BootstrapPatch extends MiniTransformer {
  @Patch.Method("bootStrap()V")
  public void patchBootStrap(PatchContext ctx) {
    ctx.jumpToLastReturn();
    ctx.add(INVOKESTATIC("dev/psydvl/mc/example/nil/NilHooks", "onBootstrap", "()V"));
  }
}

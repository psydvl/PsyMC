package dev.psydvl.mc.example.nil;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

/** The same hook as TitleScreenMixin of the other loaders. */
@Patch.Class("net.minecraft.client.gui.screens.TitleScreen")
public final class TitleScreenPatch extends MiniTransformer {
  @Patch.Method("init()V")
  public void patchInit(PatchContext ctx) {
    ctx.jumpToStart();
    ctx.add(INVOKESTATIC("dev/psydvl/mc/example/nil/NilHooks", "onTitleScreen", "()V"));
  }
}

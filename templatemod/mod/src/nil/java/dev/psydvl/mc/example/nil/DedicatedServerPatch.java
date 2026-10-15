package dev.psydvl.mc.example.nil;

import nilloader.api.lib.mini.MiniTransformer;
import nilloader.api.lib.mini.PatchContext;
import nilloader.api.lib.mini.annotation.Patch;

/** Tell the mod that the dedicated server is ready. initServer returns true at its end. */
@Patch.Class("net.minecraft.server.dedicated.DedicatedServer")
public final class DedicatedServerPatch extends MiniTransformer {
  @Patch.Method("initServer()Z")
  public void patchInitServer(PatchContext ctx) {
    // ProGuard cuts the stack map frames of the obfuscated releases: at the last return, the frame
    // has no locals, not even this. So keep the server at the start, where this is always there.
    ctx.jumpToStart();
    ctx.add(
        ALOAD(0),
        INVOKESTATIC(
            "dev/psydvl/mc/example/nil/NilHooks", "rememberServer", "(Ljava/lang/Object;)V"));
    ctx.jumpToLastReturn();
    ctx.add(INVOKESTATIC("dev/psydvl/mc/example/nil/NilHooks", "onServerStarted", "()V"));
  }
}

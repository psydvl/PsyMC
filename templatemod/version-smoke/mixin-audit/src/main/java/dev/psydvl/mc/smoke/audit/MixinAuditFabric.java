package dev.psydvl.mc.smoke.audit;

import net.fabricmc.api.ClientModInitializer;

// Fabric calls this entrypoint in the Minecraft constructor.
public final class MixinAuditFabric implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    MixinAudit.run(MixinAuditFabric.class.getClassLoader());
  }
}

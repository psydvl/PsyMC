package dev.psydvl.mc.smoke.audit;

import net.neoforged.fml.common.Mod;

// NeoForge builds this class during mod construction.
@Mod("mixin_audit")
public final class MixinAuditNeo {
  public MixinAuditNeo() {
    MixinAudit.run(MixinAuditNeo.class.getClassLoader());
  }
}

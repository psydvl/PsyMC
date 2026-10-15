package dev.psydvl.mc.example.mixinplugin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The config plugin of examplemod.mixins.json. A mixin for part of the releases is not in the
 * config: Mixin does not call shouldApplyMixin for the mixins that getMixins returns, so getMixins
 * filters them through the gate itself.
 */
public final class ExampleMixinPlugin implements IMixinConfigPlugin {
  // Mixins (relative to the config package) that apply only on part of the releases.
  private static final List<String> PART_OF_RELEASES = List.of("GatedTitleScreenMixin");

  @Override
  public void onLoad(String mixinPackage) {}

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    return MixinGate.allow(mixinClassName, MixinGate.currentRelease());
  }

  @Override
  public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

  @Override
  public List<String> getMixins() {
    String release = MixinGate.currentRelease();
    return PART_OF_RELEASES.stream().filter(name -> MixinGate.allow(name, release)).toList();
  }

  @Override
  public void preApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

  @Override
  public void postApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

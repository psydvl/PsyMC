package dev.psydvl.mc.example.mixin;

import dev.psydvl.mc.example.ExampleMod;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An example of a mixin for part of the pin line: MixinGate turns it on from 1.21 (off on 1.20.5
 * and 1.20.6). ExampleMixinPlugin.getMixins adds it; the config does not list it.
 */
@Mixin(TitleScreen.class)
abstract class GatedTitleScreenMixin {
  @Inject(method = "init", at = @At("HEAD"))
  private void examplemod$onGatedInit(CallbackInfo info) {
    ExampleMod.LOGGER.info("[{}] gated mixin applies", ExampleMod.MOD_ID);
  }
}

package dev.psydvl.mc.example.mixin;

import dev.psydvl.mc.example.ExampleMod;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
abstract class TitleScreenMixin {
  @Inject(method = "init", at = @At("HEAD"))
  private void examplemod$onInit(CallbackInfo info) {
    ExampleMod.onTitleScreen();
  }
}

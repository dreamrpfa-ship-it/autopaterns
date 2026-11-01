package fr.autopaterns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.autopaterns.core.AutoPaternsClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Crochet de début d'image, avant la mise à jour de la caméra : la rotation rejouée est appliquée
 * à l'image en cours (2.x l'appliquait en fin de rendu, donc avec une image de retard).
 * Optionnel (require = 0) : sans lui, le secours LevelRenderEvents.END_MAIN est utilisé.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "update", at = @At("HEAD"), require = 0)
	private void autopaterns$preFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
		AutoPaternsClient.onPreFrame();
	}
}

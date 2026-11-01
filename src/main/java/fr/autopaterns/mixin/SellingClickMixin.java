package fr.autopaterns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.autopaterns.selling.SellingService;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Relève les clics dans les menus pendant une capture de vente / corbeille (ex-ClickSlotMixin 2.x).
 * Injecté en tête : l'état du menu lu par le relevé est celui d'avant le clic. Optionnel (require = 0) :
 * sans lui, la capture détecte l'absence de clic relevé et s'arrête avec un message explicite.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class SellingClickMixin {
	@Inject(method = "handleContainerInput", at = @At("HEAD"), require = 0)
	private void autopaterns$onContainerInput(int containerId, int slotNum, int buttonNum, ContainerInput input,
											  Player player, CallbackInfo ci) {
		SellingService.onContainerInput(containerId, slotNum, buttonNum, input, player);
	}
}

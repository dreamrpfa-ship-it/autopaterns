package fr.autopaterns.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fr.autopaterns.waypoints.WaypointsModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Observation des interactions du joueur avec les blocs pour les patterns de waypoints (2.x :
 * {@code WaypointInteractionMixin}, mêmes points d'injection). Rien n'est annulé ni modifié.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class WaypointsInteractionMixin {
	@Inject(method = "destroyBlock", at = @At("HEAD"), require = 0)
	private void autopaterns$wpBreakPre(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		WaypointsModule.hookBreakPre(pos);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"), require = 0)
	private void autopaterns$wpBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (Boolean.TRUE.equals(cir.getReturnValue())) WaypointsModule.hookBlockBroken(pos);
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), require = 0)
	private void autopaterns$wpAttack(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		WaypointsModule.hookAttack(pos);
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), require = 0)
	private void autopaterns$wpMining(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		WaypointsModule.hookAttack(pos);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
	private void autopaterns$wpUsePre(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
									 CallbackInfoReturnable<InteractionResult> cir) {
		WaypointsModule.hookUsePre(player, hand, hit);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
	private void autopaterns$wpUsePost(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
									  CallbackInfoReturnable<InteractionResult> cir) {
		WaypointsModule.hookUsePost();
	}
}

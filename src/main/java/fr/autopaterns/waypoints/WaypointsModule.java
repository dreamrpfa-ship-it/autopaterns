package fr.autopaterns.waypoints;

import java.util.Properties;

import fr.autopaterns.core.ClientModule;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Module WAYPOINTS : enregistre certaines actions du joueur sur les blocs sous forme de marqueurs (patterns de
 * waypoints), les affiche dans le monde et sur le HUD, et surligne des types de blocs choisis (surbrillance).
 * Comportement 2.x conservé : le module observe et affiche, il ne déplace jamais le joueur et ne simule aucune entrée.
 * Raccourcis gérés ici : {@link KeyDef#WP_RECORD} (F12), {@link KeyDef#WP_MENU} (J), {@link KeyDef#FINDER_SELECT} (N).
 * Données : {@code autopaterns/waypoints.txt} (format 2.x), exports dans {@code autopaterns/waypoints-export}.
 */
public final class WaypointsModule implements ClientModule {
	/** Instance courante, pour les crochets du mixin d'interaction (aucun accès au jeu ici). */
	private static volatile WaypointsModule current;

	private final BlockFinder finder = new BlockFinder();
	private final Waypoints state = new Waypoints(finder);
	private final WaypointRenderer renderer = new WaypointRenderer(this);

	public WaypointsModule() {
		current = this;
	}

	@Override
	public String name() {
		return "waypoints";
	}

	// ------------------------------------------------------------------ API publique (contrat)

	/** Un pattern de waypoints est en enregistrement (indicateur ON/OFF du menu principal). */
	public boolean isRecording() {
		return state.active() != null;
	}

	/** Nombre de blocs en surbrillance (0 si la surbrillance est désactivée). */
	public int highlightCount() {
		return finder.resultCount();
	}

	public Waypoints state() {
		return state;
	}

	public BlockFinder finder() {
		return finder;
	}

	// ------------------------------------------------------------------ cycle de vie

	@Override
	public void onInit() {
		renderer.register();
	}

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		state.ensureLoaded();
	}

	@Override
	public void onEndTick(Minecraft mc) {
		WpSafe.run("raccourcis des waypoints", () -> hotkeys(mc));
		WpSafe.run("capture des waypoints", () -> state.onEndTick(mc));
		WpSafe.run("surbrillance de blocs", () -> finder.onEndTick(mc, state));
	}

	private void hotkeys(Minecraft mc) {
		if (Keys.poll(KeyDef.WP_MENU) && mc.gui.screen() == null) {
			mc.gui.setScreen(new WaypointsScreen(null));
		}
		if (Keys.poll(KeyDef.WP_RECORD)) state.toggle();
		if (Keys.poll(KeyDef.FINDER_SELECT)) finder.toggleAimed(mc, state);
	}

	@Override
	public void onHalt(Minecraft mc) {
		state.halt();
		finder.cancelAll();
		state.saveNow();
	}

	@Override
	public void onStopping(Minecraft mc) {
		state.saveNow();
	}

	// ------------------------------------------------------------------ crochets (mixin WaypointsInteractionMixin)

	/** HEAD de {@code MultiPlayerGameMode#destroyBlock}. */
	public static void hookBreakPre(BlockPos pos) {
		WaypointsModule m = current;
		if (m != null) WpSafe.run("minage (avant)", () -> m.state.onBreakPre(pos));
	}

	/** RETURN de {@code destroyBlock}, seulement si le bloc a été cassé côté client. */
	public static void hookBlockBroken(BlockPos pos) {
		WaypointsModule m = current;
		if (m != null) WpSafe.run("minage", () -> m.state.onBlockBroken(pos));
	}

	/** HEAD de {@code startDestroyBlock} et {@code continueDestroyBlock}. */
	public static void hookAttack(BlockPos pos) {
		WaypointsModule m = current;
		if (m != null) WpSafe.run("frappe d'un bloc", () -> m.state.onAttack(pos));
	}

	/** HEAD de {@code useItemOn}. */
	public static void hookUsePre(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
		WaypointsModule m = current;
		if (m != null) WpSafe.run("clic sur un bloc (avant)", () -> m.state.onInteractPre(player, hand, hit));
	}

	/** RETURN de {@code useItemOn}. */
	public static void hookUsePost() {
		WaypointsModule m = current;
		if (m != null) WpSafe.run("clic sur un bloc", () -> m.state.onInteractPost());
	}
}

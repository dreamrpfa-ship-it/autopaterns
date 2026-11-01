package fr.autopaterns.fishing;

import java.util.Properties;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.input.InputSim;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.selling.SellOrigin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Pêche automatique (module). Indépendante des patterns : sans pattern chargé, pas de corbeille/vente
 * entre deux prises, la pêche continue. Démarrage par l'écran, le menu principal ou le raccourci (K,
 * via {@link Automation#toggleFishing}).
 */
public final class FishingService implements ClientModule {
	private final FishingBot bot = new FishingBot();

	public FishingService() {
		// aucun accès au jeu ici
	}

	@Override
	public String name() {
		return "pêche";
	}

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		bot.load(config);
	}

	@Override
	public void saveConfig(Properties config) {
		bot.save(config);
	}

	@Override
	public void onHalt(Minecraft mc) {
		stop(mc);
	}

	@Override
	public void onStopping(Minecraft mc) {
		bot.stop();
		FishFiles.shutdown();
	}

	/** START tick, phase FISHING. */
	public void step(Minecraft mc, LocalPlayer p) {
		KeyMapping use = mc.options.keyUse;
		if (mc.gui.screen() != null) { // écran ouvert : pause, sans tick du robot
			bot.pause();
			InputSim.setKey(use, false);
			return;
		}
		FishingBot.Status st = bot.tick(mc, p, use, () -> InputSim.addClicks(use, 1));
		AutoPaterns ap = AutoPaterns.get();
		if (st == FishingBot.Status.FAILED) {
			ap.automation().stopAll(mc, Texts.tr("autopaterns.fishing.stopped", bot.failure()));
		} else if (st == FishingBot.Status.CLEAN && ap.patterns().hasPattern()) {
			ap.selling().autoClean(mc, p, true, SellOrigin.FISHING); // démarre éventuellement un job SELLING
		}
	}

	/** Démarrage : refus « ✖ … » ou succès « ✔ Pêche lancée. ». */
	public String start(Minecraft mc) {
		Automation a = AutoPaterns.get().automation();
		LocalPlayer p = mc.player;
		if (a.isBusy()) return Texts.tr("autopaterns.fishing.refuse.busy", Keys.label(KeyDef.STOP));
		if (p == null) return Texts.tr("autopaterns.fishing.refuse.world");
		if (!FishingBot.holdsRod(p)) return Texts.tr("autopaterns.fishing.refuse.rod");
		mc.gui.setScreen(null);
		InputSim.releaseAll(mc);
		bot.start();
		a.setPhase(Phase.FISHING);
		Notifier.hud(Texts.tr("autopaterns.fishing.started", Keys.label(KeyDef.STOP)));
		return Texts.tr("autopaterns.fishing.startOk");
	}

	/** Reprise après une vente / corbeille d'origine pêche (la phase est remise à FISHING par la vente). */
	public void resume() {
		bot.resume();
	}

	/** Arrêt complet : captures annulées (tampons libérés), touche « utiliser » relâchée. */
	public void stop(Minecraft mc) {
		bot.stop();
		if (mc != null && mc.options != null) InputSim.setKey(mc.options.keyUse, false);
	}

	/** Texte du HUD. */
	public String detail() {
		return bot.detail();
	}

	public int caught() {
		return bot.catches();
	}

	public int missed() {
		return bot.misses();
	}

	/** Réglages et état détaillé (écran de la pêche). */
	FishingBot bot() {
		return bot;
	}
}

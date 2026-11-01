package fr.autopaterns.hud;

import java.util.List;

import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import net.minecraft.world.item.ItemStack;

/**
 * Instantané immuable de ce que montre le HUD (équivalent de {@code Hud$State} 2.x : les 23 composants dans le même
 * ordre), complété en 3.0 par le compteur de pêche ({@code fishingShown}, {@code fishCaught}, {@code fishMissed}).
 */
public record HudState(
	boolean active,
	String label,
	int accent,
	String pattern,
	String detail,
	int current,
	int total,
	int repetition,
	boolean loop,
	double deviation,
	double maxDeviation,
	boolean deviationWarn,
	boolean playing,
	int checkpoints,
	int repairs,
	int refills,
	int sales,
	int trash,
	int freeSlots,
	List<ItemStack> loot,
	List<Integer> lootCounts,
	String toast,
	int toastAccent,
	boolean fishingShown,
	int fishCaught,
	int fishMissed) {

	public static final int C_LOOP = 0xFF9D8CF8;

	/** État d'exemple de l'éditeur (2.x {@code Hud.sample}) : butin réel, reste fictif. */
	public static HudState sample(List<ItemStack> loot, List<Integer> lootCounts) {
		return new HudState(true, Texts.tr("autopaterns.hud.phase.loop"), C_LOOP, "Mine",
			Texts.tr("autopaterns.hud.sample.detail"), 1640, 5460, 3, true, 0.004, 0.021, false, true,
			2, 1, 0, 1, 1, 7, loot, lootCounts, Texts.tr("autopaterns.hud.sample.toast"), Notifier.ACCENT_GOOD,
			true, 12, 2);
	}
}

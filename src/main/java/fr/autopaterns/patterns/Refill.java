package fr.autopaterns.patterns;

import java.util.Arrays;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Notifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Remplacement automatique des items de la barre d'action (2.x §14.1) : si l'item de référence d'un emplacement
 * coché a disparu (cassé, épuisé), le premier item identique de l'inventaire (cases 9 à 35) est échangé avec lui.
 */
final class Refill {
	private static final int COOLDOWN_TICKS = 5;
	private final int[] cooldown = new int[9];

	void reset() {
		Arrays.fill(cooldown, 0);
	}

	/** @return false si la lecture a été arrêtée (plus d'item de remplacement). */
	boolean run(Minecraft mc, LocalPlayer p, PatternOptions o, Diagnostics diag) {
		if (o == null || !o.anyRefill() || mc.gameMode == null) return true;
		Inventory inv = p.getInventory();
		for (int i = 0; i < 9; i++) {
			String id = o.slotItems[i];
			if (!o.refill[i] || id == null) continue;
			if (cooldown[i] > 0) {
				cooldown[i]--;
				continue;
			}
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && id.equals(PatternOptions.itemId(st))) continue;
			int src = -1;
			for (int j = 9; j <= 35; j++) {
				ItemStack c = inv.getItem(j);
				if (!c.isEmpty() && id.equals(PatternOptions.itemId(c))) {
					src = j;
					break;
				}
			}
			if (src < 0) {
				AutoPaterns.get().automation().errorStop(mc, Msg.t("refill.missing", PatternOptions.shortName(id), i + 1));
				return false;
			}
			mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, src, i, ContainerInput.SWAP, p);
			cooldown[i] = COOLDOWN_TICKS;
			diag.refills++;
			Notifier.hud(Msg.t("refill.done", PatternOptions.shortName(id), i + 1));
		}
		return true;
	}
}

package fr.autopaterns.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Canal unique des notifications du mod (équivalent de {@code hud(client, msg)} en 2.x).
 * <ul>
 *   <li>Le message devient la notification courante, affichée 5 s par le module NOTIF du HUD.</li>
 *   <li>Il est journalisé (sans codes §).</li>
 *   <li>Si le HUD du mod est indisponible, il part dans la barre d'action du jeu.</li>
 *   <li>Les écrans du mod affichent aussi les notifications récentes (coin bas-droit).</li>
 * </ul>
 * Couleur d'accent déduite du préfixe : §c rouge, §e orange, §a vert, sinon bleu (comme en 2.x).
 * Doit être appelé sur le thread client.
 */
public final class Notifier {
	public static final long TOAST_NANOS = 5_000_000_000L;

	public static final int ACCENT_INFO = 0xFF4C9AFF;
	public static final int ACCENT_BAD = 0xFFE5484D;
	public static final int ACCENT_WARN = 0xFFF5A524;
	public static final int ACCENT_GOOD = 0xFF46C37B;

	/** Une notification horodatée. */
	public record Entry(String text, int accent, long at) {}

	private static String toast;
	private static long toastUntil;
	private static boolean hudOk;
	private static final Deque<Entry> RECENT = new ArrayDeque<>();

	private Notifier() {}

	/** Le HUD du mod a été enregistré auprès de Fabric. */
	public static void setHudOk(boolean ok) {
		hudOk = ok;
	}

	public static boolean hudOk() {
		return hudOk;
	}

	/** Notifie l'utilisateur (toast HUD 5 s + journal ; barre d'action si HUD indisponible). */
	public static void hud(String msg) {
		if (msg == null || msg.isEmpty()) return;
		long now = System.nanoTime();
		toast = msg;
		toastUntil = now + TOAST_NANOS;
		synchronized (RECENT) {
			RECENT.addLast(new Entry(msg, accentOf(msg), now));
			while (RECENT.size() > 6) RECENT.removeFirst();
		}
		Log.info(msg);
		if (!hudOk) overlay(msg);
	}

	/** Statut silencieux dans la barre d'action (sans toast ni journal), utilisé si le HUD est indisponible. */
	public static void overlay(String msg) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.gui != null && mc.gui.hud != null) {
				mc.gui.hud.setOverlayMessage(Component.literal("§3[AutoPaterns] §r" + msg), false);
			}
		} catch (RuntimeException | LinkageError e) {
			// barre d'action indisponible : le message reste dans le journal
		}
	}

	/** Notification courante (null si expirée). */
	public static String activeToast() {
		return (toast != null && System.nanoTime() < toastUntil) ? toast : null;
	}

	public static int activeAccent() {
		return accentOf(activeToast());
	}

	public static int accentOf(String msg) {
		if (msg == null) return ACCENT_INFO;
		if (msg.startsWith("§c") || msg.startsWith("✖")) return ACCENT_BAD;
		if (msg.startsWith("§e")) return ACCENT_WARN;
		if (msg.startsWith("§a") || msg.startsWith("✔")) return ACCENT_GOOD;
		return ACCENT_INFO;
	}

	/** Notifications émises après {@code sinceNanos} (pour les écrans du mod). */
	public static List<Entry> recentSince(long sinceNanos) {
		List<Entry> out = new ArrayList<>();
		synchronized (RECENT) {
			for (Entry e : RECENT) if (e.at() >= sinceNanos) out.add(e);
		}
		return out;
	}
}

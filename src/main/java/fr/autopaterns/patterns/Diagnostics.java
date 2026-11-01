package fr.autopaterns.patterns;

import java.util.Locale;

import fr.autopaterns.core.Texts;
import net.minecraft.client.player.LocalPlayer;

/** Compteurs de la répétition en cours (fluidité, corrections serveur, recalages, réparations…), 2.x §16. */
final class Diagnostics {
	private static final long SLOW_FRAME_NANOS = 25_000_000L;

	int checkpoints, repairs, walkBacks, refills;
	/** Compteurs de ventes / corbeilles du module vente au début de la répétition. */
	int salesBase, trashBase;
	private int frames, slowFrames, corrections;
	private long lastFrameNanos, maxFrameNanos, frameNanosSum;
	private double maxCorrection;
	private double endX, endY, endZ;
	private boolean endValid;

	/** Début de répétition : tout est remis à zéro. */
	void reset(int salesNow, int trashNow) {
		checkpoints = repairs = walkBacks = refills = 0;
		salesBase = salesNow;
		trashBase = trashNow;
		frames = slowFrames = corrections = 0;
		lastFrameNanos = maxFrameNanos = frameNanosSum = 0L;
		maxCorrection = 0;
		endValid = false;
	}

	/** Chaque image en lecture. */
	void measureFrame() {
		long now = System.nanoTime();
		if (lastFrameNanos != 0L) {
			long dt = now - lastFrameNanos;
			frames++;
			frameNanosSum += dt;
			maxFrameNanos = Math.max(maxFrameNanos, dt);
			if (dt > SLOW_FRAME_NANOS) slowFrames++;
		}
		lastFrameNanos = now;
	}

	/** Fin de tick en lecture : position de référence. */
	void markEnd(LocalPlayer p) {
		endX = p.getX();
		endY = p.getY();
		endZ = p.getZ();
		endValid = true;
	}

	/** Début de tick en lecture : la position a-t-elle été corrigée (serveur) depuis la fin du tick précédent ? */
	void detectCorrection(LocalPlayer p) {
		if (!endValid) return;
		double dx = p.getX() - endX, dy = p.getY() - endY, dz = p.getZ() - endZ;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d > 1.0E-6) {
			corrections++;
			maxCorrection = Math.max(maxCorrection, d);
		}
	}

	/** Invalide la position de référence (téléportation volontaire : recalage, retour à pied…). */
	void invalidateEnd() {
		endValid = false;
	}

	String summary(int salesNow, int trashNow) {
		double avgMs = frames == 0 ? 0 : frameNanosSum / 1e6 / frames;
		String fps = avgMs > 0 ? String.format(Locale.FRANCE, "%.0f", 1000 / avgMs) : "?";
		String slowest = String.format(Locale.FRANCE, "%.1f", maxFrameNanos / 1e6);
		String corr = corrections > 0 ? Msg.t("diag.corrMax", Texts.fmt(maxCorrection)) : "";
		return Msg.t("diag.summary", fps, slowest, slowFrames, corrections, corr, checkpoints, repairs, refills,
			Math.max(0, salesNow - salesBase), Math.max(0, trashNow - trashBase), walkBacks);
	}
}

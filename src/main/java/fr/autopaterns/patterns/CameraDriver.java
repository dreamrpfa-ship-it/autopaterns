package fr.autopaterns.patterns;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Lissage de la caméra pendant la lecture (2.x §12) : en lecture, échantillonnage de la piste enregistrée
 * (ou interpolation linéaire entre ticks) ; en recalage / réparation, interpolation entre la rotation
 * précédente et celle posée au tick. Utilise directement le {@code DeltaTracker} et les champs publics
 * {@code yRotO / xRotO} (2.x passait par la réflexion et des MethodHandle).
 */
final class CameraDriver {
	static final double MAX_LOOKAHEAD = 0.5;

	final float[] out = new float[2];
	private float yawA, yawB, pitchA, pitchB;
	private double frameTicksEma = -1.0;

	/** Pose une rotation au tick et mémorise la précédente pour l'interpolation par image. */
	void applyRotation(LocalPlayer p, float yaw, float pitch) {
		float oldB = yawB;
		yawA = yaw + PlayerOps.wrapDegrees(oldB - yaw);
		pitchA = pitchB;
		yawB = yaw;
		pitchB = pitch;
		p.setYRot(yaw);
		p.setXRot(pitch);
	}

	void reset(LocalPlayer p) {
		yawA = yawB = p.getYRot();
		pitchA = pitchB = p.getXRot();
	}

	/** Progression du tick en cours (0..1). */
	static float tickProgress(Minecraft mc) {
		float t = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		if (!(t >= 0f)) return 0f;
		return Math.min(1f, t);
	}

	/** Durée moyenne (lissée) d'une image, en ticks. */
	double frameTicks(Minecraft mc) {
		double d = mc.getDeltaTracker().getGameTimeDeltaTicks();
		if (!(d > 0 && d < 5)) return Math.max(0, frameTicksEma);
		frameTicksEma = frameTicksEma < 0 ? d : frameTicksEma + 0.15 * (d - frameTicksEma);
		return frameTicksEma;
	}

	/** Recalage / réparation : interpolation entre la rotation précédente et celle du tick. */
	void interpolate(double tp, double lookahead) {
		float u = (float) Math.min(1.0, tp + lookahead);
		out[0] = yawA + (yawB - yawA) * u;
		out[1] = pitchA + (pitchB - pitchA) * u;
	}

	/** Lecture : orientation enregistrée à l'instant {@code playedTick + tp + lookahead} (au plus le tick suivant). */
	void playing(List<TickFrame> frames, CameraTrack track, int playedTick, double tp, double lookahead) {
		if (frames == null || frames.isEmpty()) return;
		int i = Math.max(0, Math.min(playedTick, frames.size() - 1));
		double t = Math.min(i + tp + lookahead, i + 1.0);
		if (track != null) {
			track.sample(t, out);
		} else {
			TickFrame a = frames.get(i);
			TickFrame b = frames.get(Math.min(i + 1, frames.size() - 1));
			float u = (float) (t - i);
			out[0] = a.yaw() + (b.yaw() - a.yaw()) * u;
			out[1] = a.pitch() + (b.pitch() - a.pitch()) * u;
		}
	}

	/** Applique l'orientation calculée et neutralise l'interpolation de rendu vanilla (pas de double lissage). */
	void apply(LocalPlayer p) {
		p.setYRot(out[0]);
		p.setXRot(out[1]);
		p.yRotO = p.getYRot();
		p.xRotO = p.getXRot();
	}
}

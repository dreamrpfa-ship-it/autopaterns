package fr.autopaterns.patterns;

import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.input.InputSim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * Recalage (phase APPROACH, 2.x §10.1-10.2) : marche jusqu'au point de départ (ou de reprise), arrêt, alignement de
 * la caméra, contrôle de hauteur puis placement exact. Pas de saut ni de recherche de chemin (comme en 2.x).
 */
final class Approach {
	static final double SNAP_DISTANCE = 0.12;
	static final double SETTLED_SPEED = 5.0E-4;
	static final double STOP_FACTOR = 1.25;
	static final double NEAR_DISTANCE = 0.8;
	static final float MAX_TURN_PER_TICK = 18.0f;
	static final double MAX_START_DISTANCE = 32.0;
	static final int APPROACH_TIMEOUT_TICKS = 600;
	private static final double MAX_HEIGHT_GAP = 0.6;
	private static final double HEIGHT_WARNING = 0.01;
	private static final float ANGLE_TOLERANCE = 0.01f;
	private static final float WALK_CONE = 45.0f;
	private static final double SNEAK_DISTANCE = 2.0;
	private static final double SIDE_MARGIN = 0.35;

	private final Playback pb;
	/** Frame visée (0 = départ d'une répétition). */
	int index;
	/** Vrai = reprise en cours de pattern (retour à pied, après une vente) au lieu d'un départ de répétition. */
	boolean resume;
	private int ticks;
	private int settleTicks;
	private double lastX, lastZ;

	Approach(Playback pb) {
		this.pb = pb;
	}

	/** Passe en APPROACH ({@link #index} et {@link #resume} sont positionnés par l'appelant). */
	void begin(LocalPlayer p) {
		AutoPaterns.get().automation().setPhase(Phase.APPROACH);
		ticks = 0;
		settleTicks = 0;
		lastX = p.getX();
		lastZ = p.getZ();
		pb.camera.reset(p);
		pb.diag.invalidateEnd();
	}

	/** Distance horizontale au point visé (celui de la reprise, pas toujours le départ : corrige 2.x). */
	double distance(LocalPlayer p) {
		List<TickFrame> fr = pb.frames();
		if (fr.isEmpty() || p == null) return -1;
		TickFrame f = fr.get(Math.max(0, Math.min(index, fr.size() - 1)));
		return Math.hypot(f.x() - p.getX(), f.z() - p.getZ());
	}

	void step(Minecraft mc, LocalPlayer p) {
		List<TickFrame> fr = pb.frames();
		index = Math.max(0, Math.min(index, fr.size() - 1));
		TickFrame f = fr.get(index);
		double dx = f.x() - p.getX();
		double dz = f.z() - p.getZ();
		double dist = Math.hypot(dx, dz);
		double speed = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
		lastX = p.getX();
		lastZ = p.getZ();
		if (++ticks > APPROACH_TIMEOUT_TICKS) {
			pb.errorStop(mc, Msg.t("approach.timeout", Texts.fmt(dist)));
			return;
		}
		if (dist > MAX_START_DISTANCE) {
			pb.errorStop(mc, Msg.t("approach.far", Texts.fmt(dist)));
			return;
		}
		float yaw = p.getYRot();
		Options o = mc.options;

		// A) arrivé : s'immobiliser, aligner la caméra, contrôler la hauteur, se placer exactement
		if (dist <= SNAP_DISTANCE) {
			InputSim.releaseAll(mc);
			pb.camera.applyRotation(p, PlayerOps.turnToward(yaw, f.yaw(), MAX_TURN_PER_TICK),
				PlayerOps.approachPitch(p.getXRot(), f.pitch(), MAX_TURN_PER_TICK));
			settleTicks = speed < SETTLED_SPEED ? settleTicks + 1 : 0;
			if (settleTicks >= 2 && Math.abs(PlayerOps.wrapDegrees(p.getYRot() - f.yaw())) < ANGLE_TOLERANCE
				&& Math.abs(p.getXRot() - f.pitch()) < ANGLE_TOLERANCE) {
				double dy = p.getY() - f.y();
				if (Math.abs(dy) > MAX_HEIGHT_GAP) {
					pb.errorStop(mc, Msg.t("approach.height", Texts.fmt(dy)));
					return;
				}
				// 2.x avertissait après le placement exact (message jamais affiché) : contrôle avant le placement
				if (!resume && Math.abs(dy) > HEIGHT_WARNING) Notifier.hud(Msg.t("approach.heightWarn", Texts.fmt(dy)));
				if (!pb.safeRestore(mc, p, f, true)) return;
				if (resume) pb.resumeAt(mc, p, index);
				else pb.startRepetition(mc, p);
			}
			return;
		}
		settleTicks = 0;

		// B) assez près pour finir sur l'élan : relâcher
		if (dist <= speed * STOP_FACTOR) {
			InputSim.releaseAll(mc);
			pb.camera.applyRotation(p, dist < NEAR_DISTANCE ? PlayerOps.turnToward(yaw, f.yaw(), MAX_TURN_PER_TICK) : yaw,
				PlayerOps.approachPitch(p.getXRot(), f.pitch(), MAX_TURN_PER_TICK));
			return;
		}

		// C) marcher
		InputSim.setKey(o.keyJump, false);
		InputSim.setKey(o.keySprint, false);
		InputSim.setKey(o.keyAttack, false);
		InputSim.setKey(o.keyUse, false);
		if (dist > NEAR_DISTANCE) {
			float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float ny = PlayerOps.turnToward(yaw, target, MAX_TURN_PER_TICK);
			pb.camera.applyRotation(p, ny, PlayerOps.approachPitch(p.getXRot(), f.pitch(), MAX_TURN_PER_TICK));
			InputSim.setKey(o.keyUp, Math.abs(PlayerOps.wrapDegrees(target - ny)) < WALK_CONE);
			InputSim.setKey(o.keyDown, false);
			InputSim.setKey(o.keyLeft, false);
			InputSim.setKey(o.keyRight, false);
			InputSim.setKey(o.keyShift, dist < SNEAK_DISTANCE);
		} else {
			float ny = PlayerOps.turnToward(yaw, f.yaw(), MAX_TURN_PER_TICK);
			pb.camera.applyRotation(p, ny, PlayerOps.approachPitch(p.getXRot(), f.pitch(), MAX_TURN_PER_TICK));
			double r = Math.toRadians(ny);
			double fw = -dx * Math.sin(r) + dz * Math.cos(r);
			double side = dx * Math.cos(r) + dz * Math.sin(r);
			double thr = SIDE_MARGIN * dist;
			InputSim.setKey(o.keyUp, fw > thr);
			InputSim.setKey(o.keyDown, fw < -thr);
			InputSim.setKey(o.keyLeft, side > thr);
			InputSim.setKey(o.keyRight, side < -thr);
			InputSim.setKey(o.keyShift, true);
		}
	}
}

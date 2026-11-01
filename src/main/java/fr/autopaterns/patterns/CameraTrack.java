package fr.autopaterns.patterns;

import java.util.Arrays;

/**
 * Piste caméra d'un enregistrement : un échantillon (temps en ticks fractionnaires, yaw, pitch) par image rendue.
 * Lecture par spline de Hermite cubique (type Catmull-Rom non uniforme), identique à la 2.x.
 */
public final class CameraTrack {
	private double[] time = new double[8192];
	private float[] yaw = new float[8192];
	private float[] pitch = new float[8192];
	private int size;
	private int cursor;

	public int size() {
		return size;
	}

	public double timeAt(int i) {
		return time[i];
	}

	public float yawAt(int i) {
		return yaw[i];
	}

	public float pitchAt(int i) {
		return pitch[i];
	}

	/** Ajoute un échantillon ; un temps antérieur au dernier est ignoré, un temps égal remplace le dernier. */
	public void add(double t, float y, float p) {
		if (size > 0 && t <= time[size - 1]) {
			if (t == time[size - 1]) {
				yaw[size - 1] = y;
				pitch[size - 1] = p;
			}
			return;
		}
		if (size == time.length) {
			int n = time.length * 2;
			time = Arrays.copyOf(time, n);
			yaw = Arrays.copyOf(yaw, n);
			pitch = Arrays.copyOf(pitch, n);
		}
		time[size] = t;
		yaw[size] = y;
		pitch[size] = p;
		size++;
	}

	public void rewind() {
		cursor = 0;
	}

	/** Orientation à l'instant {@code t} dans {@code out[0]} (yaw) et {@code out[1]} (pitch) ; inchangé si vide. */
	public void sample(double t, float[] out) {
		if (size == 0) return;
		if (size == 1 || t <= time[0]) {
			out[0] = yaw[0];
			out[1] = pitch[0];
			return;
		}
		if (t >= time[size - 1]) {
			out[0] = yaw[size - 1];
			out[1] = pitch[size - 1];
			return;
		}
		int i = cursor;
		if (!(i <= size - 2 && time[i] <= t && (i + 2 >= size || time[i + 2] > t))) {
			int r = Arrays.binarySearch(time, 0, size, t);
			i = r >= 0 ? r : -r - 2;
			i = Math.max(0, Math.min(size - 2, i));
		}
		while (i < size - 2 && time[i + 1] <= t) i++;
		cursor = i;
		out[0] = (float) hermite(yaw, i, t);
		out[1] = (float) hermite(pitch, i, t);
	}

	private double hermite(float[] v, int i, double t) {
		double t0 = time[i];
		double t1 = time[i + 1];
		double h = t1 - t0;
		double p0 = v[i];
		double p1 = v[i + 1];
		double slope = (p1 - p0) / h;
		double m0 = tangent(v, i, slope);
		double m1 = tangent(v, i + 1, slope);
		double s = (t - t0) / h;
		double s2 = s * s;
		double s3 = s2 * s;
		return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * h * m0 + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * h * m1;
	}

	private double tangent(float[] v, int i, double fallback) {
		if (i == 0 || i == size - 1) return fallback;
		double hp = time[i] - time[i - 1];
		double hn = time[i + 1] - time[i];
		double dp = (v[i] - v[i - 1]) / hp;
		double dn = (v[i + 1] - v[i]) / hn;
		return (hn * dp + hp * dn) / (hp + hn);
	}
}

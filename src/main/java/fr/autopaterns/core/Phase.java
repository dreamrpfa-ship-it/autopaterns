package fr.autopaterns.core;

/**
 * État global de l'automate (mêmes états et même ordre qu'en 2.x).
 * Une seule activité automatisée à la fois : patterns, vente/corbeille, capture de vente ou pêche.
 */
public enum Phase {
	IDLE, PREPARING, RECORDING, APPROACH, PLAYING, REPAIR, SELLING, CAPTURE, FISHING;

	/** Lecture de pattern au sens large (touches simulées actives). */
	public boolean isPlayback() {
		return this == APPROACH || this == PLAYING || this == REPAIR;
	}
}

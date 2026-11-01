package fr.autopaterns.selling;

/** Origine d'une vente / corbeille, qui détermine la reprise à la fin du job. */
public enum SellOrigin {
	/** Bouton « Tester » : retour au repos. */
	TEST,
	/** Pendant la lecture : reprise au dernier tick au sol avant le déclenchement. */
	RESUME,
	/** Entre deux répétitions : répétition suivante depuis le départ. */
	NEXT_REP,
	/** Depuis la pêche automatique : la pêche reprend. */
	FISHING
}

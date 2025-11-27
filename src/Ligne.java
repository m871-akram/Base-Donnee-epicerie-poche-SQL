// FICHIER : Ligne.java

/**
 * Objet de données représentant une ligne de commande (un produit commandé).
 * Classe immutable : tous les champs sont "final" (ne peuvent pas être modifiés après création).
 * Pas de logique métier, juste un conteneur de données.
 */
public class Ligne {

    // ID du produit commandé
    public final int idProduit;
    // Quantité commandée (peut être décimale, ex : 1.5 kg)
    public final double quantite;
    // Unité de mesure : "kg" ou "unité"
    public final String unite;
    // NOUVELLE VERSION : modePaiement retiré

    /**
     * Constructeur : initialise tous les champs.
     * Une fois créé, un objet Ligne ne peut plus être modifié (immutable).
     */
    public Ligne(int idProduit, double quantite, String unite) {
        this.idProduit = idProduit;
        this.quantite = quantite;
        this.unite = unite;
        // modePaiement retiré
    }
}
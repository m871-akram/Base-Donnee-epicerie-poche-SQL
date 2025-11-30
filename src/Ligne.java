// FICHIER : Ligne.java

/**
 * Objet de données représentant une ligne de commande (un produit commandé).
 */
public class Ligne {

    // ID du produit commandé
    public final int idProduit;
    // Quantité commandée 
    public final double quantite;
    // Unité de mesure 
    public final String unite;

    // Constructeur : initialise tous les champs.
    public Ligne(int idProduit, double quantite, String unite) {
        this.idProduit = idProduit;
        this.quantite = quantite;
        this.unite = unite;
    }
}
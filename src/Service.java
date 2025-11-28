import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList; 
import java.util.stream.Collectors; 
import java.util.Calendar;
import java.util.Date;

/**
 * Couche métier (business logic).
 * Orchestre les appels au DAO et gère les transactions (commit/rollback).
 */
public class Service {

    // Références vers le DAO (pour les requêtes) et Database (pour commit/rollback)
    private final Dao dao;
    private final Database db;

    /**
     * Classe interne utilisée pour stocker temporairement une ligne de commande 
     * avec les prix calculés avant l'insertion en base.
     */
    private static class LigneAvecPrix {
        public final Ligne ligne;
        public final double prixUnitaire;
        public final double sousTotal;

        public LigneAvecPrix(Ligne ligne, double prixUnitaire, double sousTotal) {
            this.ligne = ligne;
            this.prixUnitaire = prixUnitaire;
            this.sousTotal = sousTotal;
        }
    }

    /**
     * Constructeur : reçoit Database et Dao par injection de dépendance.
     */
    public Service(Database db, Dao dao) {
        this.db = db;
        this.dao = dao;
    }

    // =============================================================
    // FONCTIONNALITÉ F1 : CATALOGUE ET CRÉATION COMMANDE
    // =============================================================

    /**
     * Délègue simplement au DAO (pas de logique métier ici).
     */
    public List<String> getCatalogue() throws Exception {
        return dao.getCatalogue();
    }

    /**
     * Passe une commande complète (transaction multi-étapes) - Fonctionnalité F1.
     * @return Un objet ResultatCommande contenant l'ID de commande et le montant total final.
     */
    public ResultatCommande passerCommande(int idClient, String mode, String modePaiement, List<Ligne> lignes) throws Exception {
        try {
            // Étape 0 : VÉRIFICATION, CALCUL DE PRIX et PRÉPARATION DES DONNÉES
            List<LigneAvecPrix> lignesPretes = new ArrayList<>();
            double montantTotalCommandeValeur = 0.0; 

            // Vérification que la liste de lignes n'est pas vide
            if (lignes.isEmpty()) {
                throw new Exception("La commande ne contient aucun produit.");
            }

            for (Ligne l : lignes) {
                // 0.1. Vérification de la saisonnalité
                if (!dao.estProduitEnSaison(l.idProduit)) {
                    throw new Exception("Le produit ID " + l.idProduit + " n'est pas en saison.");
                }

                // 0.2. Vérification du stock suffisant
                double stockTotal = dao.getStockTotalProduit(l.idProduit);
                if (stockTotal < l.quantite) {
                    throw new Exception("Stock insuffisant pour le produit ID " + l.idProduit + ". Requis: " + l.quantite + ", Disponible: " + stockTotal);
                }
                
                // 0.3. Calculer le prix unitaire (y compris prix réduit F2)
                double prixUnitaire = dao.getPrixVente(l.idProduit);
                double sousTotal = l.quantite * prixUnitaire;
                montantTotalCommandeValeur += sousTotal; // Accumuler le sous-total de la commande
                
                lignesPretes.add(new LigneAvecPrix(l, prixUnitaire, sousTotal));
            }
            
            // Étape 1 : Créer la commande et récupérer son ID
            int idCommande = dao.creerCommande(idClient, modePaiement);

            // Étape 2 : Ajouter chaque ligne avec le prix calculé
            int i = 1;
            for (LigneAvecPrix l : lignesPretes) {
                dao.ajouterLigneCommande(idCommande, l.ligne, i++, l.prixUnitaire, l.sousTotal);
            }

            // Étape 3 : Gérer le mode de récupération et les frais de livraison
            dao.enregistrerModeRecuperation(idCommande, mode);
            
            if (mode.equals("Livraison")) {
                
                // 3.1. Récupérer les données nécessaires au calcul (Appel au DAO)
                String pays = dao.getPaysLivraison(idCommande);
                String ville = dao.getVilleLivraison(idCommande);
                double poidsTotal = dao.calculerPoidsTotalCommande(idCommande);
                
                // 3.2. Calculer les frais et la FDE (Appel aux fonctions Service)
                double fraisLivraison = calculerFraisLivraison(pays, ville, poidsTotal);
                java.util.Date dateEstimee = calculerDateLivraisonEstimee(pays, ville); 
                
                // 3.3. Mettre à jour la ligne LIVRAISON_DOMICILE dans la base (Appel au DAO)
                dao.updateFraisEtDateLivraison(idCommande, fraisLivraison, dateEstimee);
                
                // 3.4. Ajouter les frais au montant total
                montantTotalCommandeValeur += fraisLivraison; 
                
                System.out.println("\n--- Livraison Calculée ---");
                System.out.println("Frais de livraison appliqués: " + String.format("%.2f", fraisLivraison) + "€");
                System.out.println("Date estimée: " + dateEstimee.toString());
                System.out.println("--------------------------\n");
            }
            
            // Étape 4 : GESTION DU STOCK (DÉSTOCKAGE FIFO)
            for (LigneAvecPrix ligne : lignesPretes) {
                double quantiteRequise = ligne.ligne.quantite;

                // 4.1. Récupérer les lots disponibles pour ce produit (triés FIFO/FEFO)
                List<Dao.LotInfo> lots = dao.getStockLotsProduit(ligne.ligne.idProduit);

                // 4.2. Déstockage séquentiel (FIFO)
                double resteADestocker = quantiteRequise;
                for (Dao.LotInfo lot : lots) {
                    if (resteADestocker <= 0) break; // Le besoin est satisfait

                    double quantiteLot = lot.stockActuel;
                    double quantitePrise = Math.min(resteADestocker, quantiteLot);

                    // 4.3. Déduire du lot dans la base
                    dao.updateStockLot(lot.idLot, quantitePrise);

                    resteADestocker -= quantitePrise;
                }
            }
            
            // Étape 5 : La commande reste à 'En preparation' ou 'Prete'.

            // Étape 6 : valider la transaction (tout est OK)
            db.commit();
            
            // Étape 7 : Retourner l'ID et le montant total final
            return new ResultatCommande(idCommande, montantTotalCommandeValeur);

        } catch (Exception e) {
            // En cas d'erreur (vérification échouée ou erreur SQL), annuler tous les changements
            db.rollback();
            // Re-lancer l'exception avec un message clair pour le Menu
            throw new Exception("Échec de la commande : " + e.getMessage()); 
        }
    }


    // =============================================================
    // FONCTIONNALITÉ F2 : ALERTES & AJUSTEMENT PRIX (Transaction)
    // =============================================================

    /**
     * Délègue simplement au DAO pour les alertes (pas de transaction nécessaire).
     */
    public List<String> getAlertes() throws Exception {
        return dao.getAlertes();
    }

    /**
     * Transaction complète pour ajuster les prix des produits proches de péremption (F2).
     * @param pourcentage Le pourcentage de réduction (ex: 30 pour 30%).
     * @return Le nombre de produits dont le prix a été ajusté.
     */
    public int ajusterPrixPeremption(int pourcentage) throws Exception {
        int count = 0;
        try {
            // 1. Récupérer la liste des ID produits concernés (lots expirant à J+7)
            List<Integer> produits = dao.getProduitsAjuster();
            
            // 2. Appliquer la mise à jour pour chaque produit trouvé
            for (int idProduit : produits) {
                dao.updatePrixVrac(idProduit, pourcentage);
                count++;
            }

            // 3. Valider la transaction
            db.commit();
            return count;

        } catch (Exception e) {
            // En cas d'erreur, annuler toutes les mises à jour de prix
            db.rollback();
            throw new Exception("Échec de l'ajustement des prix : " + e.getMessage());
        }
    }

    // =============================================================
    // FONCTIONNALITÉ F3 : CLÔTURE DE COMMANDE (SIMPLIFIÉE)
    // =============================================================
    
    /**
     * Clôture une commande. Cette version met à jour le statut
     * de 'En preparation' à 'Livrée'.
     */
    public void cloturerCommande(int idCommande) throws Exception {
    
    // La transaction est gérée par le Service
    db.setAutoCommit(false); 

    try {
        // 1. Verrouiller la commande et vérifier le statut
        String statutActuel = dao.getStatutEtVerrouillerCommande(idCommande);
        
        if (!"En preparation".equals(statutActuel)) {
            throw new Exception("La commande ID " + idCommande + " est déjà " + statutActuel + ". Ne peut être clôturée.");
        }
        
        // 2. Récupérer les modes de récupération et de paiement
        String[] modes = dao.getModeRecupAndPaiement(idCommande);
        String modeRecuperation = modes[0];
        String modePaiement = modes[1];
        String statutFinal;

        // 3. Logique métier F3
        if ("Retrait".equals(modeRecuperation)) {
            statutFinal = "Recupere";
            
            if ("EN BOUTIQUE".equals(modePaiement)) {
                 // Étape Paiement en boutique : Si un enregistrement est nécessaire
                 System.out.println("Paiement EN BOUTIQUE enregistré.");
            }
        
        } else if ("Livraison".equals(modeRecuperation)) {
            statutFinal = "Livree";
            // NOTE : Les frais ont été calculés et stockés lors de F1.
            System.out.println("Frais de livraison appliqués : Déjà enregistrés lors de la commande."); 
        } else {
            throw new Exception("Mode de récupération non pris en charge.");
        }
        
        // 4. Mettre à jour la date effective de récupération/livraison
        dao.enregistrerDateRecup(idCommande, modeRecuperation);

        // 5. Mettre à jour le statut final
        dao.updateStatutCommande(idCommande, statutFinal);

        // 6. Valider la transaction
        db.commit();
        System.out.println("Clôture de la commande " + idCommande + " réussie. Nouveau statut : " + statutFinal);

    } catch (Exception e) {
        // En cas d'erreur, annuler tout
        db.rollback();
        throw new Exception("Échec de la clôture de la commande ID " + idCommande + " : " + e.getMessage());
    } finally {
        db.setAutoCommit(true); // Rétablir l'auto-commit
    }
}

// =============================================================
// LOGIQUE MÉTIER DE LIVRAISON (Nouvelles fonctions)
// =============================================================

/**
 * Calcule les frais de livraison basés sur le pays, la ville (zone) et le poids.
 */
public double calculerFraisLivraison(String pays, String ville, double poidsTotal) throws Exception {
    double fraisPays;
    double fraisPoids;
    double fraisDistance;

    // 1. Déterminer les frais de base selon le PAYS (Facteur 1)
    if ("FRANCE".equalsIgnoreCase(pays) && !isDOMTOM(ville)) {
        fraisPays = 5.00; // Frais de base Métropole
    } else if (isDOMTOM(ville)) {
        fraisPays = 25.00; // Frais de base DOM-TOM 
    } else {
        fraisPays = 40.00; // Frais International
    }

    // 2. Ajouter un coût basé sur le POIDS (Facteur 2)
    fraisPoids = poidsTotal * 0.5; // 0.5€ par Kg

    // 3. Ajouter un coût basé sur la DISTANCE/ZONE (Facteur 3)
    if ("GRENOBLE".equalsIgnoreCase(ville) || "SAINT-MARTIN-D'HERES".equalsIgnoreCase(ville)) {
        fraisDistance = 0.00; // Zone locale (Isère)
    } else if ("LYON".equalsIgnoreCase(ville)) {
        fraisDistance = 5.00; // Petite distance (Rhône-Alpes)
    } else {
        fraisDistance = 10.00; // Grande distance (hors Rhône-Alpes)
    }
    
    // Total
    return fraisPays + fraisPoids + fraisDistance;
}

/**
 * Calcule la Date de Livraison Estimée (FDE) basée sur le pays (délai de transit).
 */
public java.util.Date calculerDateLivraisonEstimee(String pays, String ville) {
    // Utilise Calendar pour manipuler la date facilement
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTime(new java.util.Date()); // Date de la commande (maintenant)

    int delaiJours = 1; // 1 jour pour préparer la commande
    
    // Délai de transit selon la destination
    if ("FRANCE".equalsIgnoreCase(pays) && !isDOMTOM(ville)) {
        delaiJours += 3; // 3 jours de transit Métropole (total 4 jours)
    } else if (isDOMTOM(ville)) {
        delaiJours += 10; // 10 jours de transit DOM-TOM (total 11 jours)
    } else {
        delaiJours += 15; // 15 jours de transit International (total 16 jours)
    }

    // Ajout du délai total à la date de commande
    cal.add(java.util.Calendar.DAY_OF_YEAR, delaiJours);
    
    return cal.getTime();
}

/**
 * Méthode utilitaire pour vérifier si la ville fait partie des départements ou territoires d'outre-mer.
 */
private boolean isDOMTOM(String ville) {
    if (ville == null) return false;
    String villeMaj = ville.toUpperCase();
    return villeMaj.contains("FORT-DE-FRANCE")  || villeMaj.contains("CAYENNE") || 
           villeMaj.contains("SAINT-DENIS")     || villeMaj.contains("NOUMÉA")  ||
           villeMaj.contains("LES ABYMES")      || villeMaj.contains("PAPEETE") ||
           villeMaj.contains("MAMOUDZOU");
}

// =============================================================
// CLASSE DE RETOUR (AJOUTÉE)
// =============================================================
/**
 * Objet de retour pour la méthode passerCommande, contenant l'ID et le montant final.
 */
public class ResultatCommande {
    public final int idCommande;
    public final double montantTotal;

    public ResultatCommande(int idCommande, double montantTotal) {
        this.idCommande = idCommande;
        this.montantTotal = montantTotal;
    }
}

} // Fin de la classe Service
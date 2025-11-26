import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList; // Ajouté pour la gestion des listes
import java.util.stream.Collectors; // Ajouté pour faciliter certains calculs

/**
 * Couche métier (business logic).
 * Orchestre les appels au DAO et gère les transactions (commit/rollback).
 *  C'EST ICI que se fait la gestion des transactions, PAS dans le DAO.
 */
public class Service {

    // Références vers le DAO (pour les requêtes) et Database (pour commit/rollback)
    private final Dao dao;
    private final Database db;

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
     * Passe une commande complète (transaction multi-étapes).
     */
    public int passerCommande(int idClient, String mode, List<Ligne> lignes) throws Exception {

        try {
            // Étape 1 : créer la commande et récupérer son ID
            int idCommande = dao.creerCommande(idClient);

            // Étape 2 : ajouter chaque ligne (numérotées 1, 2, 3...)
            int i = 1;
            for (Ligne l : lignes) {
                // NOTE: La vérification de prix (F1) devrait se faire ici en vrai,
                // mais pour ce projet, on se concentre sur les transactions F2/F3.
                dao.ajouterLigneCommande(idCommande, l, i++);
            }

            // Étape 3 : enregistrer le mode de récupération
            dao.enregistrerModeRecuperation(idCommande, mode);

            // Étape 4 : valider la transaction (tout est OK)
            db.commit();
            return idCommande;

        } catch (Exception e) {
            // En cas d'erreur, annuler tous les changements
            db.rollback();
            // Lancer l'exception pour que Menu puisse l'afficher
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
    // FONCTIONNALITÉ F3 : CLÔTURE & DÉSTOCKAGE (Transaction)
    // =============================================================
    
    /**
     * Clôture une commande, réalise le déstockage des produits (FIFO) et change le statut.
     * Cette méthode est critique : elle doit être atomique (tout ou rien).
     */
    public void cloturerCommande(int idCommande) throws Exception {
        try {
            // 1. Verrouiller la commande et vérifier le statut
            // SELECT... FOR UPDATE empêche une autre transaction de la modifier.
            String statut = dao.getStatutEtVerrouillerCommande(idCommande);
            
            if (!"En préparation".equals(statut)) {
                throw new Exception("La commande ID " + idCommande + " est déjà " + statut + ". Seules les commandes 'En préparation' peuvent être clôturées.");
            }

            // 2. Récupérer les lignes de la commande à déstocker
            List<Ligne> lignes = dao.getLignesCommande(idCommande);

            // 3. Traiter chaque ligne (Logique de Déstockage FIFO)
            for (Ligne ligne : lignes) {
                double quantiteRequise = ligne.quantite;

                // 3.1. Récupérer les lots disponibles pour ce produit (triés FIFO)
                List<Dao.LotInfo> lots = dao.getStockLotsProduit(ligne.idProduit);

                // Vérification du stock total disponible
                double stockTotal = lots.stream().mapToDouble(lot -> lot.stockActuel).sum();
                if (stockTotal < quantiteRequise) {
                    throw new Exception("Stock insuffisant pour le produit ID " + ligne.idProduit + ". Requis: " + quantiteRequise + " " + ligne.unite + ", Disponible: " + stockTotal + " " + ligne.unite);
                }

                // 3.2. Déstockage séquentiel (FIFO : les lots les plus anciens/proches de péremption sont consommés en premier)
                double resteADestocker = quantiteRequise;
                for (Dao.LotInfo lot : lots) {
                    if (resteADestocker <= 0) break; // Le besoin est satisfait

                    double quantiteLot = lot.stockActuel;
                    // Quantité réelle que nous allons prendre de ce lot
                    double quantitePrise = Math.min(resteADestocker, quantiteLot);

                    // 3.3. Déduire du lot dans la base
                    dao.updateStockLot(lot.idLot, quantitePrise);

                    // Mise à jour de la quantité restante à déstocker pour les lots suivants
                    resteADestocker -= quantitePrise;
                }
            }

            // 4. Mettre à jour le statut de la commande à "Livrée"
            dao.updateStatutCommande(idCommande, "Livrée");

            // 5. Valider la transaction (COMMIT) : Toutes les étapes (verrouillage, déstockage, changement de statut) sont validées ensemble.
            db.commit();

        } catch (Exception e) {
            // Annuler la transaction (ROLLBACK) : si le stock est insuffisant,
            // ou si une erreur SQL se produit, tous les changements sont annulés.
            db.rollback();
            throw new Exception("Échec de la clôture de la commande ID " + idCommande + " : " + e.getMessage());
        }
    }
}
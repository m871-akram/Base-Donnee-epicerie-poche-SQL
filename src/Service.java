import java.sql.*;
import java.util.*;
import java.util.Date;

/** Gère les appels des fonction de dao et gère les transactions*/
public class Service {

    // Références pour les requêtes et vers Database pour commit/rollback
    private final Dao dao;
    private final Database db;

    private final List<Integer> contenantsChoisis = new ArrayList<>();

    /**Classe interne utilisée pour stocker temporairement une ligne de commande 
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

    public Service(Database db, Dao dao) {
        this.db = db;
        this.dao = dao;
    }

    public void ajouterContenantPourCommandeTemp(int idContenant) {
     contenantsChoisis.add(idContenant);
    }



    
    // Gestion du client

    /**  Gère la vérification d'un client existant ou la création d'un nouveau client.
    */
    public int verifierOuCreerClient() throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.print("Êtes-vous un nouveau client ? (O/N) : ");
        boolean nouveau = sc.nextLine().equalsIgnoreCase("O");

        if (!nouveau) {
            System.out.print("ID client : ");
            int idClient = Integer.parseInt(sc.nextLine());

            if (!dao.clientExiste(idClient))
                throw new Exception("Client " + idClient + " inexistant.");

            return idClient;
        }

        System.out.print("Choisissez un ID client : ");
        int idClient = Integer.parseInt(sc.nextLine());

        System.out.print("Voulez-vous créer un compte complet (O) ou anonyme (N) ? ");
        boolean complet = sc.nextLine().equalsIgnoreCase("O");

        try {
            // Ajout des nouveaux données du client
            dao.creerClient(idClient, !complet);
            if (complet) {
                System.out.print("Nom : ");
                String nom = sc.nextLine();
                System.out.print("Prénom : ");
                String prenom = sc.nextLine();
                System.out.print("Email : ");
                String email = sc.nextLine();
                System.out.print("Téléphone : ");
                String tel = sc.nextLine();
                dao.creerInfosClient(idClient, nom, prenom, email, tel);
                int idAdresse = new Random().nextInt(10000);
                System.out.print("Rue : ");
                String rue = sc.nextLine();
                System.out.print("Ville : ");
                String ville = sc.nextLine();
                System.out.print("Pays : ");
                String pays = sc.nextLine();
                //Création de l'adresse du client nécessaire pour la livraison
                dao.creerAdresse(idAdresse, idClient, rue, ville, pays);
            }

            db.commit();
            return idClient;

        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur lors de la création du client : " + e.getMessage());
        }
    }


    // CATALOGUE
    public List<String> getCatalogue() throws Exception {
        return dao.getCatalogue();
    }


    // Fonctionnalité 1: passer la commande
    public ResultatCommande passerCommande(int idClient, String mode, String modePaiement, List<Ligne> lignes) throws Exception {
        try {
            if (lignes.isEmpty())
                throw new Exception("La commande ne contient aucun produit.");
            // Création commande
            int idCommande = dao.creerCommande(idClient, modePaiement);

            int numLigneContenant = 1000;  // évite conflits avec produits
 for (int idContenant : contenantsChoisis) {
     dao.ajouterContenantALaCommande(idCommande, idContenant, numLigneContenant);
     numLigneContenant++;
 }
 contenantsChoisis.clear();
            List<LigneAvecPrix> lignesProduitsPourDestockage = new ArrayList<>();
            double montantTotal = 0;
            int numLigne = 1; 
            //  Préparation et Insertion des lignes
            for (Ligne l : lignes) {
                // Le cas d'un  contenant
                if (l.unite.startsWith("CONTENANT_")) {
                    int idContenant = Integer.parseInt(l.unite.split("_")[1]);
                    dao.ajouterContenantALaCommande(idCommande, idContenant, numLigne);
                    numLigne++; 
                    continue; 
                }

                // Cas d'un produit
                // Vérification du stock et saison
                double stockTotal = dao.getStockTotalProduit(l.idProduit);
                if (stockTotal < l.quantite)
                    throw new Exception("Stock insuffisant pour produit " + l.idProduit);
                if (!dao.estProduitEnSaison(l.idProduit))
                    throw new Exception("Produit " + l.idProduit + " hors saison.");
                // Calcul du prix
                double prixUnitaire = dao.getPrixVente(l.idProduit);
                double sousTotal = prixUnitaire * l.quantite;
                montantTotal += sousTotal;
                // Insertion de la ligne produit
                dao.ajouterLigneCommande(idCommande, l, numLigne, prixUnitaire, sousTotal);
                numLigne++; 
                
                lignesProduitsPourDestockage.add(new LigneAvecPrix(l, prixUnitaire, sousTotal));
            }

            // Mode récupération
            dao.enregistrerModeRecuperation(idCommande, mode);

            // Livraison
            if (mode.equalsIgnoreCase("Livraison")) {
                String pays = dao.getPaysLivraison(idCommande);
                String ville = dao.getVilleLivraison(idCommande);
                double poidsTotal = calculerPoidsTotalCommande(idCommande);
                double fraisLivraison = calculerFraisLivraison(pays, ville, poidsTotal);
                Date dateEstimee = calculerDateLivraisonEstimee(pays, ville);
                dao.updateFraisEtDateLivraison(idCommande, fraisLivraison, dateEstimee);
                montantTotal += fraisLivraison;
            }

            // Déstockage produit 
            for (LigneAvecPrix lp : lignesProduitsPourDestockage) {
                double restant = lp.ligne.quantite;
                List<Dao.LotInfo> lots = dao.getStockLotsProduit(lp.ligne.idProduit);

                for (Dao.LotInfo lot : lots) {
                    if (restant <= 0) break;

         double aPrendre = Math.min(restant, lot.stockActuel);

         // Décrément stock sécurisé
         dao.updateStockLot(lot.idLot, aPrendre);

         restant -= aPrendre;
     }

     if (restant > 0) {
         throw new Exception("Stock insuffisant après verrouillage (concurrence).");
     }
 }
            db.commit();
            return new ResultatCommande(idCommande, montantTotal);

        } catch (Exception e) {
            db.rollback();
            throw new Exception("Échec de commande : " + e.getMessage());
        }
    }

    // Contenants
    public List<String[]> getContenantsCompatibles(double quantite) throws Exception {
        return dao.getContenantsCompatibles(quantite);
    }
    public void ajouterContenantALaCommande(int idCommande, int idContenant, int numLigne) throws Exception {
        dao.ajouterContenantALaCommande(idCommande, idContenant, numLigne);
    }

    // Calculs du poids total pour livraison
    public double calculerPoidsTotalCommande(int idCommande) throws SQLException {
        double poids = 0;
        List<Ligne> lignes = dao.getLignesCommande(idCommande);
        for (Ligne l : lignes) {
            if ("Kg".equalsIgnoreCase(l.unite)) {
     poids += l.quantite;
 } else {
     Double pf = dao.getPoidsFixe(l.idProduit);
     if (pf == null) {
         throw new SQLException("Produit préconditionné sans poids fixe : " + l.idProduit);
     }
     poids += (pf / 1000.0) * l.quantite; // conversion grammes → kg
 }
        }
        return poids;
    }

    public double calculerFraisLivraison(String pays, String ville, double poidsTotal) {
        double fp = ("FRANCE".equalsIgnoreCase(pays) && !isDOMTOM(ville)) ? 5 :
                    (isDOMTOM(ville) ? 25 : 40);

        double fd = (ville.equalsIgnoreCase("GRENOBLE") || ville.equalsIgnoreCase("SAINT-MARTIN-D'HERES")) ? 0 :
                    (ville.equalsIgnoreCase("LYON") ? 5 : 10);

        return fp + fd + 0.5 * poidsTotal;
    }

    public Date calculerDateLivraisonEstimee(String pays, String ville) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());

        int j = ("FRANCE".equalsIgnoreCase(pays) && !isDOMTOM(ville)) ? 4 :
                (isDOMTOM(ville) ? 11 : 16);

        cal.add(Calendar.DAY_OF_YEAR, j);
        return cal.getTime();
    }

    private boolean isDOMTOM(String ville) {
        if (ville == null) return false;
        ville = ville.toUpperCase();
        return ville.contains("CAYENNE") || ville.contains("FORT-DE-FRANCE")
                || ville.contains("PAPEETE") || ville.contains("MAMOUDZOU")
                || ville.contains("LES ABYMES") || ville.contains("SAINT-DENIS")
                || ville.contains("NOUMÉA");
    }

    // Fonctionnalité 2: ajustement des prix

    public List<String> getAlertes() throws Exception {
        return dao.getAlertes();
    }

    public int ajusterPrixPeremption(int pourcentage) throws Exception {
        try {
            List<Integer> produits = dao.getProduitsAjuster();
            int count = 0;

               for (int idP : produits) {
       try {
            dao.updatePrixVrac(idP, pourcentage);
            count++;
        } catch (Exception e1) {
            // si ce n'est pas du vrac → essayer PRECOND
            dao.updatePrixPrecond(idP, pourcentage);
            count++;
        }
    }

            db.commit();
            return count;

        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur F2 : " + e.getMessage());
        }
    }

    // Fonctionnalité 3 : cloture de la commande
    public void cloturerCommande(int idCommande) throws Exception {
        db.setAutoCommit(false);
        try {
            String statut = dao.getStatutEtVerrouillerCommande(idCommande);
            List<String> etatsPossibles = List.of("En preparation", "Prete", "En livraison");
            if (!etatsPossibles.contains(statut)) {
                throw new Exception("Commande non clôturable au statut : " + statut);
            }
            String[] modes = dao.getModeRecupAndPaiement(idCommande);
            String modeRecup = modes[0];
            String nouveauStatut = modeRecup.equals("Retrait") ? "Recupere" : "Livree";
            dao.enregistrerDateRecup(idCommande, modeRecup);
            dao.updateStatutCommande(idCommande, nouveauStatut);
            db.commit();

        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur clôture : " + e.getMessage());
        } finally {
            db.setAutoCommit(true);
        }
    }

    
    // Résultat de la commande
    public class ResultatCommande {
        public final int idCommande;
        public final double montantTotal;

        public ResultatCommande(int idCommande, double montantTotal) {
            this.idCommande = idCommande;
            this.montantTotal = montantTotal;
        }
    }

    // Adresse du client
    public int demanderAdresse(int idClient) throws Exception {
        List<Integer> adresses = dao.getAdressesClient(idClient);
        if (adresses.isEmpty())
            throw new Exception("Aucune adresse pour ce client.");
        if (adresses.size() == 1)
            return adresses.get(0);
        System.out.println("Choisissez une adresse :");
        for (int i = 0; i < adresses.size(); i++)
            System.out.println((i + 1) + ". ID = " + adresses.get(i));
        Scanner sc = new Scanner(System.in);
        int choix = Integer.parseInt(sc.nextLine());
        return adresses.get(choix - 1);
    }

    // type de conditionnement d'un produit
    public String getTypeCondProduit(int idProd) throws Exception {
        return dao.getTypeCond(idProd);
}


}
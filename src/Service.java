import java.sql.*;
import java.util.*;
import java.util.Date; // disambiguïse java.util.Date vs java.sql.Date

public class Service {

    private final Dao dao;
    private final Database db;
    private final List<Integer> contenantsChoisis = new ArrayList<>();

    public Service(Database db, Dao dao) {
        this.db = db;
        this.dao = dao;
    }

    public void ajouterContenantPourCommandeTemp(int idContenant) {
        contenantsChoisis.add(idContenant);
    }

    public int verifierOuCreerClient(Scanner sc) throws Exception {
        System.out.print("Êtes-vous un nouveau client ? (O/N) : ");
        boolean nouveau = sc.nextLine().equalsIgnoreCase("O");

        if (!nouveau) {
            System.out.print("ID ou email client : ");
            String input = sc.nextLine().trim();
            int idClient;
            if (input.contains("@")) {
                Integer found = dao.getClientByEmail(input);
                if (found == null) throw new Exception("Aucun client avec l'email : " + input);
                idClient = found;
            } else {
                idClient = Integer.parseInt(input);
                if (!dao.clientExiste(idClient))
                    throw new Exception("Client " + idClient + " inexistant.");
            }
            return idClient;
        }

        System.out.print("Choisissez un ID client : ");
        int idClient = Integer.parseInt(sc.nextLine());

        System.out.print("Voulez-vous créer un compte complet (O) ou anonyme (N) ? ");
        boolean complet = sc.nextLine().equalsIgnoreCase("O");

        try {
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
                int idAdresse = dao.nextIdAdresse();
                System.out.print("Rue : ");
                String rue = sc.nextLine();
                System.out.print("Ville : ");
                String ville = sc.nextLine();
                System.out.print("Pays : ");
                String pays = sc.nextLine();
                dao.creerAdresse(idAdresse, idClient, rue, ville, pays);
            }
            db.commit();
            return idClient;
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur lors de la création du client : " + e.getMessage());
        }
    }

    public List<String> getCatalogue() throws Exception {
        return dao.getCatalogue();
    }

    public ResultatCommande passerCommande(int idClient, String mode, String modePaiement,
                                           List<Ligne> lignes, int idAdresse) throws Exception {
        try {
            if (lignes.isEmpty())
                throw new Exception("La commande ne contient aucun produit.");

            int idCommande = dao.creerCommande(idClient, modePaiement);

            // contenants ajoutés avant les produits, numérotés à partir de 1000
            List<Integer> localContenants = new ArrayList<>(contenantsChoisis);
            contenantsChoisis.clear();
            double montantTotal = 0;
            int numLigneContenant = 1000;
            for (int idContenant : localContenants) {
                double prixContenant = dao.getPrixContenant(idContenant);
                dao.ajouterContenantALaCommande(idCommande, idContenant, numLigneContenant++, prixContenant);
                montantTotal += prixContenant;
            }

            // phase 1 : validation, insertion des lignes, réservation FEFO
            // le stock n'est déduit physiquement qu'au passage en "Prête" (marquerCommandePrete)
            int numLigne = 1;
            for (Ligne l : lignes) {
                if (!dao.estProduitEnSaison(l.idProduit))
                    throw new Exception("Produit " + l.idProduit + " hors saison.");
                double prixUnitaire = dao.getPrixVente(l.idProduit);
                dao.verrouillerProduit(l.idProduit); // sérialise les F1 concurrents sur ce produit
                double disponible = dao.getStockDisponible(l.idProduit);
                if (disponible < l.quantite)
                    throw new Exception("Stock insuffisant pour produit " + l.idProduit);
                double sousTotal = prixUnitaire * l.quantite;
                montantTotal += sousTotal;
                dao.ajouterLigneCommande(idCommande, l, numLigne++, prixUnitaire, sousTotal);
                // réservation FEFO : lots triés par date de péremption croissante
                double restant = l.quantite;
                for (Dao.LotInfo lot : dao.getLotsDisponiblesFEFO(l.idProduit)) {
                    if (restant <= 0) break;
                    double aReserver = Math.min(restant, lot.stockActuel);
                    dao.reserverStockLot(idCommande, lot.idLot, aReserver);
                    restant -= aReserver;
                }
            }

            dao.enregistrerModeRecuperation(idCommande, mode, idAdresse);

            if (mode.equalsIgnoreCase("Livraison")) {
                String pays = dao.getPaysLivraison(idCommande);
                String ville = dao.getVilleLivraison(idCommande);
                double poidsTotal = calculerPoidsTotalCommande(idCommande);
                double fraisLivraison = calculerFraisLivraison(pays, ville, poidsTotal);
                Date dateEstimee = calculerDateLivraisonEstimee(pays, ville);
                dao.updateFraisEtDateLivraison(idCommande, fraisLivraison, dateEstimee);
                montantTotal += fraisLivraison;
            }

            db.commit();
            return new ResultatCommande(idCommande, montantTotal);
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Échec de commande : " + e.getMessage());
        }
    }

    public List<String[]> getContenantsCompatibles(double quantite) throws Exception {
        return dao.getContenantsCompatibles(quantite);
    }

    public double calculerPoidsTotalCommande(int idCommande) throws SQLException {
        double poids = 0;
        for (Ligne l : dao.getLignesCommande(idCommande)) {
            if ("Kg".equalsIgnoreCase(l.unite)) {
                poids += l.quantite;
            } else {
                Double pf = dao.getPoidsFixe(l.idProduit);
                if (pf == null)
                    throw new SQLException("Produit préconditionné sans poids fixe : " + l.idProduit);
                poids += (pf / 1000.0) * l.quantite; // conversion g → kg
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

    public List<String> getAlertes() throws Exception {
        return dao.getAlertes();
    }

    public int ajusterPrixPeremption(int pourcentage) throws Exception {
        try {
            List<Integer> produits = dao.getProduitsAjuster();
            int count = 0;
            for (int idP : produits) {
                boolean v = dao.updatePrixVrac(idP, pourcentage);
                boolean p = dao.updatePrixPrecond(idP, pourcentage);
                if (v || p) count++;
            }
            db.commit();
            return count;
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur F2 : " + e.getMessage());
        }
    }

    // retourne [modeRecup, modePaiement] pour que le menu puisse afficher le paiement boutique
    public String[] cloturerCommande(int idCommande) throws Exception {
        try {
            String statut = dao.getStatutEtVerrouillerCommande(idCommande);
            if (!List.of("En preparation", "Prete", "En livraison").contains(statut))
                throw new Exception("Commande non clôturable au statut : " + statut);
            String[] modes = dao.getModeRecupAndPaiement(idCommande);
            String modeRecup = modes[0];
            String nouveauStatut = modeRecup.equals("Retrait") ? "Recupere" : "Livree";
            dao.enregistrerDateRecup(idCommande, modeRecup);
            dao.updateStatutCommande(idCommande, nouveauStatut);
            db.commit();
            return modes;
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur clôture : " + e.getMessage());
        }
    }

    // passage en "Prête" : déstockage réel FEFO depuis les réservations
    public void marquerCommandePrete(int idCommande) throws Exception {
        try {
            String statut = dao.getStatutEtVerrouillerCommande(idCommande);
            if (!"En preparation".equals(statut))
                throw new Exception("La commande n'est pas en préparation (statut : " + statut + ").");
            for (Dao.LotInfo r : dao.getReservationsCommande(idCommande))
                dao.updateStockLot(r.idLot, r.stockActuel);
            dao.effacerReservationsCommande(idCommande);
            dao.updateStatutCommande(idCommande, "Prete");
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur passage en Prête : " + e.getMessage());
        }
    }

    public void annulerCommande(int idCommande, boolean parClient) throws Exception {
        try {
            String statut = dao.getStatutEtVerrouillerCommande(idCommande);
            if (parClient && !"En preparation".equals(statut))
                throw new Exception("Annulation client autorisée uniquement en statut 'En preparation'.");
            if (!parClient && List.of("Recupere", "Livree", "Annulee").contains(statut))
                throw new Exception("Commande déjà finalisée, annulation impossible.");
            if ("En preparation".equals(statut))
                dao.effacerReservationsCommande(idCommande); // libère le stock réservé
            dao.updateStatutCommande(idCommande, "Annulee");
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur annulation : " + e.getMessage());
        }
    }

    public void enregistrerPerte(String nature, double quantite, String type, int idElement) throws Exception {
        try {
            int idPerte = dao.nextIdPerte();
            dao.creerPerte(idPerte, nature, quantite, type);
            if ("PRODUIT".equalsIgnoreCase(type))
                dao.lierProduitPerte(idPerte, idElement);
            else
                dao.lierContenantPerte(idPerte, idElement);
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw new Exception("Erreur enregistrement perte : " + e.getMessage());
        }
    }

    public int demanderAdresse(int idClient, Scanner sc) throws Exception {
        List<Integer> adresses = dao.getAdressesClient(idClient);
        if (adresses.isEmpty())
            throw new Exception("Aucune adresse pour ce client.");
        if (adresses.size() == 1)
            return adresses.get(0);
        System.out.println("Choisissez une adresse :");
        for (int i = 0; i < adresses.size(); i++)
            System.out.println((i + 1) + ". ID = " + adresses.get(i));
        return adresses.get(Integer.parseInt(sc.nextLine()) - 1);
    }

    public String getTypeCondProduit(int idProd) throws Exception {
        return dao.getTypeCond(idProd);
    }

    public static class ResultatCommande {
        public final int idCommande;
        public final double montantTotal;

        public ResultatCommande(int idCommande, double montantTotal) {
            this.idCommande = idCommande;
            this.montantTotal = montantTotal;
        }
    }
}

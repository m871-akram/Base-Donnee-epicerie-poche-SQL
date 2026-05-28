import java.util.*;

public class Menu {

    private final Service service;
    private final Scanner sc = new Scanner(System.in);

    public Menu(Service service) {
        this.service = service;
    }

    public void start() {
        while (true) {
            System.out.println("\n BIENVENUE à l'épicerie 'LE BON CHOIX'");
            System.out.println("1. Entrer en tant que client");
            System.out.println("2. Entrer en tant qu'épicier");
            System.out.println("0. Quitter le menu");
            System.out.print("Choix : ");
            try {
                int c = Integer.parseInt(sc.nextLine());
                switch (c) {
                    case 1 -> startClientLoop();
                    case 2 -> startEpicierLoop();
                    case 0 -> { System.out.println("Au revoir !"); return; }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (Exception e) {
                System.out.println("Erreur : " + e.getMessage());
            }
        }
    }

    public void startClientLoop() {
        while (true) {
            System.out.println("\n --- MENU DU CLIENT ---");
            System.out.println("1. Afficher catalogue des produits");
            System.out.println("2. Passer une commande");
            System.out.println("3. Annuler une commande");
            System.out.println("0. Retour");
            System.out.print("Choix : ");
            try {
                int c = Integer.parseInt(sc.nextLine());
                switch (c) {
                    case 1 -> afficherCatalogue();
                    case 2 -> passerCommande();
                    case 3 -> annulerCommande(true);
                    case 0 -> { return; }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (Exception e) {
                System.out.println("Erreur : " + e.getMessage());
            }
        }
    }

    public void startEpicierLoop() {
        while (true) {
            System.out.println("\n--- MENU DE L'ÉPICIER ---");
            System.out.println("1. Ajuster prix péremption");
            System.out.println("2. Marquer commande comme Prête (déstockage)");
            System.out.println("3. Clôturer commande");
            System.out.println("4. Annuler une commande");
            System.out.println("5. Enregistrer une perte");
            System.out.println("0. Retour");
            System.out.print("Choix : ");
            try {
                int c = Integer.parseInt(sc.nextLine());
                switch (c) {
                    case 1 -> ajusterPrixEtAfficherAlertes();
                    case 2 -> marquerCommandePrete();
                    case 3 -> cloturerCommande();
                    case 4 -> annulerCommande(false);
                    case 5 -> enregistrerPerte();
                    case 0 -> { return; }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (Exception e) {
                System.out.println("Erreur : " + e.getMessage());
            }
        }
    }

    private void afficherCatalogue() {
        try {
            System.out.println("\n--- CATALOGUE ---");
            service.getCatalogue().forEach(System.out::println);
        } catch (Exception e) {
            System.out.println("Erreur : " + e.getMessage());
        }
    }

    private void passerCommande() {
        try {
            int idClient = service.verifierOuCreerClient(sc);

            System.out.print("Mode récupération (Retrait / Livraison) : ");
            String modeRecuperation = sc.nextLine().trim().toUpperCase();
            if (!modeRecuperation.equals("RETRAIT") && !modeRecuperation.equals("LIVRAISON")) {
                System.out.println("Mode invalide. Choisir 'Retrait' ou 'Livraison'.");
                return;
            }

            System.out.print("Mode paiement (EN LIGNE / EN BOUTIQUE) : ");
            String modePaiement = sc.nextLine().trim().toUpperCase();
            if (!modePaiement.equals("EN LIGNE") && !modePaiement.equals("EN BOUTIQUE")) {
                System.out.println("Mode paiement invalide.");
                return;
            }

            int idAdresse = -1;
            if (modeRecuperation.equalsIgnoreCase("Livraison")) {
                idAdresse = service.demanderAdresse(idClient, sc);
                System.out.println("Adresse sélectionnée : " + idAdresse);
            }

            List<Ligne> lignes = new ArrayList<>();
            System.out.println("\n--- AJOUTER AU PANIER ---");
            while (true) {
                System.out.print("rentrer l'ID du produit que vous voulez ( rentrer 0 pour terminer) : ");
                int p = Integer.parseInt(sc.nextLine());
                if (p == 0) break;

                String type = service.getTypeCondProduit(p);
                String unite = type.equals("VRAC") ? "Kg" : "Unite";

                System.out.print("Quantité (" + unite + ") : ");
                double q = Double.parseDouble(sc.nextLine());

                // pour les produits en vrac, on propose un contenant compatible (capacité >= quantité)
                if (type.equals("VRAC")) {
                    List<String[]> contenants = service.getContenantsCompatibles(q);
                    if (!contenants.isEmpty()) {
                        System.out.println("Souhaites-tu un contenant ? (O/N)");
                        if (sc.nextLine().equalsIgnoreCase("O")) {
                            System.out.println("Choisis un contenant :");
                            for (int j = 0; j < contenants.size(); j++) {
                                String[] c = contenants.get(j);
                                System.out.println((j + 1) + ". " + c[2] + " (" + c[1] + ")");
                            }
                            System.out.print("Choix : ");
                            int choix = Integer.parseInt(sc.nextLine());
                            int idContenant = Integer.parseInt(contenants.get(choix - 1)[0]);
                            service.ajouterContenantPourCommandeTemp(idContenant);
                        }
                    }
                } else {
                    System.out.println("Produit préconditionné → unité = 1 Unité par article.");
                }

                lignes.add(new Ligne(p, q, unite));
            }

            Service.ResultatCommande res = service.passerCommande(
                    idClient, modeRecuperation, modePaiement, lignes, idAdresse
            );
            System.out.println("\n Votre Commande a été créée avec succès ");
            System.out.println("L'ID de votre Commande est  : " + res.idCommande);
            System.out.println("Le montant total est : " + String.format("%.2f", res.montantTotal) + " euros");
        } catch (Exception e) {
            System.out.println("Erreur commande : " + e.getMessage());
        }
    }

    private void ajusterPrixEtAfficherAlertes() {
        try {
            System.out.println("\n--- ALERTES DES DES DATES DE PEREMPTION ---");
            List<String> alertes = service.getAlertes();
            if (alertes.isEmpty()) {
                System.out.println("Aucune alerte.");
                return;
            }
            alertes.forEach(System.out::println);
            System.out.print("Pourcentage de réduction : ");
            int pct = Integer.parseInt(sc.nextLine());
            int count = service.ajusterPrixPeremption(pct);
            System.out.println(count + " produits ajustés.");
        } catch (Exception e) {
            System.out.println("Erreur d'ajustement : " + e.getMessage());
        }
    }

    private void cloturerCommande() {
        try {
            System.out.print("ID commande : ");
            int id = Integer.parseInt(sc.nextLine());
            String[] modes = service.cloturerCommande(id);
            System.out.println("Commande clôturée.");
            if ("Retrait".equals(modes[0]) && "EN BOUTIQUE".equals(modes[1]))
                System.out.println("Paiement EN BOUTIQUE enregistré.");
        } catch (Exception e) {
            System.out.println("Erreur de Clôture : " + e.getMessage());
        }
    }

    private void marquerCommandePrete() {
        try {
            System.out.print("ID commande : ");
            int id = Integer.parseInt(sc.nextLine());
            service.marquerCommandePrete(id);
            System.out.println("Commande passée en 'Prête'. Stock déduit.");
        } catch (Exception e) {
            System.out.println("Erreur : " + e.getMessage());
        }
    }

    private void annulerCommande(boolean parClient) {
        try {
            System.out.print("ID commande : ");
            int id = Integer.parseInt(sc.nextLine());
            service.annulerCommande(id, parClient);
            System.out.println("Commande annulée.");
        } catch (Exception e) {
            System.out.println("Erreur annulation : " + e.getMessage());
        }
    }

    private void enregistrerPerte() {
        try {
            System.out.print("Nature de la perte (vol / casse / dommage) : ");
            String nature = sc.nextLine().trim();
            System.out.print("Type (P = Produit / C = Contenant) : ");
            String type = sc.nextLine().trim().toUpperCase();
            if (!type.equals("P") && !type.equals("C")) {
                System.out.println("Type invalide.");
                return;
            }
            System.out.print("ID du " + (type.equals("P") ? "produit" : "contenant") + " : ");
            int idElem = Integer.parseInt(sc.nextLine());
            System.out.print("Quantité perdue : ");
            double quantite = Double.parseDouble(sc.nextLine());
            service.enregistrerPerte(nature, quantite, type.equals("P") ? "PRODUIT" : "CONTENANT", idElem);
            System.out.println("Perte enregistrée.");
        } catch (Exception e) {
            System.out.println("Erreur perte : " + e.getMessage());
        }
    }
}

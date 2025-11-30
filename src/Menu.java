import java.util.*;

/**
 * Interface utilisateur pour l'intéraction soit avec l'épicié ou le client.
 */
public class Menu {

    // Référence vers le Service 
    private final Service service;
    private final Scanner sc = new Scanner(System.in);

    public Menu(Service service) {
        this.service = service;
    }

    /**
     * Lance le menu interactif (boucle .
     */
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

    
    // Partie du client
    public void startClientLoop() {
        while (true) {
            System.out.println("\n --- MENU DU CLIENT ---");
            System.out.println("1. Afficher catalogue des produits");
            System.out.println("2. Passer une commande");
            System.out.println("0. Retour");
            System.out.print("Choix : ");

            try {
                int c = Integer.parseInt(sc.nextLine());
                switch (c) {
                    case 1 -> afficherCatalogue();
                    case 2 -> passerCommande();
                    case 0 -> { return; }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (Exception e) {
                System.out.println("Erreur : " + e.getMessage());
            }
        }
    }

    // =============================================================
    // EPICIER LOOP
    // =============================================================

    public void startEpicierLoop() {
        while (true) {
            System.out.println("\n--- MENU DE L'ÉPICIER ---");
            System.out.println("1. Ajuster prix péremption ");
            System.out.println("2. Clôturer commande");
            System.out.println("0. Retour");
            System.out.print("Choix : ");

            try {
                int c = Integer.parseInt(sc.nextLine());
                switch (c) {
                    case 1 -> ajusterPrixEtAfficherAlertes();
                    case 2 -> cloturerCommande();
                    case 0 -> { return; }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (Exception e) {
                System.out.println("Erreur : " + e.getMessage());
            }
        }
    }

    
    // Fonctionnalité 1: Gestion du catalogue
    private void afficherCatalogue() {
        try {
            System.out.println("\n--- CATALOGUE ---");
            service.getCatalogue().forEach(System.out::println);
        } catch (Exception e) {
            System.out.println("Erreur : " + e.getMessage());
        }
    }

    // Fonctionnalité 1 : passer la commande
    private void passerCommande() {
        try {
            int idClient = service.verifierOuCreerClient();
            // Mode de récuprération
            System.out.print("Mode récupération (Retrait / Livraison) : ");
            String modeRecuperation = sc.nextLine().trim();
            modeRecuperation = modeRecuperation.toUpperCase();
            if (!modeRecuperation.equals("RETRAIT") && !modeRecuperation.equals("LIVRAISON")) {
                System.out.println("Mode invalide. Choisir 'Retrait' ou 'Livraison'.");
                return;
            }
            // Mode de paiement
            System.out.print("Mode paiement (EN LIGNE / EN BOUTIQUE) : ");
            String modePaiement = sc.nextLine().trim().toUpperCase();
            if (!modePaiement.equals("EN LIGNE") && !modePaiement.equals("EN BOUTIQUE")) {
                System.out.println("Mode paiement invalide.");
                return;
            }
            // Adresse pour livraison
            int idAdresse = -1;
            if (modeRecuperation.equalsIgnoreCase("Livraison")) {
                idAdresse = service.demanderAdresse(idClient);
                System.out.println("Adresse sélectionnée : " + idAdresse);
            }
            // 
            List<Ligne> lignes = new ArrayList<>();
            System.out.println("\n--- AJOUTER AU PANIER ---");
            while (true) {
                System.out.print("rentrer l'ID du produit que vous voulez ( rentrer 0 pour terminer) : ");
                int p = Integer.parseInt(sc.nextLine());
                if (p == 0) break;
                String type = service.getTypeCondProduit(p);
                String unite = type.equals("VRAC") ? "Kg" : "Unite";

                // Quantité
                System.out.print("Quantité (" + unite + ") : ");
                double q = Double.parseDouble(sc.nextLine());
                // Gestion Vrac
                if (type.equals("VRAC")) {
                    System.out.println("Pour les produits de type VRAC l'unité imposée est Kg");
                    System.out.print("Ce produit est en VRAC. Voulez-vous ajouter un contenant ?  (O/N) : ");
                    String rep = sc.nextLine();
                    if (rep.equalsIgnoreCase("O")) {
                        List<String[]> contenants = service.getContenantsCompatibles(q);
                        if (contenants.isEmpty()) {
                            System.out.println("Aucun contenant compatible disponible !");
                        } else {
                            System.out.println("Choisissez un contenant :");
                            for (int j = 0; j < contenants.size(); j++) {
                                String[] c = contenants.get(j);
                                System.out.println((j + 1) + ". " + c[2] + " (" + c[1] + " Kg)");
                            }
                            System.out.print("Votre choix : ");
                            int choix = Integer.parseInt(sc.nextLine());
                            int idContenant = Integer.parseInt(contenants.get(choix - 1)[0]);
                            // Sera traité dans Service après l'insertion de la commande
                            lignes.add(new Ligne(-1, 1, "CONTENANT_" + idContenant));
                        }
                    }
                }

                 if (type.equals("VRAC")) {
     List<String[]> contenants = service.getContenantsCompatibles(q);
     if (!contenants.isEmpty()) {
         System.out.println("Souhaites-tu un contenant ? (O/N)");
         String rep = sc.nextLine();
         if (rep.equalsIgnoreCase("O")) {
             System.out.println("Choisis un contenant :");
             for (int j = 0; j < contenants.size(); j++) {
                 String[] c = contenants.get(j);
                 System.out.println((j + 1) + ". " + c[2] + " (" + c[1] + ")");
             }

             System.out.print("Choix : ");
             int choix = Integer.parseInt(sc.nextLine());
             int idContenant = Integer.parseInt(contenants.get(choix - 1)[0]);

             // On ajoute le contenant dans une structure dédiée
             service.ajouterContenantPourCommandeTemp(idContenant);
         }
     }
 }
                else {
                     if (type.equals("PRE")) {
     System.out.println("Produit préconditionné → unité = 1 Unité par article.");
 }
                }
                lignes.add(new Ligne(p, q, unite));
            }

            // Validation de la commande
            Service.ResultatCommande res = service.passerCommande(
                    idClient, modeRecuperation, modePaiement, lignes
            );
            System.out.println("\n Votre Commande a été créée avec succès ");
            System.out.println("L'ID de votre Commande est  : " + res.idCommande);
            System.out.println("Le montant total est : " + String.format("%.2f", res.montantTotal) + " euros");
        } catch (Exception e) {
            System.out.println("Erreur commande : " + e.getMessage());
        }
    }

    // Fonctionnalité 2 : ajustement des prix 
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

    // Fonctionnalite 3: Clôturer commande 
    private void cloturerCommande() {
        try {
            System.out.print("ID commande : ");
            int id = Integer.parseInt(sc.nextLine());
            service.cloturerCommande(id);
            System.out.println("Waaw, Commande clôturée.");
        } catch (Exception e) {
            System.out.println("Erreur de Clôture  : " + e.getMessage());
        }
    }
}
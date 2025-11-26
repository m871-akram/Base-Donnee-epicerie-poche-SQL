import java.util.*;

/**
 * Interface utilisateur (console interactive).
 * Affiche le menu, lit les entrées clavier et appelle le Service.
 */
public class Menu {

    // Référence vers le Service pour appeler la logique métier
    private final Service service;
    // Scanner pour lire les entrées clavier de l'utilisateur
    private final Scanner sc = new Scanner(System.in);

    /**
     * Constructeur : reçoit le Service par injection de dépendance.
     */
    public Menu(Service service) {
        this.service = service;
    }

    /**
     * Lance le menu interactif (boucle infinie).
     */
    public void start() {

        while (true) {
            // Afficher le menu principal
            System.out.println("\n--- MENU ---");
            System.out.println("1. Afficher catalogue");
            System.out.println("2. Passer commande");
            System.out.println("3. Ajuster prix péremption (Transaction F2)"); // ⬅NOUVEAU NOM
            System.out.println("4. Clôturer commande (Transaction F3)");
            System.out.println("0. Quitter");
            System.out.print("Choix : ");

            // Lire le choix de l'utilisateur
            try {
                int c = Integer.parseInt(sc.nextLine());

                // Switch expression (Java 14+) : appeler la bonne méthode selon le choix
                switch (c) {
                    case 1 -> afficherCatalogue();
                    case 2 -> passerCommande();
                    case 3 -> ajusterPrixEtAfficherAlertes(); // ⬅ NOUVELLE MÉTHODE
                    case 4 -> cloturerCommande();
                    case 0 -> {
                        System.out.println("Au revoir !");
                        return; // Quitter la boucle et le programme
                    }
                    default -> System.out.println("Choix invalide.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Veuillez entrer un nombre valide.");
            } catch (Exception e) {
                 System.out.println("Erreur fatale : " + e.getMessage());
            }
        }
    }

    // --- LOGIQUE F1 (Affichage simple) ---

    private void afficherCatalogue() {
        try {
            System.out.println("\n--- CATALOGUE ---");
            service.getCatalogue().forEach(System.out::println);
        } catch (Exception e) {
            System.out.println("Erreur : " + e.getMessage());
        }
    }

    // --- LOGIQUE F1 (Transaction) ---

    private void passerCommande() {
        try {
            System.out.print("ID client : ");
            int idClient = Integer.parseInt(sc.nextLine());

            System.out.print("Mode récupération (Retrait/Livraison) : ");
            String mode = sc.nextLine();

            // Saisie des lignes de commande
            List<Ligne> lignes = new ArrayList<>();
            System.out.println("\n--- AJOUT DES PRODUITS ---");

            while (true) {
                System.out.print("ID produit (0 pour terminer) : ");
                int p = Integer.parseInt(sc.nextLine());
                if (p == 0) break; // 0 = sortir de la boucle

                System.out.print("Quantité : ");
                double q = Double.parseDouble(sc.nextLine());

                System.out.print("Unité (Kg/Unité) : ");
                String u = sc.nextLine();

                System.out.print("Paiement (EN LIGNE/EN BOUTIQUE) : ");
                String mp = sc.nextLine();

                // Créer un objet Ligne et l'ajouter à la liste
                lignes.add(new Ligne(p, q, u, mp));
            }

            // Appeler le Service pour créer la commande (transaction complète)
            int idC = service.passerCommande(idClient, mode, lignes);
            System.out.println("\n Commande créée ID = " + idC + " en statut 'En préparation'.");

        } catch (Exception e) {
            // Si erreur (ex : produit inexistant), afficher le message
            System.out.println(" Erreur lors de la création de la commande : " + e.getMessage());
        }
    }

    // --- LOGIQUE F2 (Transaction Ajustement Prix) ---

    /**
     * Option 3 : Affiche les alertes de péremption ET demande d'ajuster les prix.
     * Appelle la transaction F2 dans le Service.
     */
    private void ajusterPrixEtAfficherAlertes() {
        try {
            // 1. Afficher d'abord les alertes pour information
            System.out.println("\n--- ALERTES PÉREMPTION (Lots expirant J+7) ---");
            service.getAlertes().forEach(System.out::println);
            
            // 2. Demander le pourcentage pour l'ajustement
            System.out.print("Entrez le pourcentage de réduction à appliquer (ex: 30) : ");
            int pourcentage = Integer.parseInt(sc.nextLine());

            // 3. Appeler la transaction du Service
            int count = service.ajusterPrixPeremption(pourcentage);

            System.out.println("\n " + count + " prix de produits ajustés de " + pourcentage + "%.");

        } catch (Exception e) {
            System.out.println(" Erreur lors de l'ajustement des prix : " + e.getMessage());
        }
    }

    // --- LOGIQUE F3 (Transaction Clôture/Déstockage) ---

    /**
     * Option 4 : Clôturer une commande, ce qui déclenche le déstockage FIFO.
     * Appelle la transaction F3 dans le Service.
     */
    private void cloturerCommande() {
        try {
            System.out.print("ID commande à clôturer : ");
            int id = Integer.parseInt(sc.nextLine());
            
            // Le Service gère le verrouillage, le déstockage FIFO et le statut.
            service.cloturerCommande(id); 
            
            System.out.println("\n Commande ID " + id + " clôturée et stock déduit (FIFO).");
            
        } catch (Exception e) {
            // L'erreur vient du Service (stock insuffisant, commande déjà traitée, etc.)
            System.out.println(" Erreur lors de la clôture : " + e.getMessage());
        }
    }
}
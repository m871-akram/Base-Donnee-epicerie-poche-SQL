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
            System.out.println("2. Passer commande (F1: Création + Vérifications + Déstockage)"); // F1 complète
            System.out.println("3. Ajuster prix péremption (F2: Transaction d'ajustement)");
            System.out.println("4. Clôturer commande (F3: Mise à jour statut uniquement)"); // F3 simplifiée
            System.out.println("0. Quitter");
            System.out.print("Choix : ");

            // Lire le choix de l'utilisateur
            try {
                int c = Integer.parseInt(sc.nextLine());

                // Appeler la bonne méthode selon le choix
                switch (c) {
                    case 1 -> afficherCatalogue();
                    case 2 -> passerCommande();
                    case 3 -> ajusterPrixEtAfficherAlertes(); // F2
                    case 4 -> cloturerCommande(); // F3
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

    // -------------------------------------------------------------
    // --- LOGIQUE F1 (Affichage simple) ---------------------------
    // -------------------------------------------------------------

    private void afficherCatalogue() {
        try {
            System.out.println("\n--- CATALOGUE ---");
            service.getCatalogue().forEach(System.out::println);
        } catch (Exception e) {
            System.out.println("Erreur : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------
    // --- LOGIQUE F1 (Transaction complète) -----------------------
    // -------------------------------------------------------------

    /**
     * Option 2 : Passe une commande. Le Service gère la vérification (stock/saison),
     * le calcul des prix, la création des lignes et le DÉSTOCKAGE (FIFO) dans une seule transaction.
     */
    private void passerCommande() {
        try {
            System.out.print("ID client : ");
            int idClient = Integer.parseInt(sc.nextLine());

            System.out.print("Mode récupération (Retrait/Livraison) : ");
            String modeRecuperation = sc.nextLine();

            // Lecture du mode de paiement pour la COMMANDE
            System.out.print("Mode paiement (EN LIGNE/EN BOUTIQUE) : ");
            String modePaiement = sc.nextLine();

            // Saisie des lignes de commande
            List<Ligne> lignes = new ArrayList<>();
            System.out.println("\n--- AJOUT DES PRODUITS ---");

            while (true) {
                System.out.print("ID produit (0 pour terminer) : ");
                int p = Integer.parseInt(sc.nextLine());
                if (p == 0) break;

                System.out.print("Quantité : ");
                double q = Double.parseDouble(sc.nextLine());

                System.out.print("Unité (Kg/Unité) : ");
                String u = sc.nextLine();

                // Supposons que Ligne(int, double, String) est le constructeur correct.
                lignes.add(new Ligne(p, q, u)); 
            }

            // Appeler le Service pour créer la commande (transaction complète F1)
            // LIGNE CORRIGÉE : Récupération de l'objet ResultatCommande
            Service.ResultatCommande resultat = service.passerCommande(idClient, modeRecuperation, modePaiement, lignes);
            
            // Affichage mis à jour pour afficher l'ID et le montant total
            System.out.println("\n✅ Commande créée avec succès !");
            System.out.println("ID Commande: " + resultat.idCommande);
            System.out.println("Montant total à payer (incl. frais): " + String.format("%.2f", resultat.montantTotal) + "€");
            System.out.println("Stock déduit.");


        } catch (Exception e) {
            // Affiche l'erreur du Service (ex : Stock insuffisant, Produit hors saison)
            System.out.println(" Erreur lors de la création de la commande : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------
    // --- LOGIQUE F2 (Transaction Ajustement Prix) ----------------
    // -------------------------------------------------------------

    /**
     * Option 3 : Affiche les alertes de péremption ET demande d'ajuster les prix (Transaction F2).
     */
    private void ajusterPrixEtAfficherAlertes() {
        try {
            // 1. Afficher d'abord les alertes pour information
            System.out.println("\n--- ALERTES PÉREMPTION (Lots expirant J+7) ---");
            List<String> alertes = service.getAlertes();
            if (alertes.isEmpty()) {
                System.out.println("Aucun produit n'est proche de la péremption.");
                return;
            }
            alertes.forEach(System.out::println);
            
            // 2. Demander le pourcentage pour l'ajustement
            System.out.print("\nEntrez le pourcentage de réduction à appliquer (ex: 30) : ");
            int pourcentage = Integer.parseInt(sc.nextLine());

            // 3. Appeler la transaction du Service
            int count = service.ajusterPrixPeremption(pourcentage);

            System.out.println("\n " + count + " prix de produits ajustés de " + pourcentage + "%.");

        } catch (Exception e) {
            System.out.println(" Erreur lors de l'ajustement des prix : " + e.getMessage());
        }
    }

    // -------------------------------------------------------------
    // --- LOGIQUE F3 (Transaction Clôture de Statut) --------------
    // -------------------------------------------------------------

    /**
     * Option 4 : Clôturer une commande. Mise à jour du statut. 
     */
    private void cloturerCommande() {
        try {
            System.out.print("ID commande à clôturer (statut 'En préparation' requis) : ");
            int id = Integer.parseInt(sc.nextLine());
            
            // Le Service gère le verrouillage et le changement de statut.
            service.cloturerCommande(id); 
            
            // Affichage mis à jour pour refléter que le déstockage est fait ailleurs
            System.out.println("\n Commande ID " + id + " mise à jour au statut 'Livrée'.");
            
        } catch (Exception e) {
            // L'erreur vient du Service (commande déjà traitée, etc.)
            System.out.println(" Erreur lors de la clôture : " + e.getMessage());
        }
    }
}
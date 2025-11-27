/**
 * Point d'entrée de l'application.
 * Configure et connecte tous les composants du système.
 */
public class Main {

    public static void main(String[] args) {

        try {
            // Étape 1 : Créer la connexion à Oracle (JDBC)
            // URL format : jdbc:oracle:thin:@host:port:sid
            // À adapter selon ton environnement Oracle
            Database db = new Database(
                    "jdbc:oracle:thin:@oracle1.ensimag.fr:1521:oracle1", // change
                    "ikaouasi",
                    "ikaouasi"
            );

            // Étape 2 : Créer les objets en cascade (injection manuelle)
            // Le Dao a besoin de Database pour exécuter du SQL
            Dao dao = new Dao(db);
            // Le Service a besoin de Database (pour commit/rollback) et du Dao (pour les requêtes)
            Service service = new Service(db, dao);
            // Le Menu a besoin du Service pour appeler la logique métier
            Menu menu = new Menu(service);

            // Étape 3 : Lancer le menu interactif (boucle infinie jusqu'à ce que l'utilisateur quitte)
            menu.start();

            // Étape 4 : Fermer proprement la connexion Oracle à la fin
            db.close();

        } catch (Exception e) {
            // Si erreur (connexion échouée, etc.), afficher la trace complète
            e.printStackTrace();
        }
    }
}
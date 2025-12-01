-- -------------------------------------------------------------
-- ÉTAPE 1 : Configuration de la Transaction (ACID)
-- -------------------------------------------------------------
-- 1. Désactivation de l'autocommit pour garantir l'Atomicité.
SET AUTOCOMMIT OFF;

-- 2. Niveau d'isolation SERIALIZABLE (Garantit l'Isolation contre la concurrence).
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;

-- -------------------------------------------------------------
-- ÉTAPE 2 : Vérifications de Cohérence (Lues par la couche Service/DAO)
-- -------------------------------------------------------------
-- Si ces requêtes ne retournent pas le résultat attendu (Saison > 0 / Stock >= Quantité),
-- l'application DOIT annuler la transaction et ne pas exécuter les INSERT/UPDATE suivants.

-- 2.1. VÉRIFICATION DE LA SAISONNALITÉ
SELECT COUNT(*)
FROM PERIODE p
JOIN PERIODE_DISPONIBILITE pd ON p.IDPERIODE = pd.IDPERIODE
WHERE pd.IDPRODUIT = ID_PRODUIT_p 
  AND SYSDATE BETWEEN p.DEBUTPERIODE AND p.FINPERIODE;

-- 2.2. VÉRIFICATION DU STOCK TOTAL SUFFISANT (Pré-vérification)
SELECT SUM(QUANTITESTOCKLOT)
FROM LOT_PRODUIT
WHERE IDPRODUIT = ID_PRODUIT_p            
  AND DATEPEREMPTION > SYSDATE
HAVING SUM(QUANTITESTOCKLOT) >= QTE_COMMANDEE_p; 

-- -------------------------------------------------------------
-- ÉTAPE 3 : Création de l'En-tête de Commande
-- -------------------------------------------------------------

-- 3.1. Insertion de la COMMANDE (Génère l'ID via la séquence)
INSERT INTO COMMANDE
(IDCOMMANDE, DATECOMMANDE, HEURECOMMANDE, STATUT, MODEPAIEMENT, IDCLIENT)
VALUES (SEQ_COMMANDE.NEXTVAL, SYSDATE, TO_CHAR(SYSDATE,'HH24:MI:SS'),
        'En preparation', :MODE_PAIEMENT, ID_CLIENT_p); 

-- 3.2. Enregistrement du Mode de Récupération (Ex: Livraison Domicile)
-- Ceci utilise SEQ_COMMANDE.CURRVAL pour récupérer l'ID nouvellement créé.
INSERT INTO LIVRAISON_DOMICILE(IDLIVRAISONDOMICILE, IDCOMMANDE, FRAISLIVRAISON, IDADRESSE)
VALUES (SEQ_LIVRAISON_DOMICILE.NEXTVAL, SEQ_COMMANDE.CURRVAL, FRAIS_p, ID_ADRESSE_p);

-- 3.3. Mise à jour des Frais et Date de Livraison (Si mode = "Livraison")
UPDATE LIVRAISON_DOMICILE
SET FRAISLIVRAISON=:FRAIS_LIVRAISON, DATELIVRAISONESTIMEE=DATE_ESTIMEE
WHERE IDCOMMANDE=SEQ_COMMANDE.CURRVAL;

-- -------------------------------------------------------------
-- ÉTAPE 4 : Déstockage FEFO (Produit)
-- -------------------------------------------------------------
-- La clause AND QUANTITESTOCKLOT >= ? garantit que l'UPDATE échouera
-- si le stock est insuffisant.

-- 4.1. DÉSTOCKAGE Lot Produit N (Exécuté pour chaque lot consommé)
UPDATE LOT_PRODUIT
SET QUANTITESTOCKLOT = QUANTITESTOCKLOT - QTE_PRISE_LOT_N_p  
WHERE IDLOTPRODUIT = ID_LOT_N_p                     
  AND QUANTITESTOCKLOT >= QTE_PRISE_LOT_N_p;                

-- -------------------------------------------------------------
-- ÉTAPE 5 : Insertion des Lignes de Commande
-- -------------------------------------------------------------

-- 5.1. Insertion de la LIGNE_COMMANDE du produit
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, IDCONTENANT,
 QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
VALUES (SEQ_COMMANDE.CURRVAL, NUM_LIGNE_PRODUIT, ID_PRODUIT_p, NULL, 
        QTE_COMMANDEE_LIGNE, UNITE_COMMANDEE, PRIX_UNITAIRE_PROD, SOUS_TOTAL_PRODUIT);

-- 5.2. DÉSTOCKAGE du Contenant (Pour chaque contenant)
UPDATE CONTENANT
SET STOCKDISPOCONTENANT = STOCKDISPOCONTENANT - 1
WHERE IDCONTENANT = ID_CONTENANT_p                           
  AND STOCKDISPOCONTENANT >= 1;                 

-- 5.3. Insertion de la LIGNE_COMMANDE du contenant (Pour chaque contenant)
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, IDCONTENANT,
 QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
VALUES (SEQ_COMMANDE.CURRVAL, NUM_LIGNE_CONTENANT, NULL, ID_CONTENANT, 
        1, 'Unite', PRIX_UNITAIRE_CONT, SOUS_TOTAL_CONTENANT);

COMMIT;

-- ROLLBACK;

-- FONCTIONNALITÉ 1 (PASSAGE DE COMMANDE ET CLIENT) 

-- Création de l'entrée de base dans la table CLIENT (si le client n'existait pas)
INSERT INTO CLIENT (IDCLIENT, ANONYME)
VALUES (:idClient, :estAnonyme); 

-- Création des informations détaillées (si client non anonyme)
INSERT INTO INFORMATION_CLIENT
(EMAILCLIENT, NOMCLIENT, PRENOMCLIENT, NUMTELCLIENT, IDCLIENT)
VALUES (:email, :nom, :prenom, :tel, :idClient);

-- Création de l'adresse par défaut (si client non anonyme)
INSERT INTO ADRESSE (IDADRESSE, ADRESSEPOSTALE, VILLE, RUE, PAYS, IDCLIENT)
VALUES (:idAdresse, CONCAT(:rue, CONCAT(', ', CONCAT(:ville, CONCAT(', ', :pays)))), :ville, :rue, :pays, :idClient);

COMMIT;

--  PASSAGE DE COMMANDE (Cœur de la F1)
-- Les vérifications de stock total et de saisonnalité sont faites en amont (DAO).

--  Création de l'en-tête de la COMMANDE
INSERT INTO COMMANDE
(IDCOMMANDE, DATECOMMANDE, HEURECOMMANDE, STATUT, MODEPAIEMENT, IDCLIENT)
VALUES (SEQ_COMMANDE.NEXTVAL, SYSDATE, TO_CHAR(SYSDATE,'HH24:MI:SS'), 'En preparation', :modePaiement, :idClient);
-- Récupération de l'IDCOMMANDE généré

--  Insertion des LIGNES PRODUITS (pour chaque ligne)
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
VALUES (:idCommande, :numLigne, :idProduit, :quantite, :unite, :prixUnitaire, :sousTotal);

-- Insertion des LIGNES CONTENANTS (IDPRODUIT = 999)
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE)
VALUES (:idCommande, :numLigneContenant, 999, 1, 'Unite');

--  Mise à jour du stock de contenant
UPDATE CONTENANT
SET STOCKDISPOCONTENANT = STOCKDISPOCONTENANT - 1
WHERE IDCONTENANT = :idContenant;

-- Insertion Retrait (s'exécute si le mode est 'Retrait')
INSERT INTO RETRAIT_BOUTIQUE(idretraitboutique, idcommande)
SELECT :idCommande, :idCommande
FROM DUAL
WHERE :modeRecup = 'Retrait';

--  Insertion Livraison (s'exécute si le mode est 'Livraison')
INSERT INTO LIVRAISON_DOMICILE(idlivraisondomicile, idcommande, fraislivraison, idadresse)
SELECT :idCommande, :idCommande, 5, 1 -- 5 et 1 sont les valeurs par défaut
FROM DUAL
WHERE :modeRecup = 'Livraison';


-- Mise à jour des frais et de la date de livraison (S'exécute uniquement si modeRecup='Livraison')
UPDATE LIVRAISON_DOMICILE
SET FRAISLIVRAISON = :fraisLivraison,
    DATELIVRAISONESTIMEE = :dateEstimee
WHERE IDCOMMANDE = :idCommande
  AND :modeRecup = 'Livraison'; 


-- Déstockage (Pour chaque prélèvement de lot)
UPDATE LOT_PRODUIT
SET QUANTITESTOCKLOT = QUANTITESTOCKLOT - :quantitePrise
WHERE IDLOTPRODUIT = :idLot;
COMMIT;
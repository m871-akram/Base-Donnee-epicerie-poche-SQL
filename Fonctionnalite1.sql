-- =============================================================
-- FONCTIONNALITÉ 1 : PASSAGE D'UNE COMMANDE
-- Deux transactions distinctes : réservation (T-A) puis déstockage (T-B).
-- T-A crée la commande et réserve le stock sans le déduire.
-- T-B (marquerCommandePrete) déduit le stock réel et passe en 'Prete'.
-- =============================================================

-- =============================================================
-- TRANSACTION A : RÉSERVATION (statut → 'En preparation')
-- =============================================================

-- 1. Vérification saisonnalité
-- Un produit sans entrée dans PERIODE_DISPONIBILITE est disponible toute l'année.
SELECT COUNT(*) FROM PERIODE_DISPONIBILITE WHERE IDPRODUIT = :idProduit;
-- Si = 0 → toujours disponible, pas besoin de la requête ci-dessous.
-- Sinon, vérifier qu'une période active couvre SYSDATE :
SELECT COUNT(*)
FROM PERIODE p
JOIN PERIODE_DISPONIBILITE pd ON p.IDPERIODE = pd.IDPERIODE
WHERE pd.IDPRODUIT = :idProduit
  AND SYSDATE BETWEEN p.DEBUTPERIODE AND p.FINPERIODE;
-- Si = 0 → produit hors saison, ROLLBACK.

-- 2. Verrouillage du produit pour sérialiser les commandes concurrentes sur ce produit
SELECT IDPRODUIT FROM PRODUIT WHERE IDPRODUIT = :idProduit FOR UPDATE;

-- 3. Stock disponible = stock total - quantités déjà réservées par d'autres commandes
SELECT NVL(SUM(lp.QUANTITESTOCKLOT), 0) - NVL(
    (SELECT SUM(rs.QUANTITE) FROM RESERVATION_STOCK rs
     WHERE rs.IDLOTPRODUIT IN (
         SELECT IDLOTPRODUIT FROM LOT_PRODUIT
         WHERE IDPRODUIT = :idProduit AND DATEPEREMPTION > SYSDATE)), 0)
FROM LOT_PRODUIT lp
WHERE lp.IDPRODUIT = :idProduit AND lp.DATEPEREMPTION > SYSDATE;
-- Si résultat < quantité demandée → ROLLBACK.

-- 4. Création de la commande
INSERT INTO COMMANDE
(IDCOMMANDE, DATECOMMANDE, HEURECOMMANDE, STATUT, MODEPAIEMENT, IDCLIENT)
VALUES (SEQ_COMMANDE.NEXTVAL, SYSDATE, TO_CHAR(SYSDATE,'HH24:MI:SS'),
        'En preparation', :modePaiement, :idClient);

-- 5. Insertion d'une ligne de commande produit (répété pour chaque produit)
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
VALUES (SEQ_COMMANDE.CURRVAL, :numLigne, :idProduit, :quantite, :unite, :prixUnitaire, :sousTotal);

-- 6. Réservation FEFO : lots triés par péremption croissante, déduit des réservations existantes
--    Exécuter pour chaque lot jusqu'à épuisement de la quantité demandée :
SELECT lp.IDLOTPRODUIT,
       lp.QUANTITESTOCKLOT - NVL(SUM(rs.QUANTITE), 0) AS DISPONIBLE
FROM LOT_PRODUIT lp
LEFT JOIN RESERVATION_STOCK rs ON lp.IDLOTPRODUIT = rs.IDLOTPRODUIT
WHERE lp.IDPRODUIT = :idProduit AND lp.DATEPEREMPTION > SYSDATE
GROUP BY lp.IDLOTPRODUIT, lp.QUANTITESTOCKLOT, lp.DATEPEREMPTION
HAVING lp.QUANTITESTOCKLOT - NVL(SUM(rs.QUANTITE), 0) > 0
ORDER BY lp.DATEPEREMPTION ASC;
-- Pour chaque lot retourné, insérer dans RESERVATION_STOCK :
INSERT INTO RESERVATION_STOCK (IDCOMMANDE, IDLOTPRODUIT, QUANTITE)
VALUES (SEQ_COMMANDE.CURRVAL, :idLot, :quantiteReservee);

-- 7. Mode de récupération : Retrait en boutique
INSERT INTO RETRAIT_BOUTIQUE (IDRETRAITBOUTIQUE, IDCOMMANDE)
VALUES (SEQ_RETRAIT_BOUTIQUE.NEXTVAL, SEQ_COMMANDE.CURRVAL);
-- Ou livraison à domicile :
INSERT INTO LIVRAISON_DOMICILE (IDLIVRAISONDOMICILE, IDCOMMANDE, FRAISLIVRAISON, IDADRESSE)
VALUES (SEQ_LIVRAISON_DOMICILE.NEXTVAL, SEQ_COMMANDE.CURRVAL, 0, :idAdresse);
-- Puis mise à jour des frais et date estimée (si livraison) :
UPDATE LIVRAISON_DOMICILE
SET FRAISLIVRAISON = :fraisLivraison, DATELIVRAISONESTIMEE = :dateEstimee
WHERE IDCOMMANDE = SEQ_COMMANDE.CURRVAL;

-- 8. Contenant optionnel : ligne technique produit 999 + décrément stock contenant
INSERT INTO LIGNE_COMMANDE
(IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
VALUES (SEQ_COMMANDE.CURRVAL, :numLigneContenant, 999, 1, 'Unite', :prixContenant, :prixContenant);
UPDATE CONTENANT SET STOCKDISPOCONTENANT = STOCKDISPOCONTENANT - 1
WHERE IDCONTENANT = :idContenant AND STOCKDISPOCONTENANT >= 1;

COMMIT; -- libère le verrou sur PRODUIT, réservations visibles aux autres transactions


-- =============================================================
-- TRANSACTION B : PASSAGE EN PRÊTE (déstockage réel FEFO)
-- =============================================================

-- 1. Verrouillage de la commande (empêche une double transition concurrente)
SELECT STATUT FROM COMMANDE WHERE IDCOMMANDE = :idCommande FOR UPDATE;
-- Si STATUT <> 'En preparation' → ROLLBACK.

-- 2. Déstockage depuis les réservations, verrouillage des lignes de réservation
SELECT IDLOTPRODUIT, QUANTITE FROM RESERVATION_STOCK
WHERE IDCOMMANDE = :idCommande FOR UPDATE;
-- Pour chaque réservation retournée, déduire du lot (clause >= garantit la cohérence) :
UPDATE LOT_PRODUIT
SET QUANTITESTOCKLOT = QUANTITESTOCKLOT - :quantiteReservee
WHERE IDLOTPRODUIT = :idLot AND QUANTITESTOCKLOT >= :quantiteReservee;

-- 3. Suppression des réservations
DELETE FROM RESERVATION_STOCK WHERE IDCOMMANDE = :idCommande;

-- 4. Passage au statut Prête
UPDATE COMMANDE SET STATUT = 'Prete' WHERE IDCOMMANDE = :idCommande;

COMMIT;

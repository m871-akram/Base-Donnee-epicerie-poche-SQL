-- =============================================================
-- FONCTIONNALITÉ 2 : ALERTES DE PÉREMPTION ET AJUSTEMENT DES PRIX
-- =============================================================

-- 1. Consultation des alertes (lecture seule, hors transaction)
SELECT lp.IDLOTPRODUIT, p.NOMPRODUIT, lp.QUANTITESTOCKLOT, lp.DATEPEREMPTION
FROM LOT_PRODUIT lp
JOIN PRODUIT p ON lp.IDPRODUIT = p.IDPRODUIT
WHERE lp.DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7
ORDER BY lp.DATEPEREMPTION ASC;

-- 2. Récupération des produits concernés (dédoublonnés)
SELECT DISTINCT IDPRODUIT FROM LOT_PRODUIT
WHERE DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7;

-- 3. Ajustement des prix (répété pour chaque produit retourné en étape 2)
-- Pour les produits en vrac :
UPDATE VRAC
SET PRIXVENTEVRAC = PRIXVENTEVRAC * (1 - (:pourcentage / 100.0))
WHERE IDPRODUIT = :idProduit;

-- Pour les produits pré-conditionnés :
UPDATE PRECOND
SET PRIXVENTEP = PRIXVENTEP * (1 - (:pourcentage / 100.0))
WHERE IDPRODUIT = :idProduit;

COMMIT;

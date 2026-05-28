-- =============================================================
-- FONCTIONNALITÉ 3 : CLÔTURE D'UNE COMMANDE (Retrait ou Livraison)
-- =============================================================

-- 1. Verrouillage pessimiste de la commande (empêche une double clôture concurrente)
SELECT STATUT FROM COMMANDE WHERE IDCOMMANDE = :idCommande FOR UPDATE;
-- STATUT doit être 'En preparation', 'Prete' ou 'En livraison', sinon ROLLBACK.

-- 2. Récupération du mode de récupération et du mode de paiement
SELECT MODEPAIEMENT FROM COMMANDE WHERE IDCOMMANDE = :idCommande;
SELECT 1 FROM RETRAIT_BOUTIQUE WHERE IDCOMMANDE = :idCommande;   -- présent → Retrait
SELECT 1 FROM LIVRAISON_DOMICILE WHERE IDCOMMANDE = :idCommande; -- présent → Livraison

-- 3. Enregistrement de la date réelle (une seule des deux tables est concernée)
UPDATE RETRAIT_BOUTIQUE SET DATEREELLE = SYSDATE WHERE IDCOMMANDE = :idCommande;
-- ou :
UPDATE LIVRAISON_DOMICILE SET DATEREELLE = SYSDATE WHERE IDCOMMANDE = :idCommande;

-- 4. Mise à jour du statut final
UPDATE COMMANDE SET STATUT = 'Recupere' WHERE IDCOMMANDE = :idCommande; -- mode Retrait
-- ou :
UPDATE COMMANDE SET STATUT = 'Livree' WHERE IDCOMMANDE = :idCommande;   -- mode Livraison

-- Note : si mode de paiement = 'EN BOUTIQUE' et mode de récupération = 'Retrait',
-- DATEREELLE dans RETRAIT_BOUTIQUE sert de timestamp du paiement effectif.

COMMIT;

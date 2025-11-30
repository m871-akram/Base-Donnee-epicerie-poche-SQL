-- FONCTIONNALITÉ 3 (CLÔTURE DE COMMANDE) 

-- Verrouillage pessimiste de la commande et récupération du statut
-- Nécessite que le Database setAutoCommit(false) et qu'un commit soit lancé après.
SELECT STATUT FROM COMMANDE WHERE IDCOMMANDE = :idCommande FOR UPDATE;

-- Enregistrement de la date réelle 
-- Une seule des deux tables (RETRAIT_BOUTIQUE ou LIVRAISON_DOMICILE) sera affectée.
UPDATE RETRAIT_BOUTIQUE
SET DATEREELLE = SYSDATE
WHERE IDCOMMANDE = :idCommande;

UPDATE LIVRAISON_DOMICILE
SET DATEREELLE = SYSDATE
WHERE IDCOMMANDE = :idCommande;


-- Mise à jour du Statut Final (Utilisation de l'expression CASE)
UPDATE COMMANDE C
SET C.STATUT = (
    -- Détermine le statut final en vérifiant l'existence de la commande dans RETRAIT_BOUTIQUE
    CASE
        WHEN EXISTS (SELECT 1 FROM RETRAIT_BOUTIQUE R WHERE R.IDCOMMANDE = C.IDCOMMANDE) THEN 'Recupere'
        ELSE 'Livree' -- Si pas de retrait, c'est une livraison
    END
)
WHERE C.IDCOMMANDE = :idCommande;

COMMIT;
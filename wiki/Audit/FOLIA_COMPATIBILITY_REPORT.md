# Rapport de Compatibilité Folia - UniverseJobs v0.5.2

**Date:** 2026-04-28
**Analyste:** Builder 1
**Objectif:** Analyser la compatibilité Folia du plugin et documenter les findings

---

## 1. État Actuel - Analyse de l'Infrastructure

### 1.1 Dépendances (pom.xml)

| Dépendance | Version | Statut Folia | Notes |
|------------|---------|--------------|-------|
| Paper API | 1.21.11-R0.1-SNAPSHOT | ✅ Compatible | Version récente, supporte Folia |
| FoliaLib | 0.5.1 | ✅ Compatible | Shaded, relocalisé sous `fr.ax_dev.universejobs.lib.folialib` |
| Java | 21 | ✅ Compatible | Requis pour Paper 1.21+ |

**Bibliothèques intégrées (plugin.yml):**
- HikariCP 5.1.0 ✅ (Thread-safe)
- SQLite JDBC 3.46.1.3 ✅
- MySQL Connector 9.0.0 ✅

### 1.2 Métadonnées Plugin (plugin.yml)

```yaml
api-version: '1.21'        ✅
folia-supported: true       ✅
```

**Verdict infrastructure:** ✅ **PRÊT POUR FOLIA**

---

## 2. Analyse de FoliaCompatibilityManager

### 2.1 Architecture

Le `FoliaCompatibilityManager` est une couche d'abstraction bien conçue qui encapsule FoliaLib:

```java
public class FoliaCompatibilityManager {
    private final FoliaLib foliaLib;

    // Détection de plateforme
    public boolean isFolia()
    public boolean isPaper()
    public boolean isSpigot()

    // Scheduling universel
    void runNextTick(Runnable)
    void runAsync(Runnable)
    void runLater(Runnable, long)
    void runTimer(Runnable, long, long)
    void runTimerAsync(Runnable, long, long)

    // Scheduling Folia-aware
    void runAtLocation(Location, Runnable)
    void runAtEntity(Entity, Runnable)

    // Téléportation async
    CompletableFuture<Boolean> teleportAsync(Entity, Location)

    // Vérifications de région
    boolean isOwnedByCurrentRegion(Location)
    boolean isOwnedByCurrentRegion(Entity)
}
```

### 2.2 Points Forts

- ✅ API unifiée Paper/Folia/Spigot
- ✅ Support des schedulers Folia (RegionScheduler, EntityScheduler, GlobalRegionScheduler)
- ✅ Téléportation async avec CompletableFuture
- ✅ Vérifications de propriété de région (thread ownership)
- ✅ Documentation Java complète

**Verdict FoliaCompatibilityManager:** ✅ **EXCELLENTE IMPLEMENTATION**

---

## 3. Problèmes de Compatibilité Folia Identifiés

*Basé sur l'analyse de Scout 1 (T1) + revue de l'utilisation de FoliaCompatibilityManager*

### 3.1 CRITIQUES - Empêchent le fonctionnement sur Folia

| ID | Fichier | Ligne | Problème | Solution Prioritaire |
|----|---------|-------|----------|---------------------|
| C1 | CustomFishingEventListener.java | 26 | `HashMap<UUID, Long>` non thread-safe | Remplacer par `ConcurrentHashMap<UUID, Long>` |
| C2 | ExploreEventListener.java | 24 | `HashMap<UUID, Long>` non thread-safe | Remplacer par `ConcurrentHashMap<UUID, Long>` |
| C3 | AsyncMenuLoader.java | 44-52 | Accès concurrent aux données de plugin depuis thread async | Utiliser `foliaManager.runAtLocation()` ou snapshot immuable |
| C4 | SingleJobMenu.java | 601 | Appel `getAllPlayerData()` depuis thread async | Wrapper avec scheduler approprié |

**Impact:** Ces problèmes causent des `ConcurrentModificationException` et des race conditions sur Folia.

### 3.2 MAJEURS - Risques de race conditions

| ID | Fichier | Ligne | Problème | Solution |
|----|---------|-------|----------|----------|
| M1 | BlockProtectionManager.java | 88-120 | Accès PDC sans vérification `isOwnedByCurrentRegion()` | Ajouter check avant PDC access |
| M2 | SingleJobMenu.java | 612 | `Bukkit.getOfflinePlayer()` bloquant depuis calcul ranking | Précharger ou cacher les noms |

**Impact:** Risque d'échec d'accès PDC et performance dégradée.

### 3.3 MINEURS - Optimisations

- ✅ Tous les listeners utilisent `EventPriority.MONITOR` (correct pour Folia)
- ✅ `BrewEventListener.java:48` utilise `foliaManager.runLater()` correctement

---

## 4. Recommandations de Corrections

### Priorité P0 - Corrections Immédiates (Blockers Folia)

```java
// C1: CustomFishingEventListener.java:26
- private final Map<UUID, Long> lastFishingTime = new HashMap<>();
+ private final Map<UUID, Long> lastFishingTime = new ConcurrentHashMap<>();

// C2: ExploreEventListener.java:24
- private final Map<UUID, Long> lastChunkMove = new HashMap<>();
+ private final Map<UUID, Long> lastChunkMove = new ConcurrentHashMap<>();
```

```java
// C3: AsyncMenuLoader.java - Wrapper avec scheduler
- plugin.getJobManager().getJobs().values().forEach(job -> {
+ foliaManager.runAsync(() -> {
+     // Snapshot des jobs
+     var jobs = List.copyOf(plugin.getJobManager().getJobs().values());
+     jobs.forEach(job -> {
          // traitement
- });
+     });
+ });
```

```java
// C4: SingleJobMenu.java:601 - Prévenir accès concurrent
- plugin.getJobManager().getAllPlayerData().entrySet().stream()
+ CompletableFuture.supplyAsync(() ->
+     plugin.getJobManager().getAllPlayerData().entrySet().stream()
+     , foliaManager.getFoliaLib().getScheduler().asyncExecutor())
```

### Priorité P1 - Corrections Majeures

```java
// M1: BlockProtectionManager.java - Vérifier région avant PDC
if (!foliaManager.isOwnedByCurrentRegion(block.getLocation())) {
    foliaManager.runAtLocation(block.getLocation(), () -> processBlock(block));
    return;
}
```

```java
// M2: SingleJobMenu.java:612 - Cacher les OfflinePlayer
private final Map<UUID, OfflinePlayer> playerCache = new ConcurrentHashMap<>();
```

---

## 5. Vérification de l'Utilisation de FoliaCompatibilityManager

### 5.1 Recherche d'utilisation

Pour compléter l'analyse, j'ai vérifié comment `FoliaCompatibilityManager` est utilisé dans le codebase:

**Fichiers utilisant foliaManager:**
- `UniverseJobs.java` - Initialisation et injection
- `BrewEventListener.java` - `runLater()` ✅
- `CustomFishingEventListener.java` - PAS UTILISÉ (problème C1)
- `ExploreEventListener.java` - PAS UTILISÉ (problème C2)
- `AsyncMenuLoader.java` - `runAsync()` ✅
- `SingleJobMenu.java` - `runAtEntity()` ✅
- `BlockProtectionManager.java` - NON INJECTÉ (problème M1)

**Problème identifié:** Certains fichiers critiques n'ont pas accès à `foliaManager` ou ne l'utilisent pas correctement.

---

## 6. Verdict Final

### État Actuel

| Catégorie | Statut | Détails |
|-----------|--------|---------|
| Infrastructure | ✅ PRÊT | Paper API 1.21.11, FoliaLib 0.5.1, folia-supported: true |
| FoliaCompatibilityManager | ✅ EXCELLENT | API complète et bien conçue |
| Listeners | ⚠️ PARTIEL | 2 HashMap non thread-safe (CRITIQUE) |
| Menus | ⚠️ PARTIEL | Accès concurrent aux données (CRITIQUE) |
| Protection Manager | ⚠️ PARTIEL | Pas de vérification région (MAJEUR) |

### Conclusion

**Le plugin n'est PAS actuellement prêt pour une production Folia.**

Les fondations sont excellentes (FoliaCompatibilityManager, Paper API 1.21.11), mais des problèmes de concurrence critiques empêchent le fonctionnement correct sur Folia.

### Recommandation

**État:** ⚠️ **CONDITIONNEL**

**Conditions pour production Folia:**
1. ✅ Corriger les 2 HashMap non thread-safe (P0) - 5 minutes
2. ✅ Corriger les accès concurrents dans AsyncMenuLoader/SingleJobMenu (P0) - 30 minutes
3. ✅ Ajouter vérifications région dans BlockProtectionManager (P1) - 15 minutes
4. ✅ Tests sur serveur Folia de test (P1)

**Estimation temps total:** ~50 minutes pour atteindre la compatibilité Folia complète.

---

## 7. Plan d'Action Recommandé

### Phase 1: Corrections Critiques (P0) - ~35 min
1. Remplacer HashMap par ConcurrentHashMap dans CustomFishingEventListener
2. Remplacer HashMap par ConcurrentHashMap dans ExploreEventListener
3. Wrapper les accès concurrents dans AsyncMenuLoader
4. Wrapper les accès concurrents dans SingleJobMenu

### Phase 2: Corrections Majeures (P1) - ~15 min
5. Ajouter vérifications région dans BlockProtectionManager
6. Optimiser getOfflinePlayer() avec cache

### Phase 3: Validation (P2) - ~20 min
7. Tests sur serveur Folia local
8. Vérification TPS/MSPT sous charge
9. Validation des fonctionnalités jobs

### Phase 4: Documentation (P2) - ~10 min
10. Mettre à jour README avec notes Folia
11. Ajouter section troubleshooting Folia

**Total estimé:** ~80 minutes pour compatibilité Folia production-ready.

---

## Annexes

### A. Fichiers Requérant des Modifications

| Priorité | Fichier | Modifications |
|----------|---------|---------------|
| P0 | CustomFishingEventListener.java | HashMap → ConcurrentHashMap |
| P0 | ExploreEventListener.java | HashMap → ConcurrentHashMap |
| P0 | AsyncMenuLoader.java | Wrapper avec scheduler |
| P0 | SingleJobMenu.java | Wrapper avec scheduler |
| P1 | BlockProtectionManager.java | Ajouter vérification région |
| P1 | SingleJobMenu.java | Cache OfflinePlayer |

### B. Bonnes Pratiques Folia à Suivre

1. **Toujours utiliser ConcurrentHashMap** pour les états partagés
2. **Vérifier isOwnedByCurrentRegion()** avant d'accéder PDC/blocs
3. **Utiliser runAtLocation/runAtEntity** pour les opérations régionalisées
4. **Éviter les appels bloquants** depuis threads async (Bukkit.getOfflinePlayer)
5. **Préférer les snapshots immuables** aux collections mutables partagées

---

**Fin du rapport**

# WatchMate (Windows)

Client bureau de [WatchMate](https://github.com/ilyasseelmoulaoui/watchmate) : catalogues, fiches, lecture et synchronisation via les modules que tu installes. **Aucun contenu n’est fourni par l’application.**

## Prérequis
- JDK 17
- Windows x64

## Lancer
```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-17.0.3"
.\gradlew.bat :desktop:run
```

## Modules
| Module | Rôle |
|--------|------|
| `:core` | Logique partagée (Kotlin/JVM) |
| `:relay` | Relais local HTTP |
| `:desktop` | Interface Compose Desktop |

Voir `docs/HANDOFF.md` pour l’état et les prochaines tâches.

## Règles
Clean-room : pas de copie du produit qui a inspiré la spécification. Voir `AGENTS.md`.

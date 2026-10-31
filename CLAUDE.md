# REGELN FÜR DIE VERSIONIERUNG & BUILD-ERSTELLUNG

Für dieses Minecraft-Plugin trennen wir strikt zwischen Entwicklungs-Builds (Dev-Builds) und Release-Builds für Modrinth. Halte dich bei jeder Änderung an der Codebase zwingend an folgenden Workflow:

1. **Stabile Hauptversion:** Die aktuelle Hauptversionsnummer (z. B. `1.1`) darf von dir NIEMALS selbstständig geändert oder erhöht werden.
2. **Das Dev-Suffix:** Für alle laufenden Änderungen, Fehlerbehebungen und Code-Tests nutzt du ein Entwickler-Suffix. Das Format ist `[Hauptversion]-dev[Nummer]` (Beispiel: `1.1-dev1`, `1.1-dev2`).
3. **Automatisches Hochzählen:** Bei jedem neuen Build, den du generierst, zählst du *ausschließlich* die Nummer des Dev-Suffixes um +1 hoch. Aktualisiere diese Version in allen relevanten Dateien (z.B. `plugin.yml` und `build.gradle` bzw. `pom.xml`).
4. **Der Release-Befehl:** Erst wenn ich ausdrücklich den Befehl **"Baue das Release"** gebe, entfernst du das `-dev`-Suffix vollständig. Du trägst dann die reine Hauptversion (z.B. `1.1`) in die Dateien ein und erstellst die finale, saubere `.jar`-Datei.
5. **Nächster Zyklus:** Nachdem das Release gebaut wurde und neue Änderungen anstehen, fragst du mich, wie die nächste Hauptversion lauten soll, und wir starten wieder bei `-dev1`.

## Wo die Version in diesem Projekt steht

Bei jedem Build beide Stellen identisch halten:

- `pom.xml` → `<version>` des Projekts (bestimmt auch den JAR-Namen `target/StoneStats-<version>.jar`)
- `src/main/resources/plugin.yml` → `version: '<version>'`

Nur beim Release zusätzlich: `WIKI.md` → Zeile `**Version:** …` auf die neue Hauptversion setzen.

Aktuelle Hauptversion: `1.0.0`. Letzter Build: `1.0.0` (Release). Vor der nächsten Änderung nach der neuen Hauptversion fragen.

# REGELN FÜR NEUE CONFIG-OPTIONEN

`config.yml` und `messages.yml` werden beim Start von `ConfigUpdater` automatisch aktualisiert (aufgerufen in `ConfigManager.load()` bzw. `MessageManager.loadLanguage()`, vor dem Einlesen der Werte).

- **Neue Option:** nur in `src/main/resources/config.yml` (bzw. `messages.yml`) eintragen, mit erklärendem Kommentar direkt darüber. Keine Migration nötig – sie wird samt Kommentar in bestehende Dateien eingefügt.
- **Option umbenennen, verschieben oder entfernen:** `config-version` in `src/main/resources/config.yml` um 1 erhöhen und in `ConfigManager.MIGRATIONS` einen Schritt ergänzen (`ConfigMigration.rename(neueVersion, alt, neu)` bzw. `ConfigMigration.remove(neueVersion, pfad)`). Eine Version nie wiederverwenden.
- **Neue Item-Listen, die der Admin frei bearbeitet** (wie `gui.items`): Pfad in `ConfigManager.USER_OWNED_SECTIONS` eintragen, sonst kommen gelöschte Einträge zurück.
- Jede Änderung an diesem Verhalten mit `ConfigUpdaterTest` absichern.

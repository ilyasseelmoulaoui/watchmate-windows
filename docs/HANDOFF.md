# HANDOFF — WatchMate Windows

Last updated: 2026-10-24. Sibling of Android app `watchmate`. Read `AGENTS.md` first.

## 1. Goal
Full feature parity with WatchMate Android on Windows (desktop window, not an emulator).

## 2. Current state
Scaffold created:
- `:core` and `:relay` copied from Android (JVM logic + tests).
- `:desktop` Compose Multiplatform shell: navigation rail (Home / Explore / Library / Modules / Settings).
- Home loads catalog rows via `:core`; Modules install suggested + URL; Library lists saved entries; Settings: account sign-in/sync, theme, app language, About.
- Data dir: `%LOCALAPPDATA%\WatchMate\`.
- Same Supabase project as Android (library/modules sync across phone ↔ PC when signed in).

**Not yet:** title page, Explore UI, Media player (VLC planned), relay process + torrent engine wiring, watch-party UI/voice, deep links, Velours et Néon theme polish, MSI packaging verified on a clean PC.

## 3. Next tasks (take the first unfinished)
1. Wire Explore + title page + playable source list (reuse `TitleService` / `SourceRouter` from `:core`).
2. Desktop player with VLC (`vlcj`) for direct HTTPS/HLS; then relay + libtorrent Windows native for torrents/archives.
3. Port watch-party (`PartyManager` / Realtime) without Android VoiceChat first (chat + sync); WebRTC voice later if a JVM stack fits.
4. Visual identity Velours et Néon + FR/EN string resources.
5. `.\gradlew.bat :desktop:run` / package MSI; document VLC runtime requirement if using vlcj.

## 4. Build / run (PowerShell)
```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-17.0.3"   # any JDK 17
cd C:\Users\lenovo\Projects\watchmate-windows
.\gradlew.bat :core:test :relay:test
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:packageMsi   # after player/packaging is ready
```

## 5. Pitfalls
- Do not import AndroidX App / Media3 into `:desktop` — use Compose Multiplatform + VLC/libtorrent desktop artifacts.
- Keep `:core`/`:relay` in sync with Android when fixing protocol bugs (prefer cherry-pick / copy carefully).
- Anon Supabase key only; never service_role.

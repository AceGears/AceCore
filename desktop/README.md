# AceArcade desktop (Windows, macOS)

The desktop edition of AceArcade: the same game library, detection rules (shared with the Android app's
`core/detection` and `core/model` sources) and look, starting games with each platform's engines.

| Games | Windows | macOS |
|---|---|---|
| RPG Maker XP / VX / VX Ace, Pokémon Essentials | the game's own `Game.exe` | needs mkxp-z for macOS (not bundled yet) |
| RPG Maker 2000 / 2003 | EasyRPG Player (bundled) | EasyRPG Player (bundled) |
| RPG Maker MV / MZ, HTML5, Construct, TyranoScript | the game's own program, or the browser | the browser (served from the game folder on 127.0.0.1) |
| Godot | the game's own program | Godot 4.7 (bundled) with the game's `.pck` |
| Ren'Py | the game's own launcher | the game's macOS app / launcher, when the download includes it |
| Flash (.swf) | Ruffle (bundled) | Ruffle (bundled) |
| Unity, Unreal, GameMaker, WOLF RPG | the game's own program | not possible (Windows programs) |

Build: `./gradlew :desktop:run` (from source) · portable zips: `.github/workflows/desktop.yml` (`:desktop:createDistributable`, zipped; unzip and run `AceArcade.exe` on Windows or `AceArcade.app` on macOS — no installer).
The library is stored in `%APPDATA%\AceArcade` (Windows) or `~/Library/Application Support/AceArcade` (macOS).

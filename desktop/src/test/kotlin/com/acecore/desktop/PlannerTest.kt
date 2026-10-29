package com.acecore.desktop

import com.acecore.app.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlannerTest {
    private fun folder(vararg files: String): File = Files.createTempDirectory("game").toFile().apply {
        files.forEach { File(this, it).apply { parentFile.mkdirs(); writeText("x") } }
    }

    private fun runtimes(vararg names: Pair<String, String>): Runtimes {
        val root = Files.createTempDirectory("res").toFile()
        names.forEach { (rt, exe) -> File(root, "runtimes/$rt/$exe").apply { parentFile.mkdirs(); writeText("x") } }
        return Runtimes(root)
    }

    private fun game(dir: File, engine: Engine, entry: String? = null, exe: String? = null) =
        DesktopGame(1, "Test", dir.absolutePath, engine, entry = entry, exe = exe)

    @Test fun rgssOnWindowsRunsGameExe() {
        val d = folder("Game.exe", "Game.ini", "Data/Scripts.rxdata")
        val p = Planner(runtimes(), Platform.WINDOWS).plan(game(d, Engine.POKEMON_ESSENTIALS, "Game.ini", "Game.exe"))
        assertTrue(p is LaunchPlan.Process && p.command.single().endsWith("Game.exe"))
    }

    @Test fun rgssOnMacIsHonestlyUnsupportedWithoutMkxpz() {
        val d = folder("Game.exe", "Game.ini")
        val p = Planner(runtimes(), Platform.MAC).plan(game(d, Engine.RPG_MAKER_XP, "Game.ini", "Game.exe"))
        assertTrue(p is LaunchPlan.Unsupported && p.summary.contains("mkxp-z"))
    }

    @Test fun rm2kUsesBundledEasyRpgWithProjectPath() {
        val d = folder("RPG_RT.ldb", "RPG_RT.lmt")
        val p = Planner(runtimes("easyrpg" to "Player.exe"), Platform.WINDOWS).plan(game(d, Engine.RPG_MAKER_2K, "RPG_RT.ldb"))
        assertTrue(p is LaunchPlan.Process)
        p as LaunchPlan.Process
        assertEquals(listOf("--project-path", d.absolutePath), p.command.drop(1))
    }

    @Test fun webGameWithoutExeOpensInBrowser() {
        val d = folder("www/index.html", "www/js/rpg_core.js")
        val p = Planner(runtimes(), Platform.MAC).plan(game(d, Engine.RPG_MAKER_MV, "www/index.html").copy(contentRoot = "www"))
        assertTrue(p is LaunchPlan.Browser)
        assertEquals("index.html", (p as LaunchPlan.Browser).entry)
    }

    @Test fun windowsOnlyGameOnMacIsExplained() {
        val d = folder("Game.exe", "UnityPlayer.dll")
        val p = Planner(runtimes(), Platform.MAC).plan(game(d, Engine.UNITY, exe = "Game.exe"))
        assertTrue(p is LaunchPlan.Unsupported && p.summary.contains("Windows program"))
    }

    @Test fun flashUsesRuffle() {
        val d = folder("game.swf")
        val p = Planner(runtimes("ruffle" to "ruffle.exe"), Platform.WINDOWS).plan(game(d, Engine.FLASH, "game.swf"))
        assertTrue(p is LaunchPlan.Process && (p as LaunchPlan.Process).command.last().endsWith("game.swf"))
    }

    @Test fun scannerDetectsFolders() {
        val d = folder("Game.ini", "Data/Scripts.rxdata", "Game.exe")
        File(d, "Game.ini").writeText("[Game]\nLibrary=RGSS104E.dll\nScripts=Data\\Scripts.rxdata\nTitle=My Game\n")
        val g = Scanner.gameFor(d, 7)!!
        assertEquals(Engine.RPG_MAKER_XP, g.engine)
        assertEquals("Game.exe", g.exe)
    }

    @Test fun browserServerServesOnlyTheGameFolder() {
        val d = folder("index.html")
        val store = LibraryStore(Files.createTempFile("lib", ".json").toFile())
        val l = Launcher(store, Planner(runtimes()))
        val url = l.serve(d, "index.html")
        val ok = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(200, ok.responseCode)
        val bad = java.net.URI(url.substringBeforeLast('/') + "/..%2F..%2Fetc%2Fpasswd").toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(404, bad.responseCode)
        l.stop()
    }
}

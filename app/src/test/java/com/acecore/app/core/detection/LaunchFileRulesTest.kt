package com.acecore.app.core.detection

import com.acecore.app.core.install.GameLocations
import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchFileRulesTest {
    private val all: (EngineFamily) -> Boolean = { it == EngineFamily.WEB || it == EngineFamily.FLASH || it == EngineFamily.RGSS }
    private val essentialsRoot = listOf("Game.exe", "Game.ini", "Data", "Graphics", "Audio", "Fonts", "mkxp.json")

    private fun eval(path: String, siblings: List<String>, engine: Engine = Engine.POKEMON_ESSENTIALS, avail: (EngineFamily) -> Boolean = all) =
        LaunchFileRules.evaluate(engine, path, siblings, avail)

    @Test fun gameExeStartsFromGameIniNotTheExe() {
        val r = eval("Game.exe", essentialsRoot) as LaunchEvaluation.Supported
        assertEquals("Game.ini", r.entry)
        assertEquals(EngineFamily.RGSS, r.family)
        assertEquals("Game.ini", r.configFile)
        assertTrue(r.explanation.contains("Windows launcher"))
    }

    @Test fun gameIniIsDescribedAsConfigurationNotAProgram() {
        val r = eval("Game.ini", essentialsRoot) as LaunchEvaluation.Supported
        assertTrue(r.explanation.contains("configuration file, not a program"))
    }

    @Test fun keepsRealCaseOfIni() {
        val r = eval("game.exe", listOf("game.exe", "game.ini", "data")) as LaunchEvaluation.Supported
        assertEquals("game.ini", r.entry)
    }

    @Test fun gameInASubfolder() {
        val r = eval("Release/Game.exe", essentialsRoot) as LaunchEvaluation.Supported
        assertEquals("Release/Game.ini", r.entry)
        assertEquals("Release", GameLocations.rgssSubdir(r.entry))
    }

    @Test fun renamedExeWithoutGameIniIsExplained() {
        val r = eval("Pokemon.exe", listOf("Pokemon.exe", "Pokemon.ini", "Data")) as LaunchEvaluation.Unsupported
        assertTrue(r.reason.contains("only reads Game.ini"))
    }

    @Test fun nwjsExeOpensWebFiles() {
        val r = eval("Game.exe", listOf("Game.exe", "www", "package.json", "nw.dll"), Engine.RPG_MAKER_MV) as LaunchEvaluation.Supported
        assertEquals("www/index.html", r.entry)
        assertEquals(EngineFamily.WEB, r.family)
    }

    @Test fun plainWindowsProgramIsUnsupported() {
        assertTrue(eval("Setup.exe", listOf("Setup.exe", "readme.txt")) is LaunchEvaluation.Unsupported)
    }

    @Test fun rm2kIsExplained() {
        val r = eval("RPG_RT.exe", listOf("RPG_RT.exe", "RPG_RT.ldb", "RPG_RT.lmt")) as LaunchEvaluation.Unsupported
        assertTrue(r.reason.contains("2000/2003"))
    }

    @Test fun htmlAndSwfAreSupportedAnywhere() {
        assertEquals(EngineFamily.WEB, (eval("game/index.html", emptyList(), Engine.HTML5) as LaunchEvaluation.Supported).family)
        assertEquals(EngineFamily.FLASH, (eval("movie.swf", emptyList(), Engine.HTML5) as LaunchEvaluation.Supported).family)
    }

    @Test fun dataFilesAreNotLaunchFiles() {
        assertTrue(eval("Data/Map001.rxdata", emptyList()) is LaunchEvaluation.Unsupported)
        assertTrue(eval("Save1.rxdata", emptyList()) is LaunchEvaluation.Unsupported)
        val scripts = eval("Data/Scripts.rxdata", listOf("Scripts.rxdata")) as LaunchEvaluation.Supported
        assertEquals("Game.ini", scripts.entry)
    }

    @Test fun missingRuntimeIsReportedNotOffered() {
        val r = eval("Game.exe", essentialsRoot, avail = { it == EngineFamily.WEB })
        assertTrue(r is LaunchEvaluation.Unsupported)
    }

    @Test fun rgssVersionFromIni() {
        assertEquals(Engine.RPG_MAKER_VX_ACE, LaunchFileRules.rgssEngineFromIni("[Game]\nLibrary=System\\RGSS301.dll\nTitle=x"))
        assertEquals(Engine.RPG_MAKER_VX, LaunchFileRules.rgssEngineFromIni("Library=RGSS202E.dll"))
        assertEquals(Engine.RPG_MAKER_XP, LaunchFileRules.rgssEngineFromIni("Library=RGSS104E.dll"))
        assertEquals(null, LaunchFileRules.rgssEngineFromIni("Title=x"))
    }

    @Test fun rgssSubdirFromEntries() {
        assertEquals("", GameLocations.rgssSubdir("Game.ini"))
        assertEquals("", GameLocations.rgssSubdir("Data/Scripts.rxdata"))
        assertEquals("Game", GameLocations.rgssSubdir("Game/Data/Scripts.rvdata2"))
        assertEquals("a/b", GameLocations.rgssSubdir("a/b/Game.ini"))
        assertEquals("", GameLocations.rgssSubdir(null))
    }
}

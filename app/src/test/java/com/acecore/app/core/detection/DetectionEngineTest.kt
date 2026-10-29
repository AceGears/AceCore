package com.acecore.app.core.detection

import com.acecore.app.core.model.CompatStatus
import com.acecore.app.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEngineTest {

    /** Builds a snapshot from "path" or "path=content" strings; directories are inferred from file paths. */
    private fun snap(vararg files: String, binary: Map<String, ByteArray> = emptyMap()): GameSnapshot {
        val contents = mutableMapOf<String, ByteArray>()
        val entries = mutableListOf<SnapshotEntry>()
        val dirs = linkedSetOf<String>()
        for (f in files) {
            val path = f.substringBefore('=')
            val body = if (f.contains('=')) f.substringAfter('=') else ""
            contents[path] = body.toByteArray()
            entries += SnapshotEntry(path, false, body.length.toLong())
            var p = path.substringBeforeLast('/', "")
            while (p.isNotEmpty()) { dirs += p; p = p.substringBeforeLast('/', "") }
        }
        for ((path, bytes) in binary) {
            contents[path] = bytes
            entries += SnapshotEntry(path, false, bytes.size.toLong())
        }
        dirs.forEach { entries += SnapshotEntry(it, true) }
        return GameSnapshot(entries, reader = { p, max -> contents[p]?.let { it.copyOf(minOf(max, it.size)) } })
    }

    private val engine = DetectionEngine()

    @Test fun rpgMakerMvDesktopDeployment() {
        val r = engine.detect(
            snap(
                "Game.exe", "www/index.html=<html><head><title>x</title></head></html>",
                "www/js/rpg_core.js=Utils.RPGMAKER_VERSION = \"1.6.2\";", "www/js/rpg_managers.js", "www/js/plugins.js",
                "www/data/System.json={\"gameTitle\":\"Crimson Saga\",\"title1Name\":\"Castle\"}", "www/img/titles1/Castle.png",
            ),
            "crimson_saga",
        )
        assertEquals(Engine.RPG_MAKER_MV, r.engine)
        assertEquals("1.6.2", r.engineVersion)
        assertEquals("www", r.contentRoot)
        assertEquals("www/index.html", r.entry)
        assertEquals("Crimson Saga", r.title)
        assertEquals(CompatStatus.PLAYABLE_UNTESTED, r.status)
        assertTrue(r.coverCandidates.contains("www/img/titles1/Castle.png"))
        assertTrue(r.confidence >= 70)
    }

    @Test fun rpgMakerMzRootDeploymentIsCaseInsensitive() {
        val r = engine.detect(snap("index.html", "JS/RMMZ_CORE.JS", "js/rmmz_managers.js", "DATA/System.json={\"gameTitle\":\"Mz\"}"), "mz")
        assertEquals(Engine.RPG_MAKER_MZ, r.engine)
        assertEquals("", r.contentRoot)
        assertEquals("Mz", r.title)
    }

    @Test fun rpgMakerXpIsRecognisedButNotPlayable() {
        val r = engine.detect(snap("Game.exe", "Game.ini=[Game]\nLibrary=RGSS104E.dll\nTitle=Old Quest", "Data/Scripts.rxdata", "Data/Map001.rxdata", "RGSS104E.dll"), "x")
        assertEquals(Engine.RPG_MAKER_XP, r.engine)
        assertEquals(CompatStatus.RUNTIME_NOT_BUILT, r.status)
        assertEquals("Old Quest", r.title)
        assertFalse(r.launchCandidates.any { it.playable && it.kind == LaunchKind.EXE })
    }

    @Test fun pokemonEssentialsBuildsOnXp() {
        val r = engine.detect(
            snap("Game.ini=Library=RGSS104E.dll", "Data/Scripts.rxdata", "Data/PluginScripts.rxdata", "PBS/pokemon.txt",
                "Graphics/Pokemon/Front/PIKACHU.png", "Audio/SE/Cries/025Cry.ogg"),
            "pkmn",
        )
        assertEquals(Engine.POKEMON_ESSENTIALS, r.engine)
        assertEquals("v20+ (estimated)", r.engineVersion)
        assertTrue(r.tags.any { it.startsWith("base:") })
    }

    @Test fun renamedEssentialsGameUsesItsOwnIni() {
        val r = engine.detect(
            snap("Flux.ini=[Game]\r\nLibrary=RGSS104E.dll\r\nScripts=Data\\Scripts.rxdata\r\nTitle=Pokemon Flux\r\n", "Flux.exe",
                "Flux Patcher.exe", "Data/Scripts.rxdata", "PBS/pokemon.txt", "Graphics/Pokemon/Front/PIKACHU.png", "Audio/SE/Cries/025Cry.ogg"),
            "flux",
        )
        assertEquals(Engine.POKEMON_ESSENTIALS, r.engine)
        assertEquals("Flux.ini", r.entry)
    }

    @Test fun vxAceViaEncryptedArchive() {
        val archive = "RGSSAD".toByteArray() + byteArrayOf(0, 3) + ByteArray(8)
        val r = engine.detect(snap("Game.ini=Library=System\\RGSS301.dll", binary = mapOf("Game.rgss3a" to archive)), "ace")
        assertEquals(Engine.RPG_MAKER_VX_ACE, r.engine)
    }

    @Test fun renpyVersionFromVcVersion() {
        val r = engine.detect(snap("renpy/vc_version.py=version = '8.2.1.24030407'", "game/archive.rpa", "game/script.rpyc", "lib/py3-windows-x86_64/python.exe"), "vn")
        assertEquals(Engine.RENPY, r.engine)
        assertEquals("8.2.1.24030407", r.engineVersion)
    }

    @Test fun godotPckHeader() {
        val pck = "GDPC".toByteArray() + le(2) + le(4) + le(2) + le(1) + ByteArray(16)
        val r = engine.detect(snap("MyGame.exe", binary = mapOf("MyGame.pck" to pck)), "g")
        assertEquals(Engine.GODOT, r.engine)
        assertEquals("4.2.1", r.engineVersion)
    }

    @Test fun nativeOnlyExecutableIsExplainedNotCrashed() {
        val r = engine.detect(snap("Launcher.exe", "readme.txt"), "native")
        assertEquals(Engine.NATIVE_WINDOWS, r.engine)
        assertEquals(CompatStatus.UNSUPPORTED, r.status)
        assertTrue(r.diagnostics.any { it.severity == Severity.ERROR })
    }

    @Test fun electronAsarIsReportedAsPacked() {
        val r = engine.detect(snap("Game.exe", "resources/app.asar", "resources/elevate.exe"), "e")
        assertEquals(CompatStatus.PACKED, r.status)
    }

    @Test fun unpackedElectronConstruct3() {
        val r = engine.detect(snap("Game.exe", "resources/app/index.html", "resources/app/scripts/c3runtime.js", "resources/app/data.json"), "c3")
        assertEquals(Engine.CONSTRUCT_3, r.engine)
        assertEquals("resources/app", r.contentRoot)
        assertEquals("resources/app/index.html", r.entry)
    }

    @Test fun unityIsUnsupported() {
        val r = engine.detect(snap("Game.exe", "UnityPlayer.dll", "Game_Data/globalgamemanagers"), "u")
        assertEquals(Engine.UNITY, r.engine)
        assertEquals(CompatStatus.UNSUPPORTED, r.status)
    }

    @Test fun emptyFolderIsUnknown() {
        val r = engine.detect(snap("notes.txt"), "stuff")
        assertEquals(Engine.UNKNOWN, r.engine)
        assertFalse(r.isGame)
    }

    @Test fun genericHtml5Game() {
        val r = engine.detect(snap("index.html=<html><head><title>Tiny Jumper</title></head></html>", "game.js"), "tj")
        assertEquals(Engine.HTML5, r.engine)
        assertEquals("Tiny Jumper", r.title)
        assertNotNull(r.launchCandidates.firstOrNull { it.path == "index.html" && it.playable })
    }

    @Test fun exeResolvesToWebEntryForMv() {
        val pe = ByteArray(512).also {
            it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte(); it[0x3C] = 0x80.toByte()
            it[0x80] = 'P'.code.toByte(); it[0x81] = 'E'.code.toByte(); it[0x84] = 0x4C; it[0x85] = 0x01
        }
        val r = engine.detect(snap("www/index.html", "www/js/rpg_core.js", "www/data/System.json={}", binary = mapOf("Game.exe" to pe)), "mv")
        val exe = r.launchCandidates.first { it.kind == LaunchKind.EXE }
        assertTrue(exe.playable)
        assertEquals("www/index.html", exe.resolvedEntry)
    }

    @Test fun flashAs3GameIsPlayableWithWarning() {
        val swf = swfBytes(as3 = true, compressed = true)
        val r = engine.detect(snap("readme.txt", binary = mapOf("game.swf" to swf, "intro.swf" to swfBytes(as3 = false, compressed = false).copyOf(10))), "flashgame")
        assertEquals(Engine.FLASH, r.engine)
        assertEquals("game.swf", r.entry)
        assertEquals(CompatStatus.PLAYABLE_UNTESTED, r.status)
        assertEquals("SWF 10 · ActionScript 3", r.engineVersion)
        assertTrue(r.diagnostics.any { it.severity == Severity.WARNING && it.message.contains("ActionScript 3") })
        assertTrue(r.launchCandidates.any { it.kind == LaunchKind.SWF && it.playable })
    }

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    companion object {
        /** Minimal SWF: header, 5-bit RECT, frame rate/count, then a FileAttributes tag (code 69). */
        fun swfBytes(as3: Boolean, compressed: Boolean): ByteArray {
            val body = byteArrayOf(0x28, 0, 0, 0, 0, 24, 1, 0, 0x44, 0x11, (if (as3) 8 else 0).toByte(), 0, 0, 0) + ByteArray(64)
            val payload = if (compressed) {
                val d = java.util.zip.Deflater(); d.setInput(body); d.finish()
                val buf = ByteArray(512); val n = d.deflate(buf); d.end(); buf.copyOf(n)
            } else body
            val len = body.size + 8
            return byteArrayOf(if (compressed) 'C'.code.toByte() else 'F'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte(), 10,
                len.toByte(), (len shr 8).toByte(), 0, 0) + payload
        }
    }
}

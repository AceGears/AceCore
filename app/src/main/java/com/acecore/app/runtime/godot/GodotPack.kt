package com.acecore.app.runtime.godot

import java.io.File
import java.io.RandomAccessFile

/**
 * Finds and checks the Godot pack (.pck) a game runs from — without copying or changing any game file.
 *
 * A Windows/Linux Godot export is either `Game.exe` + `Game.pck`, or a single executable with the pack appended
 * ("embed PCK"). The pack's own header says which Godot version exported it; a pack only loads on the same major
 * version and the same or a newer minor version of the engine (core/io/file_access_pack.cpp).
 */
object GodotPack {
    /** Version of the Godot engine bundled in this build (org.godotengine:godot, see app/build.gradle.kts). */
    const val ENGINE_MAJOR = 4
    const val ENGINE_MINOR = 7
    const val ENGINE_LABEL = "Godot 4.7"

    private const val MAGIC = 0x43504447 // "GDPC" little-endian

    data class Pack(
        val file: File,
        /** Offset of the pack header inside [file] (0 for a separate .pck, > 0 when embedded in an executable). */
        val offset: Long,
        val format: Int,
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) {
        val embedded get() = offset > 0
        val version get() = "$major.$minor.$patch"
    }

    sealed class Check {
        data class Ok(val pack: Pack, val warnings: List<String>) : Check()
        data class Unsupported(val title: String, val message: String) : Check()
    }

    /** Reads a pack header at the start of [file], or appended to its end (embedded pack). */
    fun read(file: File): Pack? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            header(f, 0L, file) ?: run {
                val len = f.length()
                if (len < 16) return@run null
                f.seek(len - 4)
                if (le32(f) != MAGIC) return@run null
                f.seek(len - 12)
                val size = le64(f)
                val start = len - 12 - size
                if (size <= 0 || start < 0) null else header(f, start, file)
            }
        }
    }.getOrNull()

    private fun header(f: RandomAccessFile, at: Long, file: File): Pack? {
        if (f.length() < at + 20) return null
        f.seek(at)
        if (le32(f) != MAGIC) return null
        return Pack(file, at, le32(f), le32(f), le32(f), le32(f))
    }

    private fun le32(f: RandomAccessFile): Int {
        val b = ByteArray(4); f.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun le64(f: RandomAccessFile): Long {
        val lo = le32(f).toLong() and 0xFFFFFFFFL
        val hi = le32(f).toLong() and 0xFFFFFFFFL
        return lo or (hi shl 32)
    }

    /**
     * Picks the pack for the game in [dir]: the detected [entry] first, then *.pck files, then executables with an
     * embedded pack. Returns null when there is none.
     */
    fun find(dir: File, entry: String?): Pack? {
        val candidates = buildList {
            entry?.let { add(File(dir, it)) }
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            addAll(files.filter { it.name.endsWith(".pck", true) }.sortedByDescending { it.length() })
            addAll(files.filter { it.name.endsWith(".exe", true) || it.name.endsWith(".x86_64", true) || it.name.endsWith(".arm64", true) }
                .sortedByDescending { it.length() })
        }.distinct()
        return candidates.firstNotNullOfOrNull { if (it.isFile) read(it) else null }
    }

    /** Whether this build's engine can run the game in [dir], with an honest reason when it can't. */
    fun check(dir: File, entry: String?): Check {
        if (File(dir, "project.godot").isFile && find(dir, entry) == null) return Check.Unsupported(
            "Godot source project",
            "This folder is a Godot project's source (project.godot), not an exported game. Source projects must be " +
                "imported by the Godot editor before they can run; open it in Godot and export it, then add the export.",
        )
        val pack = find(dir, entry) ?: return Check.Unsupported(
            "No Godot pack found",
            "AceArcade didn't find the game's data (.pck file, or a pack inside the game's executable) in this folder.",
        )
        if (pack.major < ENGINE_MAJOR) return Check.Unsupported(
            "Godot ${pack.major} game",
            "This game was made with Godot ${pack.version}. AceArcade includes the $ENGINE_LABEL engine, which cannot " +
                "load Godot ${pack.major} games (their file formats differ). A Godot 3 runtime isn't included in this version.",
        )
        if (pack.major > ENGINE_MAJOR || pack.minor > ENGINE_MINOR) return Check.Unsupported(
            "Made with a newer Godot",
            "This game was made with Godot ${pack.version}, newer than the $ENGINE_LABEL engine in this version of AceArcade.",
        )
        val files = dir.listFiles().orEmpty()
        val dotnet = files.any { it.isDirectory && it.name.startsWith("data_", true) && it.name.contains("windows", true) } ||
            files.any { it.name.equals("GodotSharp.dll", true) }
        if (dotnet) return Check.Unsupported(
            "C# (.NET) Godot game",
            "This game uses Godot's C# (.NET) version. Its game code is compiled for Windows .NET and cannot run in " +
                "the Android Godot engine.",
        )
        val warnings = buildList {
            val dlls = files.filter { it.name.endsWith(".dll", true) }.map { it.name }
            if (dlls.isNotEmpty()) add(
                "The folder has Windows libraries (${dlls.take(4).joinToString()}). If the game uses them as Godot " +
                    "extensions (GDExtension), those parts can't load on Android and the game may stop with an error.",
            )
            if (pack.minor < ENGINE_MINOR) add(
                "Made with Godot ${pack.version}; running on $ENGINE_LABEL. Newer engines load older Godot 4 games, " +
                    "but behaviour changes between versions can affect some games.",
            )
        }
        return Check.Ok(pack, warnings)
    }
}

package com.acecore.app.runtime.windows

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/**
 * The Windows runtime: a small arm64 Linux user space (Debian) with Wine (x86_64 WoW64 build) and Box64, packed in
 * the APK (assets/windows/windows-rootfs.tar.xz) and unpacked into app storage on first use. It runs under PRoot,
 * which is shipped as native libraries (libproot.so, libproot-loader.so) because Android only lets apps execute
 * files from their native library folder. Built by CI (ci/windows); nothing here downloads anything.
 */
object WindowsRuntime {
    const val ASSET = "windows/windows-rootfs.tar.xz"
    const val VERSION_ASSET = "windows/WINDOWS_VERSION.txt"
    private const val READY = ".acearcade-installed"

    fun prootBinary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    fun prootLoader(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    fun home(context: Context) = File(context.filesDir, "windows")
    fun rootfs(context: Context) = File(home(context), "rootfs")
    /** Shared with the Linux side as /tmp (display/audio sockets, the runtime's status files and logs). */
    fun tmp(context: Context) = File(context.cacheDir, "windows-tmp")
    fun status(context: Context) = File(tmp(context), "acearcade")

    /** True when this APK contains the runtime (checked at startup; no unpacking). */
    fun isPresent(context: Context): Boolean =
        prootBinary(context).isFile && prootLoader(context).isFile &&
            runCatching { context.assets.open(ASSET).use { true } }.getOrDefault(false)

    fun bundledVersion(context: Context): String? =
        runCatching { context.assets.open(VERSION_ASSET).bufferedReader().readText().trim() }.getOrNull()

    fun installed(context: Context): Boolean {
        val marker = File(rootfs(context), READY)
        return marker.isFile && marker.readText().trim() == bundledVersion(context).orEmpty()
    }

    /**
     * Unpacks the runtime (about 1 GB unpacked) when this APK's version isn't installed yet. [progress] gets the bytes
     * read from the packed file and its total size. The previous version is replaced; the Windows settings folder
     * (Wine prefix, which holds games' Windows-side saves) is kept across updates.
     */
    @Throws(IOException::class)
    fun install(context: Context, progress: (Long, Long) -> Unit) {
        if (installed(context)) return
        val home = home(context).apply { mkdirs() }
        val target = rootfs(context)
        val staging = File(home, "rootfs.new")
        staging.deleteRecursively()
        staging.mkdirs()
        val total = runCatching { context.assets.openFd(ASSET).use { it.length } }.getOrDefault(-1L)
        val counting = CountingStream(context.assets.open(ASSET)) { progress(it, total) }
        val hardLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(XZInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
            var e: TarArchiveEntry? = tar.nextEntry
            val buf = ByteArray(1 shl 16)
            while (e != null) {
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isNotEmpty() && !name.split('/').contains("..")) {
                    val out = File(staging, name)
                    when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            Os.symlink(e.linkName, out.absolutePath)
                        }
                        e.isLink -> hardLinks += out to File(staging, e.linkName.removePrefix("./"))
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o ->
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                }
                            }
                            if (e.mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                        }
                        else -> Unit // device nodes and fifos aren't in the archive (PRoot binds /dev)
                    }
                }
                e = tar.nextEntry
            }
        }
        // Hard links become copies (Android's app storage refuses link()).
        for ((link, src) in hardLinks) {
            link.parentFile?.mkdirs()
            if (src.isFile) { src.copyTo(link, overwrite = true); if (src.canExecute()) link.setExecutable(true, false) }
        }
        // Keep the Wine prefix (Windows settings and saves written under C:\users) from the previous version.
        val oldPrefix = File(target, "root/.wine")
        if (oldPrefix.isDirectory) {
            File(staging, "root").mkdirs()
            oldPrefix.renameTo(File(staging, "root/.wine"))
        }
        File(staging, READY).writeText(bundledVersion(context).orEmpty())
        val old = File(home, "rootfs.old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("Couldn't move the Windows runtime into place")
        old.deleteRecursively()
    }

    /**
     * The process that runs [exe] (a path on the device, e.g. /storage/emulated/0/Games/X/Game.exe): PRoot with the
     * runtime as /, device storage visible at the same paths, and /tmp shared with the app.
     */
    fun processBuilder(context: Context, exe: File, width: Int, height: Int): ProcessBuilder {
        val tmp = tmp(context).apply { mkdirs() }
        status(context).mkdirs()
        val prootTmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val cmd = mutableListOf(
            prootBinary(context).absolutePath,
            "--kill-on-exit", "--root-id", "--link2symlink", "--sysvipc", "-L",
            "-r", rootfs(context).absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.absolutePath}:/tmp",
            "-w", "/root",
        )
        // Device storage at the same paths, so the game's folder needs no copying.
        listOf("/storage", "/sdcard").filter { File(it).exists() }.forEach { cmd += listOf("-b", it) }
        // Android doesn't let apps read some /proc files Linux programs expect; give them plain stand-ins (as
        // Termux's proot-distro does) instead of failing reads.
        val fakeProc = File(rootfs(context), "proc")
        mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n",
            "uptime" to "1000.00 900.00\n",
            "version" to "Linux version 6.1.0 (acearcade@android) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 100000\n",
        ).forEach { (name, text) ->
            if (!File("/proc/$name").canRead()) {
                val f = File(fakeProc, ".$name")
                runCatching { if (!f.isFile) f.writeText(text) }
                cmd += listOf("-b", "${f.absolutePath}:/proc/$name")
            }
        }
        cmd += listOf("/bin/sh", "/opt/acearcade/run.sh", exe.absolutePath, width.toString(), height.toString())
        return ProcessBuilder(cmd).redirectErrorStream(true).apply {
            val env = environment()
            env.clear()
            env["PROOT_LOADER"] = prootLoader(context).absolutePath
            env["PROOT_TMP_DIR"] = prootTmp.absolutePath
            env["HOME"] = "/root"
            env["PATH"] = "/usr/local/bin:/usr/bin:/bin"
            env["LANG"] = "C.UTF-8"
            env["TERM"] = "dumb"
        }
    }

    private class CountingStream(private val inner: java.io.InputStream, private val report: (Long) -> Unit) : java.io.InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) add(it.toLong()) }
        override fun close() = inner.close()
        private fun add(n: Long) {
            count += n
            if (count - lastReport > 1 shl 20) { lastReport = count; report(count) }
        }
    }
}

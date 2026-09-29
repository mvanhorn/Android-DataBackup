package com.xayah.core.data.repository

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.topjohnwu.superuser.Shell
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.Pm
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real [Pm.install] coverage. Pass a disposable fixture that is not this test package:
 * `-e fixtureApk <absolute-path> -e fixturePackage <name>` and optional `-e userId <id>`.
 * Put the fixture where the root shell can read it, for example `/data/local/tmp`.
 * Missing root or fixture prerequisites skip the test.
 *
 * `coverage=emulator` or `coverage=hardware` separates emulator runs from hardware.
 * A full single-APK restore through the app, including tmp cleanup, still needs that device.
 */
@RunWith(AndroidJUnit4::class)
class PmInstallTest {
    private lateinit var appContext: Context
    private var shellReady = false

    private val arguments get() = InstrumentationRegistry.getArguments()

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            runCatching { BaseUtil.initializeEnvironment(appContext) }
            shellReady = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        }
    }

    @After
    fun tearDown() {
        if (shellReady.not()) return
        runBlocking {
            removePrivateTree()
            BaseUtil.execute("rm", "-rf", q(SESSION_DIR))
            uninstallFixture()
        }
    }

    @Test
    fun streamsPrivateApkWhenPathInstallFails() = runBlocking {
        assumePrivileged()
        assumeFixture()
        assumeSdk(belowR = true)
        assumeEnforcing()
        assumeFixtureFile()
        println(deviceNote())
        try {
            uninstallFixture()
            val plain = stagePrivate("plain")
            val legacy = legacyPathInstall(plain)
            assertFalse(
                "Expected the pre-R path install to fail for a private APK on ${deviceNote()}\n${legacy.inputString}\n${legacy.outString}",
                legacy.isSuccess,
            )
            assertTrue(
                "Legacy path install failed without diagnostics\n${legacy.inputString}",
                legacy.outString.isNotBlank(),
            )
            assertFalse(legacy.inputString.contains(" -S "))
            assertTrue(legacy.inputString.endsWith(q(plain)))
            assertNotInstalled()

            assertInstalls(plain, preR = true)
            assertInstalls(plain, preR = true)

            val awkward = stagePrivate("o'brien dir")
            assertInstalls(awkward, preR = true)
        } finally {
            uninstallFixture()
            removePrivateTree()
        }
    }

    @Test
    fun installsSingleApkOnAndroid11AndAbove() = runBlocking {
        assumePrivileged()
        assumeFixture()
        assumeSdk(belowR = false)
        assumeFixtureFile()
        println(deviceNote())
        try {
            uninstallFixture()
            val plain = stagePrivate("plain")
            assertNotInstalled()
            assertInstalls(plain, preR = false)
            assertInstalls(plain, preR = false)

            val awkward = stagePrivate("o'brien dir")
            assertInstalls(awkward, preR = false)
        } finally {
            uninstallFixture()
            removePrivateTree()
        }
    }

    @Test
    fun splitSessionInstallsReadableApk() = runBlocking {
        assumePrivileged()
        assumeFixture()
        assumeFixtureFile()
        println(deviceNote())
        var session: String? = null
        var committed = false
        try {
            uninstallFixture()
            val dst = "$SESSION_DIR/base.apk"
            assertShell(BaseUtil.execute("mkdir", "-p", q(SESSION_DIR)))
            assertShell(BaseUtil.execute("cp", "-f", q(fixtureApk()), q(dst)))
            assertShell(BaseUtil.execute("chmod", "644", q(dst)))

            val created = Pm.Install.create(userId())
            assertTrue("${created.inputString}\n${created.outString}", created.isSuccess)
            val sessionId = created.outString.trim()
            session = sessionId
            assertTrue("Install session id was not numeric: '$sessionId'\n${created.outString}", sessionId.matches(Regex("\\d+")))

            val write = Pm.Install.write(session = sessionId, srcName = "base.apk", src = dst)
            assertTrue("${write.inputString}\n${write.outString}", write.isSuccess)
            val commit = Pm.Install.commit(sessionId)
            assertTrue("${commit.inputString}\n${commit.outString}", commit.isSuccess)
            committed = commit.isSuccess
            assertInstalled()
        } finally {
            val abandoned = session
            if (abandoned != null && committed.not()) {
                BaseUtil.execute("pm", "install-abandon", q(abandoned))
            }
            uninstallFixture()
            BaseUtil.execute("rm", "-rf", q(SESSION_DIR))
        }
    }

    @Test
    fun missingFileReturnsByteCountFailure() = runBlocking {
        assumePrivileged()
        println(deviceNote())
        val path = "$privateRoot/missing/base.apk"
        try {
            assertShell(BaseUtil.execute("mkdir", "-p", q(path.substringBeforeLast('/'))))
            val result = Pm.install(userId = userId(), src = path)
            assertFalse("${result.inputString}\n${result.outString}", result.isSuccess)
            assertTrue("Missing APK produced no diagnostic\n${result.inputString}", result.outString.isNotBlank())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                assertEquals("stat", result.input.firstOrNull())
                assertFalse("Unavailable byte count still ran install: ${result.inputString}", result.input.contains("install"))
                assertFalse(result.inputString, result.input.contains("-S"))
                assertTrue(
                    "Stat failure was replaced or dropped: ${result.outString}",
                    result.outString.contains("No such file") || result.outString.contains("stat:"),
                )
            }
        } finally {
            removePrivateTree()
        }
    }

    @Test
    fun emptyApkReturnsFailure() = runBlocking {
        assumePrivileged()
        println(deviceNote())
        val path = "$privateRoot/empty/base.apk"
        try {
            assertShell(BaseUtil.execute("mkdir", "-p", q(path.substringBeforeLast('/'))))
            assertShell(BaseUtil.execute("touch", q(path)))
            val result = Pm.install(userId = userId(), src = path)
            assertFalse("${result.inputString}\n${result.outString}", result.isSuccess)
            assertTrue(result.outString.isNotBlank())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                assertEquals("stat", result.input.firstOrNull())
                assertFalse("Empty APK produced an install command: ${result.inputString}", result.input.contains("install"))
                assertFalse(result.inputString, result.input.contains("-S"))
                assertTrue(result.outString, result.outString.contains("Invalid APK byte count"))
            }
        } finally {
            removePrivateTree()
        }
    }

    @Test
    fun invalidApkReturnsFailure() = runBlocking {
        assumePrivileged()
        println(deviceNote())
        val path = "$privateRoot/invalid/base.apk"
        try {
            assertShell(BaseUtil.execute("mkdir", "-p", q(path.substringBeforeLast('/'))))
            assertShell(BaseUtil.execute("printf", "%s", "not-an-apk", ">", q(path)))
            val length = shellByteCount(path)
            val result = Pm.install(userId = userId(), src = path)
            assertFalse("${result.inputString}\n${result.outString}", result.isSuccess)
            assertTrue("Invalid APK produced no diagnostic\n${result.inputString}", result.outString.isNotBlank())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                assertEquals("pm", result.input.firstOrNull())
                assertTrue(result.inputString, result.input.contains("install"))
                assertTrue(result.inputString, result.inputString.contains(" -- - < "))
                assertTrue("Quoted path was altered: ${result.inputString}", result.inputString.endsWith(q(path)))
                assertTrue(
                    "${result.inputString} expected=$length",
                    streamedBytes(result.inputString) == length,
                )
            }
        } finally {
            removePrivateTree()
        }
    }

    private suspend fun assertInstalls(path: String, preR: Boolean) {
        val result = Pm.install(userId = userId(), src = path)
        assertTrue(
            "Pm.install failed on ${deviceNote()}\n${result.inputString}\n${result.outString}",
            result.isSuccess,
        )
        assertTrue(result.inputString, result.inputString.contains(" -r "))
        assertTrue(result.inputString, result.inputString.contains(" -t "))
        assertTrue("Quoted path was altered: ${result.inputString}", result.inputString.endsWith(q(path)))
        if (preR) {
            assertEquals("pm", result.input.firstOrNull())
            assertTrue(result.inputString, result.input.contains("install"))
            assertTrue(result.inputString, result.inputString.contains(" -- - < "))
            assertFalse(result.inputString, result.inputString.contains("--bypass-low-target-sdk-block"))
            assertFalse(result.inputString, result.inputString.contains("com.android.vending"))
            val expectedBytes = shellByteCount(path)
            assertTrue(
                "${result.inputString} expected=$expectedBytes",
                streamedBytes(result.inputString) == expectedBytes,
            )
        } else {
            assertTrue(result.inputString, result.inputString.contains("-i com.android.vending"))
            assertFalse(result.inputString, result.inputString.contains(" -S "))
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                assertFalse(result.inputString, result.inputString.contains("--bypass-low-target-sdk-block"))
            } else {
                assertTrue(result.inputString, result.inputString.contains("--bypass-low-target-sdk-block"))
            }
        }
        assertInstalled()
    }

    private suspend fun legacyPathInstall(src: String): ShellResult = BaseUtil.execute(
        "pm",
        "install",
        "--user",
        q(userId().toString()),
        "-r",
        "-t",
        q(src),
    )

    private suspend fun stagePrivate(child: String): String {
        val dst = "$privateRoot/$child/base.apk"
        assertShell(BaseUtil.execute("mkdir", "-p", q(dst.substringBeforeLast('/'))))
        assertShell(BaseUtil.execute("cp", "-f", q(fixtureApk()), q(dst)))
        assertTrue("Staged APK is empty: $dst", shellByteCount(dst) > 0L)
        return dst
    }

    private suspend fun shellByteCount(path: String): Long {
        val result = BaseUtil.execute("wc", "-c", "<", q(path))
        assertTrue("${result.inputString}\n${result.outString}", result.isSuccess)
        val size = result.outString.trim().toLongOrNull()
        assertTrue("wc did not return a byte count for $path: ${result.outString}", size != null && size > 0L)
        return size ?: 0L
    }

    private suspend fun assertInstalled() {
        val result = queryInstalled()
        assertTrue(
            "Package ${fixturePackage()} is not installed for user ${userId()} on ${deviceNote()}\n${result.inputString}\n${result.outString}",
            result.isSuccess && result.outString.contains("package:"),
        )
    }

    private suspend fun assertNotInstalled() {
        val result = queryInstalled()
        assertFalse(
            "Package ${fixturePackage()} is already installed for user ${userId()}\n${result.outString}",
            result.isSuccess && result.outString.contains("package:"),
        )
    }

    private suspend fun queryInstalled(): ShellResult = BaseUtil.execute(
        "pm",
        "path",
        "--user",
        q(userId().toString()),
        q(fixturePackage()),
    )

    private suspend fun uninstallFixture() {
        val pkg = arguments.getString("fixturePackage")
        if (pkg.isNullOrBlank() || isProtectedPackage(pkg)) return
        BaseUtil.execute(
            "pm",
            "uninstall",
            "--user",
            q(userIdOrZero().toString()),
            q(pkg),
        )
    }

    private fun isProtectedPackage(pkg: String): Boolean {
        if (!::appContext.isInitialized) return true
        val self = setOf(
            appContext.packageName,
            InstrumentationRegistry.getInstrumentation().context.packageName,
        )
        return pkg in self || pkg == "android" || pkg.startsWith("com.android.")
    }

    private suspend fun removePrivateTree() {
        BaseUtil.execute("rm", "-rf", q(privateRoot))
    }

    private fun assertShell(result: ShellResult) {
        assertTrue("${result.inputString}\n${result.outString}", result.isSuccess)
    }

    private fun streamedBytes(input: String): Long {
        val match = Regex("""-S (\d+)""").find(input)
        assertTrue("Install command is missing a positive -S size: $input", match != null)
        return match!!.groupValues[1].toLong()
    }

    private fun assumePrivileged() {
        assumeTrue("Root shell is required on ${deviceNote()}; skipping", shellReady)
    }

    private fun assumeFixture() {
        val apk = arguments.getString("fixtureApk")
        val pkg = arguments.getString("fixturePackage").orEmpty()
        assumeTrue(
            "Pass -e fixtureApk and -e fixturePackage for a disposable APK. The test package is not a fixture.",
            apk.isNullOrBlank().not() && pkg.isNotBlank(),
        )
        assumeTrue(
            "Fixture package $pkg must be disposable and must not be the running test package",
            isProtectedPackage(pkg).not(),
        )
    }

    private suspend fun assumeFixtureFile() {
        val apk = fixtureApk()
        val result = BaseUtil.execute("test", "-f", q(apk))
        assumeTrue("Fixture APK is not a readable file: $apk\n${result.outString}", result.isSuccess)
    }

    private suspend fun assumeEnforcing() {
        val result = BaseUtil.execute("getenforce")
        assumeTrue(
            "SELinux enforcing is required to reproduce the private-path failure on ${deviceNote()}. out=${result.outString}",
            result.isSuccess && result.outString.trim().equals("Enforcing", ignoreCase = true),
        )
    }

    private fun assumeSdk(belowR: Boolean) {
        val isBelowR = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        assumeTrue(
            if (belowR) {
                "Private-path streaming is the pre-Android 11 branch. This device is ${deviceNote()}"
            } else {
                "Path install flags are the Android 11+ branches. This device is ${deviceNote()}"
            },
            if (belowR) isBelowR else isBelowR.not(),
        )
    }

    private fun fixtureApk(): String = arguments.getString("fixtureApk").orEmpty()

    private fun fixturePackage(): String = arguments.getString("fixturePackage").orEmpty()

    private fun userId(): Int {
        val raw = arguments.getString("userId")
        if (raw.isNullOrBlank()) return 0
        val parsed = raw.toIntOrNull()
        assumeTrue("userId must be an integer, was $raw", parsed != null)
        return parsed ?: 0
    }

    private fun userIdOrZero(): Int = arguments.getString("userId")?.toIntOrNull() ?: 0

    private val privateRoot: String
        get() = "${appContext.filesDir.absolutePath}/pm-install-test"

    private fun q(value: String): String = "$QUOTE$value$QUOTE"

    private fun isEmulator(): Boolean = Build.FINGERPRINT.contains("generic")
        || Build.FINGERPRINT.contains("emulator")
        || Build.HARDWARE.contains("goldfish")
        || Build.HARDWARE.contains("ranchu")
        || Build.MODEL.contains("Emulator")
        || Build.MODEL.contains("Android SDK built for")

    private fun deviceNote(): String {
        val kind = if (isEmulator()) "emulator" else "hardware"
        return "coverage=$kind manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}"
    }

    private companion object {
        const val SESSION_DIR = "/data/local/tmp/databackup-apk-install-test"
    }
}

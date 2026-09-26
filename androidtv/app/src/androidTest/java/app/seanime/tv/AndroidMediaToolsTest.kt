package app.seanime.tv

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaToolsTest {
    @Test
    fun bundledToolsExecuteFromTheirInstalledNativeLibraryDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("ffmpeg", "ffprobe")) {
            val command = File(context.filesDir, "seanime/bin/$name")
            val packaged = File(context.applicationInfo.nativeLibraryDir, "lib$name.so")
            assertEquals("$name must point to the installed APK binary", packaged.canonicalFile, command.canonicalFile)
            assertTrue("$name is not executable", command.canExecute())

            val process = ProcessBuilder(command.absolutePath, "-version").redirectErrorStream(true).start()
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var exitCode: Int? = null
                while (exitCode == null && SystemClock.elapsedRealtime() < deadline) {
                    exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exitCode == null) SystemClock.sleep(50)
                }
                assertEquals("$name did not exit successfully", 0, exitCode)
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue("unexpected $name output: $output", output.startsWith("$name version"))
            } finally {
                process.destroy()
            }
        }
    }
}

package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendDefaultsTest {
    private val tailscale = "http://100.116.13.37:8080"
    private val emu = BackendDefaults.EMULATOR_URL

    @Test fun detectsEmulators() {
        assertTrue(BackendDefaults.isEmulator("generic_x86/sdk_gphone64_arm64/emu64a:16/BE2A/123:userdebug/dev-keys", "ranchu", "sdk_gphone64_arm64"))
        assertTrue(BackendDefaults.isEmulator("generic/vbox86p/vbox86p:7.0/NRD90M", "vbox86", "vbox86p"))
        assertTrue(BackendDefaults.isEmulator("google/sdk_gphone_x86/generic:11", "x", "x"))
        assertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "goldfish", "foo"))
        assertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "ranchu", "foo"))
        assertTrue(BackendDefaults.isEmulator("samsung/foo/bar", "qcom", "sdk_foo"))
        assertTrue(BackendDefaults.isEmulator("Google/Android-EMULATOR/x", "qcom", "foo"))
    }

    @Test fun realPhoneIsNotAnEmulator() {
        assertFalse(BackendDefaults.isEmulator("samsung/r11sxx/r11s:16/BP2A.250605.031.A3/S721BXXU1AYE1:user/release-keys", "s5e9945", "r11sxx"))
        assertFalse(BackendDefaults.isEmulator("google/husky/husky:15/AP3A:user/release-keys", "husky", "husky"))
        assertFalse(BackendDefaults.isEmulator(null, null, null))
        assertFalse(BackendDefaults.isEmulator("", "", ""))
    }

    @Test fun defaultsPerEnvironment() {
        assertEquals(emu, BackendDefaults.defaultFor(true, tailscale))
        assertEquals(tailscale, BackendDefaults.defaultFor(false, tailscale))
        assertEquals(emu, BackendDefaults.defaultFor(false, "  ")) // blank build default falls back to the emulator alias
    }

    @Test fun nothingStoredUsesDefault() {
        assertEquals(tailscale, BackendDefaults.resolve(null, false, tailscale))
        assertEquals(tailscale, BackendDefaults.resolve("", false, tailscale))
        assertEquals(tailscale, BackendDefaults.resolve("   ", false, tailscale))
        assertEquals(emu, BackendDefaults.resolve(null, true, tailscale))
    }

    @Test fun legacyEmulatorDefaultIsMigratedOnRealPhones() {
        assertEquals(tailscale, BackendDefaults.resolve(emu, false, tailscale))
        assertEquals(tailscale, BackendDefaults.resolve("$emu/", false, tailscale))
        assertEquals(tailscale, BackendDefaults.resolve(" $emu ", false, tailscale))
    }

    @Test fun emulatorKeepsItsStoredEmulatorUrl() {
        assertEquals(emu, BackendDefaults.resolve(emu, true, tailscale))
    }

    @Test fun explicitUserChoicesAreKept() {
        assertEquals(tailscale, BackendDefaults.resolve(tailscale, false, "https://relay.example.run.app"))
        assertEquals("http://192.168.1.20:8080", BackendDefaults.resolve("http://192.168.1.20:8080", false, tailscale))
        assertEquals("http://192.168.1.20:8080", BackendDefaults.resolve("http://192.168.1.20:8080", true, tailscale))
    }

    @Test fun hostOfStripsSchemeAndPath() {
        assertEquals("100.116.13.37:8080", BackendDefaults.hostOf("http://100.116.13.37:8080"))
        assertEquals("100.116.13.37:8080", BackendDefaults.hostOf("http://100.116.13.37:8080/"))
        assertEquals("relay.run.app", BackendDefaults.hostOf("https://relay.run.app/v1/x?y=1"))
        assertEquals("10.0.2.2:8080", BackendDefaults.hostOf(" 10.0.2.2:8080 "))
    }
}

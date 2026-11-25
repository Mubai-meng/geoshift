package app.geoshift.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimConfigTest {
    @Test fun roundTrip() {
        val c = SimConfig(bootAt = 7, writtenAt = 99, active = true, route = RouteAnchor("r", 1.0, 2, 3.0, Repeat.BOUNCE),
            wifi = WifiSpec("Cafe", "aa:bb:cc:dd:ee:ff"), cells = listOf(CellSpec(CellType.GSM, "460", "00", 1234, 22)),
            selectedOnly = true, packages = listOf("com.example"))
        assertEquals(c, SimConfig.decode(c.encode()))
    }

    @Test fun nrRoundTripPreserves36BitIdentity() {
        val cell = CellSpec(CellType.NR, "460", "00", 68719476735L, 16777215, 1007, 3279165, -85)
        val config = SimConfig(cells = listOf(cell))
        assertEquals(config, SimConfig.decode(config.encode()))
        assertTrue(app.geoshift.data.Validate.cellOk(cell))
        assertFalse(app.geoshift.data.Validate.cellOk(cell.copy(cid = 68719476736L)))
        assertFalse(app.geoshift.data.Validate.cellOk(cell.copy(pci = 1008)))
    }

    @Test fun scannedCellWithoutSignalKeepsIdentity() {
        val scanned = app.geoshift.data.ScannedCell("NR", "460", "01", 45678912345L, 12345, 500, 627264, Int.MAX_VALUE)
        assertEquals(CellSpec(CellType.NR, "460", "01", 45678912345L, 12345, 500, 627264, -85, registered = false), scanned.toSpec())
        assertEquals(null, scanned.copy(cid = null).toSpec())
    }

    @Test fun unknownFieldsAreIgnored() {
        assertEquals(SimConfig(bootAt = 3), SimConfig.decode("""{"bootAt":3,"future":true}"""))
    }

    @Test fun sameBootOnlyWithinTolerance() {
        val boot = SimConfig.bootMoment(wallMs = 1_700_000_100_000, elapsedMs = 100_000)
        val c = SimConfig(bootAt = boot, active = true)
        // Same boot, read later: both clocks advanced together.
        assertTrue(c.sameBoot(SimConfig.bootMoment(1_700_003_700_000, 3_700_000)))
        // After a reboot the boot moment moves by the whole previous uptime plus the restart.
        assertFalse(c.sameBoot(SimConfig.bootMoment(1_700_003_760_000, 20_000)))
        // A config that never recorded its boot is never applied.
        assertFalse(SimConfig(active = true).sameBoot(boot))
    }

    @Test fun derivedSwitches() {
        val off = SimConfig()
        assertFalse(off.locationOn || off.gnssOn || off.cellsOn || off.wifiOn)
        val hidden = SimConfig(active = true)
        assertTrue(hidden.environmentHidden && hidden.gnssOn && hidden.cellsOn && hidden.wifiOn)
        val shown = hidden.copy(hideEnvironment = false)
        assertFalse(shown.gnssOn || shown.cellsOn || shown.wifiOn)
        val scanOnly = SimConfig(wifiScan = listOf(WifiSpec("AP1", "aa:bb:cc:dd:ee:01")))
        assertTrue(scanOnly.wifiOn)
    }

    @Test fun bootCountSurvivesNtpClockJumpAndRejectsReboot() {
        val boot = SimConfig.bootMoment(wallMs = 1_700_000_100_000, elapsedMs = 100_000)
        val c = SimConfig(bootAt = boot, bootCount = 12, active = true)
        // NTP shifted wall clock by 5 minutes (> 60s tolerance), still same bootCount -> valid.
        val shifted = SimConfig.bootMoment(wallMs = 1_700_000_400_000, elapsedMs = 100_000)
        assertTrue(c.sameBoot(shifted, currentBootCount = 12))
        // After reboot bootCount increments -> rejected even if bootAt were close.
        assertFalse(c.sameBoot(boot, currentBootCount = 13))
    }

    @Test fun multiApWifiScanAndRouteContentHashRoundTrip() {
        val aps = listOf(
            WifiSpec("Office-5G", "aa:bb:cc:dd:ee:01", -52, 5180),
            WifiSpec("Guest", "aa:bb:cc:dd:ee:02", -68, 2437),
        )
        val c = SimConfig(
            bootAt = 10, bootCount = 5, active = true,
            wifi = aps.first(), wifiScan = aps,
            route = RouteAnchor("route-1", 12.5, 5000L, 3.0, Repeat.LOOP, traffic = 99L, contentHash = "2-abc123"),
        )
        val decoded = SimConfig.decode(c.encode())
        assertEquals(c, decoded)
        assertEquals("2-abc123", decoded.route?.contentHash)
        assertEquals(2, decoded.wifiScan.size)
    }
}

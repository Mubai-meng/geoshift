package app.geoshift.data

import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxAndModelsTest {
    @Test
    fun gpxExportAndParseRoundTrip() {
        val pts = listOf(
            LatLon(39.9042, 116.4074),
            LatLon(39.9142, 116.4174),
            LatLon(39.9242, 116.4274),
        )
        val xml = Gpx.export("朝阳公园 & 跑步 <5km>", pts)
        val parsed = Gpx.parse(xml)
        assertEquals("朝阳公园 & 跑步 <5km>", parsed.name)
        assertEquals(pts.size, parsed.points.size)
        for (i in pts.indices) {
            assertEquals(pts[i].lat, parsed.points[i].lat, 1e-6)
            assertEquals(pts[i].lon, parsed.points[i].lon, 1e-6)
        }
    }

    @Test
    fun gpxSupportsRoutePointsAndSkipsInvalidCoordinates() {
        val xml = """
            <gpx version="1.1">
              <rte>
                <name>通勤路线</name>
                <rtept lat="31.2304" lon="121.4737"/>
                <rtept lat="999.0" lon="121.48"/>
                <rtept lon="121.4837" lat="31.2404"/>
              </rte>
            </gpx>
        """.trimIndent()
        val parsed = Gpx.parse(xml)
        assertEquals("通勤路线", parsed.name)
        assertEquals(2, parsed.points.size)
        assertEquals(31.2404, parsed.points[1].lat, 1e-6)
    }

    @Test(expected = UserError::class)
    fun gpxRejectsNonGpxContent() {
        Gpx.parse("""{"not":"gpx"}""")
    }

    @Test
    fun positionNameUsesBoundingBoxAndPicksClosestPlace() {
        val base = LatLon(39.9042, 116.4074)
        val near = Geo.move(base, 10.0, 90.0)
        val closer = Geo.move(base, 4.0, 180.0)
        val far = Geo.move(base, 150.0, 0.0)
        val places = listOf(
            SavedPlace("1", "远处", far),
            SavedPlace("2", "较近", near),
            SavedPlace("3", "最近", closer),
        )
        assertEquals("最近", positionName(Runtime(position = base), places, emptyList()))
        assertEquals("", positionName(Runtime(position = base, mode = SimMode.ROUTE), places, emptyList()))
        assertEquals("", positionName(Runtime(position = LatLon(31.23, 121.47)), places, emptyList()))
    }

    @Test
    fun diagnosticsClassifiesOptionalMotionChannel() {
        val unlinked = Diagnostics.classify(
            key = "motion", report = null, simActive = true,
            hideEnvironment = true, wifiEnabled = false, sessionStartedAt = 1000L, nowMs = 2000L,
        )
        assertEquals("未联动", unlinked.label)
        assertEquals(DiagnosticTone.MUTED, unlinked.tone)

        val linked = Diagnostics.classify(
            key = "motion",
            report = Report(ok = true, time = 1500L, stage = "read", detail = "ok", process = "system_server"),
            simActive = true, hideEnvironment = true, wifiEnabled = false, sessionStartedAt = 1000L, nowMs = 2000L,
        )
        assertEquals("已命中", linked.label)
        assertTrue(linked.isHealthy)
    }
}

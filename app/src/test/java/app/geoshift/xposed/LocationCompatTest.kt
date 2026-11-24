package app.geoshift.xposed

import android.location.Location
import app.geoshift.xposed.system.LocationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocationCompatTest {
    @Before
    fun clearMissing() {
        Reflect.missing.clear()
        Reflect.missingAt.clear()
    }

    @Suppress("unused")
    private class FakeIdentity(private val mUid: Int, private val mPackageName: String) {
        fun getUid(): Int = mUid
        fun getPackageName(): String = mPackageName
    }

    @Suppress("unused")
    private class FieldOnlyIdentity(val mUid: Int, val mPackageName: String)

    @Suppress("unused")
    private open class BaseRegWithMethod(private val id: FakeIdentity) {
        protected fun getIdentity(): FakeIdentity = id
    }

    private class ChildRegWithMethod(id: FakeIdentity) : BaseRegWithMethod(id)

    @Suppress("unused")
    private open class BaseRegWithFieldOnly(protected val mIdentity: FieldOnlyIdentity)

    private class ChildRegWithFieldOnly(id: FieldOnlyIdentity) : BaseRegWithFieldOnly(id)

    @Suppress("unused")
    private class RegWithCallerIdentity(private val caller: FakeIdentity) {
        fun getCallerIdentity(): FakeIdentity = caller
    }

    @Suppress("unused")
    private class RegWithoutIdentity {
        val mOther = 42
    }

    @Suppress("unused")
    private class LpmAosp14 {
        fun getLastLocation(flags: Int): Location? = null
    }

    @Suppress("unused")
    private class LpmVariantUnsafe {
        fun getLastLocationUnsafe(userId: Int, permissionLevel: Int): Location? = null
    }

    @Suppress("unused")
    private class LpmMissingLast {
        fun getLastLocationMetadata(): String = ""
    }

    @Test
    fun resolvesGetIdentityFromSuperclassWithoutPollutingMissing() {
        val reg = ChildRegWithMethod(FakeIdentity(10123, "com.example.maps"))
        val info = LocationCompat.resolveIdentity(reg)
        assertNotNull(info)
        assertEquals(10123, info!!.uid)
        assertEquals("com.example.maps", info.packageName)
        assertEquals("method:getIdentity", info.resolvedBy)
        assertTrue(Reflect.missing.isEmpty())
    }

    @Test
    fun fallsBackToFieldIdentityWhenGetIdentityMethodRemoved() {
        val reg = ChildRegWithFieldOnly(FieldOnlyIdentity(10456, "com.example.run"))
        val info = LocationCompat.resolveIdentity(reg)
        assertNotNull(info)
        assertEquals(10456, info!!.uid)
        assertEquals("com.example.run", info.packageName)
        assertEquals("field:mIdentity", info.resolvedBy)
        assertTrue(Reflect.missing.isEmpty())
    }

    @Test
    fun fallsBackToGetCallerIdentity() {
        val reg = RegWithCallerIdentity(FakeIdentity(10789, "com.example.caller"))
        val info = LocationCompat.resolveIdentity(reg)
        assertNotNull(info)
        assertEquals(10789, info!!.uid)
        assertEquals("com.example.caller", info.packageName)
        assertEquals("method:getCallerIdentity", info.resolvedBy)
    }

    @Test
    fun returnsNullWhenIdentityCompletelyAbsentWithoutPollutingMissing() {
        val info = LocationCompat.resolveIdentity(RegWithoutIdentity())
        assertNull(info)
        assertTrue(Reflect.missing.isEmpty())
    }

    @Test
    fun discoversStandardAndUnsafeLastLocationVariants() {
        val standard = LocationCompat.discoverLastLocationMethods(LpmAosp14::class.java)
        assertEquals(listOf("getLastLocation"), standard.map { it.name })

        val unsafe = LocationCompat.discoverLastLocationMethods(LpmVariantUnsafe::class.java)
        assertEquals(listOf("getLastLocationUnsafe"), unsafe.map { it.name })

        val missing = LocationCompat.discoverLastLocationMethods(LpmMissingLast::class.java)
        assertTrue(missing.isEmpty())
        val desc = LocationCompat.describeLocationCandidates(LpmMissingLast::class.java)
        assertTrue("Expected candidate method signature in diagnostic: $desc", desc.contains("getLastLocationMetadata"))
    }

    @Test
    fun recognizesGeoshiftUidAcrossSecondaryUsers() {
        // Secondary user (e.g. Second Space userId=10, appId=10234 -> uid=1010234)
        assertTrue(
            app.geoshift.xposed.system.SystemSide.isSelfUid(
                uid = 1_010_234,
                packagesForUid = { if (it == 1_010_234) arrayOf(Bridge.SELF) else null },
                primarySelfUid = { null },
            ),
        )
        // Fallback by appId when PackageManager only answers User 0
        assertTrue(
            app.geoshift.xposed.system.SystemSide.isSelfUid(
                uid = 99_910_234,
                packagesForUid = { if (it == 10_234) arrayOf(Bridge.SELF) else null },
                primarySelfUid = { 10_234 },
            ),
        )
        // System UID or another app in secondary space must NOT match
        assertTrue(
            !app.geoshift.xposed.system.SystemSide.isSelfUid(
                uid = 1_001_000,
                packagesForUid = { arrayOf(Bridge.SELF) },
                primarySelfUid = { 10_234 },
            ),
        )
        assertTrue(
            !app.geoshift.xposed.system.SystemSide.isSelfUid(
                uid = 1_010_999,
                packagesForUid = { arrayOf("com.other.app") },
                primarySelfUid = { 10_234 },
            ),
        )
    }
}

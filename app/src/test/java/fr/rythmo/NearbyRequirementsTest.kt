package fr.rythmo

import android.Manifest
import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import com.google.android.gms.common.GoogleApiAvailability
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@Implements(GoogleApiAvailability::class)
class AvailablePlayServices {
 @Implementation fun isGooglePlayServicesAvailable(context:Context):Int=0
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[AvailablePlayServices::class])
class NearbyRequirementsTest {
 @Test fun `nearby prerequisites distinguish missing permissions Bluetooth and WiFi`() {
  val app=RuntimeEnvironment.getApplication()
  shadowOf(app).denyPermissions(*NearbyRequirements.permissions())
  assertTrue(NearbyRequirements.problem(app)!!.contains("Autorisez"))
  shadowOf(app).grantPermissions(*NearbyRequirements.permissions())
  BluetoothAdapter.getDefaultAdapter().disable()
  assertTrue(NearbyRequirements.problem(app)!!.contains("Bluetooth"))
  BluetoothAdapter.getDefaultAdapter().enable()
  app.getSystemService(WifiManager::class.java).isWifiEnabled=false
  assertTrue(NearbyRequirements.problem(app)!!.contains("Wi-Fi"))
  app.getSystemService(WifiManager::class.java).isWifiEnabled=true
  assertNull(NearbyRequirements.problem(app))
  assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in NearbyRequirements.permissions())
 }
}

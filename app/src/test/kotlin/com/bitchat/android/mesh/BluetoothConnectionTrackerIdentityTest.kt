package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class BluetoothConnectionTrackerIdentityTest {
    private val tracker = BluetoothConnectionTracker(
        CoroutineScope(Dispatchers.Unconfined),
        mock()
    )

    @Test
    fun `unauthenticated declaration leaves routing and connection identity intact`() {
        val device = device("AA:BB:CC:DD:EE:FF")
        tracker.addDeviceConnection(
            device.address,
            BluetoothConnectionTracker.DeviceConnection(device = device, peerID = "aaaaaaaaaaaaaaaa")
        )
        tracker.setDevicePeerID(device.address, "aaaaaaaaaaaaaaaa")

        assertTrue(tracker.noteDeclaredIdentity(device.address, "bbbbbbbbbbbbbbbb"))

        assertEquals("aaaaaaaaaaaaaaaa", tracker.addressPeerMap[device.address])
        assertEquals("aaaaaaaaaaaaaaaa", tracker.getDeviceConnection(device.address)?.peerID)
    }

    @Test
    fun `malformed identity is rejected before any pending state`() {
        assertFalse(tracker.noteDeclaredIdentity("AA:BB:CC:DD:EE:FF", "abcd"))
        assertFalse(tracker.noteDeclaredIdentity("AA:BB:CC:DD:EE:FF", "bbbbbbbbbbbbbbbbbb"))
    }

    @Test
    fun `authenticated bind publishes the route and keeps an existing peer link`() {
        val first = device("11:11:11:11:11:11")
        val second = device("22:22:22:22:22:22")
        tracker.addDeviceConnection(first.address, BluetoothConnectionTracker.DeviceConnection(device = first))
        tracker.addDeviceConnection(second.address, BluetoothConnectionTracker.DeviceConnection(device = second))
        tracker.noteDeclaredIdentity(first.address, "cccccccccccccccc")

        assertTrue(tracker.bindAuthenticatedPeer(first.address, "cccccccccccccccc"))
        assertEquals("cccccccccccccccc", tracker.addressPeerMap[first.address])

        tracker.noteDeclaredIdentity(second.address, "cccccccccccccccc")
        assertFalse(tracker.bindAuthenticatedPeer(second.address, "cccccccccccccccc"))
        assertNull(tracker.addressPeerMap[second.address])
        assertEquals(first.address, tracker.addressPeerMap.entries.single().key)
    }

    private fun device(mac: String): BluetoothDevice = mock {
        on { address } doReturn mac
    }
}

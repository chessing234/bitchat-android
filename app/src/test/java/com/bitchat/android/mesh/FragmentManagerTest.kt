package com.bitchat.android.mesh

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.util.AppConstants.Fragmentation
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentManagerTest {

    private lateinit var fragmentManager: FragmentManager
    @Volatile
    private var nowMillis = 1_000L
    private val senderID = "1122334455667788"
    private val recipientID = "8877665544332211"

    @Before
    fun setup() {
        fragmentManager = FragmentManager { nowMillis }
    }

    @After
    fun tearDown() {
        fragmentManager.shutdown()
    }

    @Test
    fun `test fragmentation without route`() {
        // Create a large payload (e.g., 1000 bytes)
        val payload = ByteArray(1000)
        Random().nextBytes(payload)

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)
        
        // Verify each fragment fits in MTU (512)
        for (fragment in fragments) {
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
            
            // Inspect the payload data size
            val fragmentPayload = FragmentPayload.decode(fragment.payload)
            assertNotNull(fragmentPayload)
        }
    }

    @Test
    fun `test fragmentation with route`() {
        // Create a large payload
        val payload = ByteArray(1000)
        Random().nextBytes(payload)
        
        // Create a fake route (3 hops)
        val route = listOf(
            hexStringToByteArray("AABBCCDDEEFF0011"),
            hexStringToByteArray("1100FFEEDDCCBBAA"),
            hexStringToByteArray("1234567890ABCDEF")
        )

        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)

        // Verify fragments retain the route and version 2
        for (fragment in fragments) {
            assertEquals("Fragment version should be 2", 2u.toUByte(), fragment.version)
            assertEquals("Fragment should have the route", route.size, fragment.route?.size)
            
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
        }
    }
    
    @Test
    fun `test fragmentation size difference with and without route`() {
        // This test specifically checks if the dynamic calculation logic works 
        // by observing that fragments with routes carry less data payload per fragment
        
        val payload = ByteArray(2000) // Large enough to ensure full fragments
        Random().nextBytes(payload)
        
        // 1. Without route
        val packetNoRoute = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )
        val fragmentsNoRoute = fragmentManager.createFragments(packetNoRoute)
        val firstFragPayloadNoRoute = FragmentPayload.decode(fragmentsNoRoute[0].payload)
        val dataSizeNoRoute = firstFragPayloadNoRoute?.data?.size ?: 0
        
        // 2. With large route (e.g., 5 hops)
        val route = List(5) { hexStringToByteArray("000000000000000$it") }
        val packetWithRoute = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )
        val fragmentsWithRoute = fragmentManager.createFragments(packetWithRoute)
        val firstFragPayloadWithRoute = FragmentPayload.decode(fragmentsWithRoute[0].payload)
        val dataSizeWithRoute = firstFragPayloadWithRoute?.data?.size ?: 0
        
        println("Data size without route: $dataSizeNoRoute")
        println("Data size with route: $dataSizeWithRoute")
        
        assertTrue("Data payload should be smaller with route", dataSizeWithRoute < dataSizeNoRoute)
        
        // Rough verification of the math:
        // 5 hops * 8 bytes = 40 bytes extra.
        // Plus v2 header overhead differences.
        // The difference should be roughly 40+ bytes.
        assertTrue("Difference should be significant", (dataSizeNoRoute - dataSizeWithRoute) >= 40)
    }

    @Test
    fun `test reassembly`() {
        val originalPayload = ByteArray(1500)
        Random().nextBytes(originalPayload)
        
        val originalPacket = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = originalPayload,
            ttl = 7u
        )
        
        val fragments = fragmentManager.createFragments(originalPacket)
        
        var reassembledPacket: BitchatPacket? = null
        
        // Feed fragments back into FragmentManager
        // Note: FragmentManager stores state in incomingFragments
        
        for (fragment in fragments) {
            val result = fragmentManager.handleFragment(fragment)
            if (result != null) {
                reassembledPacket = result
            }
        }
        
        assertNotNull("Should have reassembled packet", reassembledPacket)
        assertEquals("Type should match", originalPacket.type, reassembledPacket!!.type)
        assertEquals("Payload size should match", originalPacket.payload.size, reassembledPacket.payload.size)
        assertTrue("Payload content should match", originalPacket.payload.contentEquals(reassembledPacket.payload))
    }

    @Test
    fun `inbound fragment set above the receive count limit is rejected`() {
        val payload = FragmentPayload(
            fragmentID = ByteArray(8) { 1 },
            index = 0,
            total = 10_001,
            originalType = MessageType.NOISE_ENCRYPTED.value,
            data = byteArrayOf(1)
        ).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.FRAGMENT.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = 1u,
            payload = payload,
            ttl = 7u
        )

        assertNull(fragmentManager.handleFragment(packet))
        assertBufferedEntries(0)
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
    }

    @Test
    fun `fragment payload refuses UInt16 truncation`() {
        assertThrows(IllegalArgumentException::class.java) {
            FragmentPayload(
                fragmentID = ByteArray(8) { 2 },
                index = 0,
                total = 65_536,
                originalType = MessageType.NOISE_ENCRYPTED.value,
                data = byteArrayOf(1)
            ).encode()
        }
    }

    @Test
    fun `generic public packet retains a 257 fragment outbound plan`() {
        val randomPayload = ByteArray(180 * 1024).also { Random(0xB17C4A7).nextBytes(it) }

        fun plan(contentSize: Int): List<BitchatPacket> = fragmentManager.createFragments(
            BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexStringToByteArray(senderID),
                recipientID = com.bitchat.android.protocol.SpecialRecipients.BROADCAST,
                timestamp = 1u,
                payload = randomPayload.copyOf(contentSize),
                signature = ByteArray(64) { 7 },
                ttl = 7u
            )
        )

        var low = 1
        var high = randomPayload.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (plan(mid).size >= 257) high = mid else low = mid + 1
        }

        assertEquals(257, plan(low).size)
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            val byteStr = hexString.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }

    // Injected-fragment hardening. Fragment packets are unauthenticated and
    // both halves of the reassembly key are attacker-choosable, so a stream in
    // flight has to survive a crafted packet aimed at it. Ports the storage
    // rules from permissionlesstech/bitchat#1515.

    /** A real packet, fragmented the way the sender would fragment it. */
    private fun honestStream(): Pair<BitchatPacket, List<BitchatPacket>> {
        val payload = ByteArray(1200) { (it % 251).toByte() }
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = 1u,
            payload = payload,
            ttl = 7u
        )
        val fragments = fragmentManager.createFragments(packet)
        assertTrue("Need a multi-fragment stream", fragments.size >= 3)
        return packet to fragments
    }

    /** Same stream key as [honest], but attacker-chosen contents. */
    private fun injected(
        honest: BitchatPacket,
        index: Int,
        total: Int,
        data: ByteArray,
        originalType: UByte
    ): BitchatPacket {
        val decoded = FragmentPayload.decode(honest.payload)!!
        return honest.copy(
            payload = FragmentPayload(
                fragmentID = decoded.fragmentID,
                index = index,
                total = total,
                originalType = originalType,
                data = data
            ).encode()
        )
    }

    private fun feedAllButLast(fragments: List<BitchatPacket>) {
        fragments.dropLast(1).forEach {
            assertNull("No fragment before the last should complete", fragmentManager.handleFragment(it))
        }
        assertBufferedEntries(fragments.size - 1)
    }

    private fun assertCompletes(original: BitchatPacket, last: BitchatPacket) {
        val reassembled = fragmentManager.handleFragment(last)
        assertNotNull("The honest stream must still complete", reassembled)
        assertTrue(
            "Reassembled payload must be the honest bytes",
            original.payload.contentEquals(reassembled!!.payload)
        )
        assertBufferedEntries(0)
    }

    @Test
    fun `a conflicting total is rejected without destroying the stream`() {
        val (original, fragments) = honestStream()
        feedAllButLast(fragments)

        val decoded = FragmentPayload.decode(fragments[0].payload)!!
        assertNull(
            fragmentManager.handleFragment(
                injected(fragments[0], index = 0, total = decoded.total - 1,
                         data = ByteArray(4), originalType = decoded.originalType)
            )
        )

        assertCompletes(original, fragments.last())
    }

    @Test
    fun `a conflicting original type is rejected without destroying the stream`() {
        val (original, fragments) = honestStream()
        feedAllButLast(fragments)

        val decoded = FragmentPayload.decode(fragments[0].payload)!!
        assertNull(
            fragmentManager.handleFragment(
                injected(fragments[0], index = 0, total = decoded.total,
                         data = ByteArray(4), originalType = MessageType.ANNOUNCE.value)
            )
        )

        assertCompletes(original, fragments.last())
    }

    @Test
    fun `an index already held keeps its first bytes`() {
        val (original, fragments) = honestStream()
        feedAllButLast(fragments)

        // Same stream, same index, different bytes: last-wins would swap the
        // real sender's bytes out from under the reassembly.
        val decoded = FragmentPayload.decode(fragments[0].payload)!!
        assertNull(
            fragmentManager.handleFragment(
                injected(fragments[0], index = 0, total = decoded.total,
                         data = ByteArray(decoded.data.size) { 0x66 },
                         originalType = decoded.originalType)
            )
        )

        assertCompletes(original, fragments.last())
    }

    @Test
    fun `an identical duplicate is still accepted`() {
        // Duplicate delivery is normal in a mesh; only a *differing* duplicate
        // is an attack, so redelivery must not be turned into a rejection.
        val (original, fragments) = honestStream()
        feedAllButLast(fragments)

        assertNull(fragmentManager.handleFragment(fragments[0]))

        assertCompletes(original, fragments.last())
    }

    @Test
    fun `tiny fragments cannot exceed the shared entry budget`() {
        fillEntryBudget()
        val fullState = fragmentManager.getDebugInfo()

        // Identical and conflicting duplicates neither charge nor replace entries.
        assertNull(fragmentManager.handleFragment(incompleteFragment(1, 0)))
        assertNull(fragmentManager.handleFragment(incompleteFragment(1, 0, byteArrayOf(2))))
        // Reject a new index without destroying the existing set, and remove an
        // empty set created by a rejected first fragment.
        assertNull(fragmentManager.handleFragment(incompleteFragment(2, 6_385)))
        assertNull(fragmentManager.handleFragment(incompleteFragment(3, 0)))

        assertEquals(fullState, fragmentManager.getDebugInfo())
        assertBufferedEntries(16_384)
    }

    @Test
    fun `timeout releases entry credits exactly once`() {
        fillEntryBudget()
        nowMillis += Fragmentation.FRAGMENT_TIMEOUT_MS
        fragmentManager.cleanupOldFragments()
        assertBufferedEntries(16_384)

        nowMillis += 1
        fragmentManager.cleanupOldFragments()
        assertBufferedEntries(0)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
        fragmentManager.cleanupOldFragments()
        assertBufferedEntries(0)
        fillEntryBudget()
    }

    @Test
    fun `reset and shutdown release entry credits`() {
        fillEntryBudget()
        fragmentManager.clearAllFragments()
        assertBufferedEntries(0)
        fillEntryBudget()
        fragmentManager.shutdown()
        assertBufferedEntries(0)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))
    }

    @Test
    fun `active set rejection does not consume entry credits`() {
        repeat(64) { set ->
            assertNull(fragmentManager.handleFragment(incompleteFragment(set + 1, 0)))
        }
        val fullState = fragmentManager.getDebugInfo()
        assertNull(fragmentManager.handleFragment(incompleteFragment(65, 0)))
        assertEquals(fullState, fragmentManager.getDebugInfo())
        assertBufferedEntries(64)
    }

    @Test
    fun `byte admission failures do not consume entry credits`() {
        assertNull(fragmentManager.handleFragment(incompleteFragment(
            1, 0, ByteArray(Fragmentation.MAX_FRAGMENT_TOTAL_BYTES + 1)
        )))
        assertBufferedEntries(0)
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 0"))

        repeat(4) { set ->
            assertNull(fragmentManager.handleFragment(incompleteFragment(
                set + 1, 0, ByteArray(1_048_576)
            )))
        }
        val fullState = fragmentManager.getDebugInfo()
        assertNull(fragmentManager.handleFragment(incompleteFragment(5, 0)))
        assertNull(fragmentManager.handleFragment(incompleteFragment(1, 1)))
        assertEquals(fullState, fragmentManager.getDebugInfo())
        assertBufferedEntries(4)
    }

    @Test
    fun `literal file frame completes across 257 fragments`() {
        val wire = hex(
            "022207010203040506070800000191781011121314151617" +
                "01000161020004000191640300016d0400019164"
        ) + ByteArray(102_756) { 0x5a }

        assertGoldenFileCompletes(wire, 257, 102_756)
    }

    @Test
    fun `literal file frame at the byte ceiling completes across 10000 fragments`() {
        // 1,179,760 uncompressed frame bytes, above the former 1 MiB bound.
        val wire = hex(
            "022207010203040506070800001200581011121314151617" +
                "01000161020004001200440300016d0400120044"
        ) + ByteArray(1_179_716) { 0x5a }

        assertEquals(1_179_760, wire.size)
        assertGoldenFileCompletes(wire, 10_000, 1_179_716)
    }

    @Test
    fun `literal file frame one byte above the ceiling is not reassembled`() {
        val wire = hex(
            "022207010203040506070800001200591011121314151617" +
                "01000161020004001200450300016d0400120045"
        ) + ByteArray(1_179_717) { 0x5a }

        assertNotNull("The original frame itself is decodable", BinaryProtocol.decode(wire))
        assertNull(reassembleGoldenFrame(wire, 10_000))
        assertBufferedEntries(9_999)
    }

    private fun incompleteFragment(
        set: Int,
        index: Int,
        data: ByteArray = byteArrayOf(1)
    ): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.FRAGMENT.value,
        senderID = hexStringToByteArray(senderID),
        timestamp = 1u,
        payload = FragmentPayload(
            fragmentID = ByteArray(8).also { it[7] = set.toByte() },
            index = index,
            total = 10_000,
            originalType = MessageType.FILE_TRANSFER.value,
            data = data
        ).encode(),
        ttl = 7u
    )

    private fun fillEntryBudget() {
        // Two incomplete sets, only 16 KiB of data, not a 640k-entry allocation.
        repeat(9_999) { assertNull(fragmentManager.handleFragment(incompleteFragment(1, it))) }
        repeat(6_385) { assertNull(fragmentManager.handleFragment(incompleteFragment(2, it))) }
        assertBufferedEntries(16_384)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 16384"))
        assertTrue(fragmentManager.getDebugInfo().contains("Active Fragment Sets: 2"))
    }

    private fun assertBufferedEntries(expected: Int) {
        val line = fragmentManager.getDebugInfo().lineSequence()
            .first { it.startsWith("Global Buffered Entries:") }
        assertEquals("Global Buffered Entries: $expected", line)
    }

    private fun assertGoldenFileCompletes(wire: ByteArray, total: Int, contentSize: Int) {
        val packet = reassembleGoldenFrame(wire, total)
        assertNotNull("All fragments must produce the original file", packet)
        assertEquals(0u.toUByte(), packet!!.ttl)
        assertArrayEquals(wire, BinaryProtocol.encode(packet.copy(ttl = 7u), padding = false))
        val file = BitchatFilePacket.decode(packet.payload)
        assertNotNull(file)
        assertEquals("a", file!!.fileName)
        assertEquals("m", file.mimeType)
        assertEquals(contentSize.toLong(), file.fileSize)
        assertArrayEquals(ByteArray(contentSize) { 0x5a }, file.content)
        assertBufferedEntries(0)
        assertTrue(fragmentManager.getDebugInfo().contains("Global Buffered Bytes: 0"))
    }

    private fun reassembleGoldenFrame(wire: ByteArray, total: Int): BitchatPacket? {
        var completed: BitchatPacket? = null
        repeat(total) { index ->
            // Literal, uncompressed v1 fragment envelopes and v2 file bytes:
            // neither the fragment planner nor file encoder supplies the oracle.
            val start = (wire.size.toLong() * index / total).toInt()
            val end = (wire.size.toLong() * (index + 1) / total).toInt()
            val payload = hex("0001020304050607") + u16(index) + u16(total) +
                byteArrayOf(0x22) + wire.copyOfRange(start, end)
            val fragmentWire = hex("012007010203040506070800") + u16(payload.size) +
                hex("1011121314151617") + payload
            val fragment = BinaryProtocol.decode(fragmentWire)
            assertNotNull(fragment)
            val result = fragmentManager.handleFragment(fragment!!)
            if (index < total - 1) assertNull(result) else completed = result
        }
        return completed
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    @Test
    fun `an oversized fragment cannot wipe an assembly it did not start`() {
        val (original, fragments) = honestStream()
        feedAllButLast(fragments)

        val decoded = FragmentPayload.decode(fragments[0].payload)!!
        val oversized = ByteArray(
            com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES + 1
        )
        assertNull(
            fragmentManager.handleFragment(
                injected(fragments[0], index = decoded.total - 1, total = decoded.total,
                         data = oversized, originalType = decoded.originalType)
            )
        )

        assertCompletes(original, fragments.last())
    }
}

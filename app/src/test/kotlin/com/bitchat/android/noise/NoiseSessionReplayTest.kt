package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoiseSessionReplayTest {
    @Test
    fun `every ciphertext in an in-order run is rejected on replay`() = withSessions { sender, receiver ->
        val packets = packets(sender, 101)
        packets.forEachIndexed { nonce, packet ->
            assertArrayEquals(payload(nonce), receiver.decrypt(packet))
        }
        packets.forEach { assertRejected(receiver, it) }
    }

    @Test
    fun `shifts preserve seen nonces and unseen holes across byte boundaries`() {
        for (shift in listOf(1, 7, 8, 9, 15, 63, 127, 255, 511, 1023, 1024, 1025)) {
            withSessions { sender, receiver ->
                val newest = 7 + shift
                val packets = packets(sender, newest + 1)
                assertArrayEquals(payload(0), receiver.decrypt(packets[0]))
                assertArrayEquals(payload(7), receiver.decrypt(packets[7]))
                assertArrayEquals(payload(newest), receiver.decrypt(packets[newest]))

                assertRejected(receiver, packets[0])
                assertRejected(receiver, packets[7])
                assertRejected(receiver, packets[newest])
                for (nonce in 1..6) {
                    if (newest - nonce < 1024) {
                        assertArrayEquals(payload(nonce), receiver.decrypt(packets[nonce]))
                    }
                    assertRejected(receiver, packets[nonce])
                }
            }
        }
    }

    @Test
    fun `unseen nonce at offset 1023 is accepted but offset 1024 is rejected`() = withSessions { sender, receiver ->
        val packets = packets(sender, 1025)
        assertArrayEquals(payload(1024), receiver.decrypt(packets[1024]))
        assertArrayEquals(payload(1), receiver.decrypt(packets[1]))
        assertRejected(receiver, packets[0])
        assertRejected(receiver, packets[1])
        assertRejected(receiver, packets[1024])
    }

    @Test
    fun `unsigned wire nonce jumps clear the window before integer narrowing`() = withSessions { sender, receiver ->
        val first = sender.encrypt(payload(0))
        assertArrayEquals(payload(0), receiver.decrypt(first))
        // Skip directly to large valid send nonces instead of encrypting billions of packets.
        val sendNonce = NoiseSession::class.java.getDeclaredField("messagesSent").apply {
            isAccessible = true
        }
        for (nonce in listOf(0x80000000L, 0xFFFFFFFEL)) {
            sendNonce.setLong(sender, nonce)
            val plaintext = "large-nonce-$nonce".toByteArray(Charsets.UTF_8)
            val packet = sender.encrypt(plaintext)
            assertArrayEquals(plaintext, receiver.decrypt(packet))
            assertRejected(receiver, packet)
            assertRejected(receiver, first)
        }
    }

    @Test
    fun `receive order golden vector keeps holes distinct from duplicates`() = withSessions { sender, receiver ->
        val packets = packets(sender, 9)
        for (nonce in listOf(0, 7, 8, 6)) {
            assertArrayEquals(payload(nonce), receiver.decrypt(packets[nonce]))
        }
        for (nonce in listOf(0, 6, 7, 8)) {
            assertRejected(receiver, packets[nonce])
        }
        for (nonce in 1..5) {
            assertArrayEquals(payload(nonce), receiver.decrypt(packets[nonce]))
            assertRejected(receiver, packets[nonce])
        }
    }

    @Test
    fun `shuffled delivery accepts each ciphertext once over multiple windows`() = withSessions { sender, receiver ->
        val packets = packets(sender, 3072)
        val random = Random(1055)
        for (block in packets.indices.chunked(512)) {
            for (nonce in block.shuffled(random)) {
                assertArrayEquals(payload(nonce), receiver.decrypt(packets[nonce]))
            }
            for (nonce in block) {
                assertRejected(receiver, packets[nonce])
            }
        }
    }

    @Test
    fun `unauthenticated packets neither advance the window nor consume a nonce`() = withSessions { sender, receiver ->
        val packets = packets(sender, 2)
        val forgedNonce = packets[0].copyOf().apply {
            this[0] = 0x7F
            this[1] = 0xFF.toByte()
            this[2] = 0xFF.toByte()
            this[3] = 0xFF.toByte()
        }
        assertRejected(receiver, forgedNonce)
        assertArrayEquals(payload(0), receiver.decrypt(packets[0]))

        val tamperedTag = packets[1].copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        assertRejected(receiver, tamperedTag)
        assertArrayEquals(payload(1), receiver.decrypt(packets[1]))
        packets.forEach { assertRejected(receiver, it) }
    }

    @Test
    fun `short packets do not consume the first empty plaintext packet`() = withSessions { sender, receiver ->
        for (length in 0 until 20) {
            assertRejected(receiver, ByteArray(length))
        }
        val packet = sender.encrypt(ByteArray(0))
        assertArrayEquals(ByteArray(0), receiver.decrypt(packet))
        assertRejected(receiver, packet)
    }

    private fun packets(sender: NoiseSession, count: Int): List<ByteArray> =
        List(count) { sender.encrypt(payload(it)) }

    private fun payload(nonce: Int): ByteArray = "replay-test-$nonce".toByteArray(Charsets.UTF_8)

    private fun assertRejected(receiver: NoiseSession, packet: ByteArray) {
        assertThrows(SessionError.DecryptionFailed::class.java) { receiver.decrypt(packet) }
    }

    private fun withSessions(test: (NoiseSession, NoiseSession) -> Unit) {
        val initiatorIdentity = identity(
            "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1"
        )
        val responderIdentity = identity(
            "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893"
        )
        val initiator = NoiseSession(
            NoisePeerIdentity.derivePeerID(responderIdentity.second)!!,
            true,
            initiatorIdentity.first,
            initiatorIdentity.second
        )
        val responder = NoiseSession(
            NoisePeerIdentity.derivePeerID(initiatorIdentity.second)!!,
            false,
            responderIdentity.first,
            responderIdentity.second
        )
        try {
            val message1 = initiator.startHandshake()
            val message2 = responder.processHandshakeMessage(message1)!!
            val message3 = initiator.processHandshakeMessage(message2)!!
            assertNull(responder.processHandshakeMessage(message3))
            assertTrue(initiator.isEstablished())
            assertTrue(responder.isEstablished())
            test(initiator, responder)
        } finally {
            initiator.destroy()
            responder.destroy()
        }
    }

    private fun identity(privateKeyHex: String): Pair<ByteArray, ByteArray> {
        val privateKey = privateKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val dh = Noise.createDH("25519")
        return try {
            dh.setPrivateKey(privateKey, 0)
            val publicKey = ByteArray(32)
            dh.getPublicKey(publicKey, 0)
            privateKey to publicKey
        } finally {
            dh.destroy()
        }
    }
}

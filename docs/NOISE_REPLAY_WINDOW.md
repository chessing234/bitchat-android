# Noise transport replay window

The transport payload remains a four-byte unsigned big-endian nonce followed by
Noise ciphertext and its authentication tag. This is a receive-side correction;
no wire-format or peer negotiation changes are required.

Each receive cipher tracks the highest authenticated nonce and a 1024-bit bitmap.
Bit `offset` records nonce `highest - offset`, with the least significant bit
first in each byte. Advancing the highest nonce increases existing offsets:
shift bits left within each byte and carry high bits into the next byte. A jump
of at least 1024 clears the previous bitmap; compare the full unsigned-wire nonce
difference before narrowing it to an integer bitmap offset. Offsets 0 through 1023 are eligible;
older nonces and already-set bits are rejected. Do not update the bitmap until
authentication succeeds.

## Receive-order golden vector

For ciphertexts produced by one established Noise session:

| Delivery | Expected result | Highest | First two bitmap bytes (hex) |
| --- | --- | --- | --- |
| nonce 0 | accept | 0 | `01 00` |
| nonce 7 | accept | 7 | `81 00` |
| nonce 8 | accept | 8 | `03 01` |
| nonce 6 | accept | 8 | `07 01` |
| nonce 0, 6, 7, or 8 again | reject | 8 | unchanged |
| nonce 1 through 5, once each | accept | 8 | `ff 01` after all five |

`NoiseSessionReplayTest` exercises this delivery vector through the real Noise
handshake and transport cipher, as well as sequential replays, byte-boundary
shifts, the 1023/1024 cutoff, deterministic shuffled delivery, and failed
authentication that must not consume receive state. Existing Noise-C vectors in
`NoiseExternalVectorTest` continue to cover the unchanged handshake and cipher
bytes.

Physical phone-to-phone and phone-to-watch Mesh Lab validation remains required
before release; the JVM regressions do not replace it.

package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class AnubisLocalAdbTest {
    @Test
    fun onlyTheWirelessTlsUpgradeCanPrecedeTheHandshake() {
        AnubisLocalAdb.requireTlsUpgrade(frame(0x534c5453, 0x01000000))

        for (legacy in listOf(0x4e584e43, 0x48545541)) {
            assertThrows(IOException::class.java) {
                AnubisLocalAdb.requireTlsUpgrade(frame(legacy, 0x01000000))
            }
        }
    }

    @Test
    fun malformedUpgradeFramesAreRejected() {
        for (invalid in listOf(
            frame(0x534c5453, 0),
            frame(0x534c5453, 0x01000000, size = Int.MAX_VALUE),
            frame(0x534c5453, 0x01000000, validMagic = false),
        )) {
            assertThrows(IOException::class.java) { AnubisLocalAdb.requireTlsUpgrade(invalid) }
        }
    }

    private fun frame(command: Int, version: Int, size: Int = 0, validMagic: Boolean = true): DataInputStream {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(version).putInt(0).putInt(size).putInt(0)
        header.putInt(if (validMagic) command.inv() else 0)
        return DataInputStream(ByteArrayInputStream(header.array()))
    }
}

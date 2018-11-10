package cz.muni.fi.sybila.deadlock

import org.junit.Test
import kotlin.math.max
import kotlin.test.assertEquals

class TCPSystemTest {

    @Test
    fun processAck() {
        TCPTransitionSystem().run {
            assertEquals(null, TCPState(1234, emptyList(), 1234, listOf()).processAck())
            assertEquals(
                    TCPState(1234, emptyList(), 1234, listOf(456)) to fullRect.asParams(),
                    TCPState(1234, emptyList(), 1234, listOf(123, 456)).processAck()
            )
        }
    }

    @Test
    fun receiveNoAck() {
        TCPTransitionSystem().run {
            // nothing to receive
            assertEquals(null, TCPState(1234, emptyList(), 1234, listOf(123)).receiveNoAck())
            // acknowledgement sent if windowSlide >= 2*MSS
            assertEquals(null, TCPState(1234, listOf(MSS), MSS, emptyList()).receiveNoAck())
            assertEquals(
                    TCPState(1234, emptyList(), 30, emptyList()) to fullRect.asParams(),
                    TCPState(1234, listOf(10), 20, emptyList()).receiveNoAck()
            )
            // acknowledgement sent if windowSlide >= 0.35 * BLOCK * r
            assertEquals(
                    // (4096 + 9204) = 13300 < 13619 = (0.35 * BLOCK * 38)
                    TCPState(1234, listOf(123), 4*BLOCK + MSS, emptyList()) to iRectOf(s.first, s.second, 38, r.second).asParams(),
                    TCPState(1234, listOf(MSS, 123), 4*BLOCK, emptyList()).receiveNoAck()
            )
        }
    }

    @Test
    fun receiveWithAck() {
        TCPTransitionSystem().run {
            // nothing to receive
            assertEquals(null, TCPState(1234, emptyList(), 1234, listOf(123)).receiveWithAck())
            // acknowledgement sent if windowSlide >= 2*MSS
            assertEquals(
                    TCPState(1234, listOf(), 0, listOf(123, MSS + MSS)) to fullRect.asParams(),
                    TCPState(1234, listOf(MSS), MSS, listOf(123)).receiveWithAck()
            )
            // acknowledgement sent if windowSlide >= 0.35 * BLOCK * r
            assertEquals(
                    // (4096 + 9204) = 13300 > 13260 = (0.35 * BLOCK * 37)
                    TCPState(1234, listOf(123), 0, listOf(4*BLOCK + MSS)) to iRectOf(s.first, s.second, r.first, 37).asParams(),
                    TCPState(1234, listOf(MSS, 123), 4*BLOCK, emptyList()).receiveWithAck()
            )
        }
    }

    @Test
    fun sendFullPacket() {
        TCPTransitionSystem().run {
            // cannot send full packet with less than MSS to send
            assertEquals(null, TCPState(1234, emptyList(), 1234, emptyList()).sendFullPacket())
            // cannot send full packet if window is too small
            assertEquals(null, TCPState(2*MSS, emptyList(), (max(s.second, r.second) + 1) * BLOCK, emptyList()).sendFullPacket())
            // with no other restrictions, send and receive has to be at least MSS
            assertEquals(
                    TCPState(MSS, listOf(MSS), 0, emptyList()) to iRectOf(9, s.second, 9, r.second).asParams(),
                    TCPState(2*MSS, emptyList(), 0, emptyList()).sendFullPacket()
            )
            // with some restrictions, the parameters can have a much more complex role
            assertEquals(
                    // send and receive buffer both need to be at least 3*MSS to allow this packet
                    // (Actually, send buffer needs to be even bigger since there is an extra MSS of unsent data,
                    // but that is not the point of this test)
                    TCPState(MSS, listOf(MSS), 2*MSS, emptyList()) to iRectOf(27, 64, 27, 64).asParams(),
                    TCPState(2*MSS, emptyList(), 2*MSS, emptyList()).sendFullPacket()
            )
        }
    }

    @Test
    fun sendPartialPacket() {
        TCPTransitionSystem().run {
            // nothing to send
            assertEquals(emptyList(), TCPState(0, emptyList(),  0, emptyList()).sendPartialPacket())
            // outstanding bytes
            assertEquals(emptyList(), TCPState(1234, emptyList(), 4000, emptyList()).sendPartialPacket())
            assertEquals(emptyList(), TCPState(1234, listOf(4000), 0, emptyList()).sendPartialPacket())
            assertEquals(emptyList(), TCPState(1234, emptyList(), 0, listOf(4000)).sendPartialPacket())
            // very small packet
            assertEquals(listOf(
                    TCPState(0, listOf(100), 0, emptyList()) to tt
            ), TCPState(100, emptyList(), 0, emptyList()).sendPartialPacket())
            // limited only by window size - cannot send full packet, because MSS would be exceeded
            val packets = MSS/BLOCK
            assertEquals(
                    (1..packets).map { n ->
                        TCPState(2*MSS - n*BLOCK, listOf(n*BLOCK), 0, emptyList()) to
                                mutableSetOf(iRectOf(n, n, n, r.second), iRectOf(n, s.second, n, n))
                    },
                    TCPState(2*MSS, emptyList(), 0, emptyList()).sendPartialPacket()
            )
            // less than MSS but also limited by window
            val packets2 = 4567/BLOCK
            assertEquals(
                    listOf(
                            TCPState(0, listOf(4567), 0, emptyList()) to
                            iRectOf(5, s.second, 5, r.second).asParams()
                    ) +
                    (1..packets2).map { n ->
                        TCPState(4567 - n*BLOCK, listOf(n*BLOCK), 0, emptyList()) to
                                mutableSetOf(iRectOf(n, n, n, r.second), iRectOf(n, s.second, n, n))
                    },
                    TCPState(4567, emptyList(), 0, emptyList()).sendPartialPacket()
            )
        }
    }

    @Test
    fun copyData() {
        TCPTransitionSystem().run {
            // nothing to copy - buffers are full full full
            assertEquals(emptyList(), TCPState(s.second * BLOCK - 1, emptyList(), 0, emptyList()).copyData(ff))
            // copy one or two blocks
            assertEquals(listOf(
                TCPState(s.second * BLOCK - 5, emptyList(), 0, emptyList()) to iRectOf(s.second, s.second, r.first, r.second).asParams(),
                TCPState((s.second - 1) * BLOCK - 5, emptyList(), 0, emptyList()) to iRectOf(s.second - 1, s.second - 1, r.first, r.second).asParams()
            ), TCPState((s.second - 2) * BLOCK - 5, emptyList(), 0, emptyList()).copyData(ff))
        }
    }


}
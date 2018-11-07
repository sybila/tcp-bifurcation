package cz.muni.fi.sybila.deadlock

import com.github.sybila.checker.Solver
import kotlin.math.max
import kotlin.math.min

/*
    Here, we store the general description of the transition system. Each specific model can then use this
    representation to create a transition system
 */

data class TCPState(
        val toSend: Int,    // bytes which have been copied from user, but haven't been sent yet
        val sent: List<Int>,// bytes which have been sent and are in transit
        val toAck: Int,     // bytes which have been received, but acknowledgement haven't been sent yet
        val acked: List<Int>// bytes which have been acknowledged by the receiver, but acknowledgement haven't been received yet
) {

    val sentData: Int
        get() = sent.sum()

    val ackedData: Int
        get() = acked.sum()

}

class TCPTransitionSystem(
        /*
        s and r are parameters which give the size of the send/receive buffer in BLOCK multiples

        We use R and S to refer to actual buffer size (i.e. R = r * BLOCK)
     */
        val s: Pair<Int, Int> = 1 to 64,
        val r: Pair<Int, Int> = 1 to 64,
        solver: Solver<IParams> = IntRectSolver(iRectOf(s.first, s.second, r.first, r.second))
) : Solver<IParams> by solver {

    // Size of one application data block
    val BLOCK = 1024
    // Size of one packet of data (minus header)
    // ATM network: 9204, Ethernet network: 1460
    val MSS = 9204

    val fullRect = iRectOf(s.first, s.second, r.first, r.second)

    fun successors(source: TCPState): List<Pair<TCPState, IParams>> {
        val receiveAck = source.processAck()
        return emptyList()
    }

    fun TCPState.processAck(): Pair<TCPState, IParams>? {
        return if (acked.isNotEmpty()) copy(acked = acked.drop(1)) to fullRect.asParams() else null
    }

    fun TCPState.receiveNoAck(): Pair<TCPState, IParams>? {
        if (sent.isEmpty()) return null // nothing to receive
        val packet = sent.first()
        val windowSlide = toAck + packet
        // no acknowledgement is sent if windowSlide < 0.35 * R AND windowSlide < 2 * MSS
        if (windowSlide >= 2*MSS) return null   // ack is always sent
        // windowSlide < 0.35 * R
        // windowSlide / 0.35 < R
        val lowerBound = Math.ceil(windowSlide / 0.35 / BLOCK).toInt()  // ceil is important, floor might be off by one
        val newState = copy(sent = sent.drop(1), toAck = toAck + packet)
        return if (lowerBound > r.second) null else {
            newState to iRectOf(s.first, s.second, max(r.first, lowerBound), r.second).asParams()
        }
    }

    fun TCPState.receiveWithAck(): Pair<TCPState, IParams>? {
        if (sent.isEmpty()) return null // nothing to receive
        val packet = sent.first()
        val windowSlide = toAck + packet
        val newState = copy(sent = sent.drop(1), toAck = 0, acked = acked + (windowSlide))
        // acknowledgement is sent if windowSlide >= 0.35 * R OR windowSlide >= 2 * MSS
        if (windowSlide >= 2*MSS) return (newState to fullRect.asParams())  // ack is always sent, regardless of buffer size
        // windowSlide >= 0.35 * R
        // windowSlide / 0.35 >= R
        val upperBound = Math.floor(windowSlide / 0.35 / BLOCK).toInt() // floor for conceptual symmetry with ceil up
        return if (upperBound < r.first) null else {
            newState to iRectOf(s.first, s.second, r.first, min(r.second, upperBound)).asParams()
        }
    }

    fun TCPState.sendFullPacket(): Pair<TCPState, IParams>? {
        if (toSend < MSS) return null // not enough data to send
        // Now, we have to ensure that send window is big enough to contain the packet
        // window = min(R,S) - sentData - toAck - ackedData >= MSS
        // min(R,S) >= MSS + sentData + toAck + ackedData
        val threshold = Math.ceil((MSS + sentData + toAck + ackedData) / BLOCK.toDouble()).toInt()
        // minimum is bigger only if BOTH parameters are bigger, so we restrict both parameters
        if (threshold > s.second || threshold > r.second) return null   // buffers are too small to send a full packet
        val params = iRectOf(max(s.first, threshold), s.second, max(r.first, threshold), r.second).asParams()
        return copy(toSend = toSend - MSS, sent = sent + MSS) to params
    }

    fun TCPState.sendPartialPacket(): Pair<TCPState, IParams>? {
        if (toSend == 0 || sentData + toAck + ackedData > 0) return null    // no data or outstanding data present
        
    }



}
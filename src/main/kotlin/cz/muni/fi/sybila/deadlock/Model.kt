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
        val acked: List<Int>,// bytes which have been acknowledged by the receiver, but acknowledgement haven't been received yet
        val canRandomAck: Boolean = true
) {

    val sentData: Int
        get() = sent.sum()

    val ackedData: Int
        get() = acked.sum()

}

class TCPTransitionSystem(
        // A scale factor is used to skip some parameter values
        private val SCALE: Int = 1,
        // Maximal parameter bound
        private val MAX: Int = 64,
        // true if one random acknowledgement can be sent
        private val randomAck: Boolean = true,
        /*
            s and r are parameters which give the size of the send/receive buffer in BLOCK multiples

            We use R and S to refer to actual buffer size (i.e. R = r * SCALE * BLOCK)
        */
        val s: Pair<Int, Int> = 1 to MAX,
        val r: Pair<Int, Int> = 1 to MAX,
        // Size of one packet of data (minus header)
        // ATM network: 9204, Ethernet network: 1460
        val MSS: Int = 9204,
        // Size of one application data block
        val BLOCK: Int = 1024,
        solver: Solver<IParams> = IntRectSolver(iRectOf(s.first, s.second, r.first, r.second))
) : Solver<IParams> by solver {

    val fullRect = iRectOf(s.first, s.second, r.first, r.second)

    fun successors(source: TCPState): List<Pair<TCPState, IParams>> {
        //if (source in cache) return cache[source]!!
        val receiveNoAck = source.receiveNoAck()
        val receiveWithAck = source.receiveWithAck()
        val processAck = source.processAck()
        val sendFullPacket = source.sendFullPacket()
        val sendPartialPacket = source.sendPartialPacket()
        val sentParams = (sendFullPacket?.second ?: ff) or (sendPartialPacket.fold(ff) { a, b -> a or b.second })
        val copyData = source.copyData(sentParams)
        val result = ArrayList<Pair<TCPState, IParams>>()
        if (this.randomAck) {
            val randomAck = source.sendRandomAck()
            randomAck?.let { result.add(randomAck) }
        }
        receiveNoAck?.let { result.add(it) }
        receiveWithAck?.let { result.add(it) }
        processAck?.let { result.add(it) }
        sendFullPacket?.let { result.add(it) }
        result.addAll(sendPartialPacket)
        result.addAll(copyData)
        return result
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
        val lowerBound = Math.ceil(windowSlide / 0.35 / BLOCK / SCALE).toInt()  // ceil is important, floor might be off by one
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
        val upperBound = Math.floor(windowSlide / 0.35 / BLOCK / SCALE).toInt() // floor for conceptual symmetry with ceil up
        return if (upperBound < r.first) null else {
            newState to iRectOf(s.first, s.second, r.first, min(r.second, upperBound)).asParams()
        }
    }

    fun TCPState.sendPacket(): List<Pair<TCPState, IParams>> {
        if (toSend == 0 || sentData > 0) return emptyList()
        /*
            Try to send as much available data as possible, without considering Nagle's algorithm.
         */
        val results = ArrayList<Pair<TCPState, IParams>>()

        val outstanding = sentData + toAck + ackedData
        // First, consider we can send all available data - packet = min(MSS, toSend) and min(R,S) >= packet.
        val maxPacket = min(MSS, toSend)
        val outstandingAfterFullSend = outstanding + maxPacket
        val windowSendAll = if (outstandingAfterFullSend % (BLOCK * SCALE) == 0) {
            outstandingAfterFullSend / (BLOCK * SCALE)
        } else {
            outstandingAfterFullSend / (BLOCK * SCALE) + 1
        }
        val paramsSendAll = if (windowSendAll <= s.second && windowSendAll <= r.second) {
            iRectOf(max(s.first, windowSendAll), s.second, max(r.first, windowSendAll), r.second).asParams()
        } else ff
        if (paramsSendAll.isSat()) {
            results.add(copy(toSend = toSend - maxPacket, sent = sent + maxPacket) to paramsSendAll)
        }

        // Second we consider other situations where we can't send a full packet because we are limited by window size.
        for (window in 1 until windowSendAll) {
            if (window < s.first || window < r.first) continue  // window is not allowed
            if (outstanding >= window * BLOCK * SCALE) continue // window is too small to send anything
            val params = mutableSetOf(
                    // S = window, R >= window                 S >= window, R = window
                    iRectOf(window, window, window, r.second), iRectOf(window, s.second, window, window)
            )
            val packet = window * BLOCK * SCALE
            results.add(copy(toSend = toSend - packet, sent = sent + packet) to params)
        }

        return results
    }

    fun TCPState.sendFullPacket(): Pair<TCPState, IParams>? {
        if (toSend < MSS) return null // not enough data to send
        // Now, we have to ensure that send window is big enough to contain the packet
        // window = min(R,S) - sentData - toAck - ackedData >= MSS
        // min(R,S) >= MSS + sentData + toAck + ackedData
        val threshold = Math.ceil((MSS + sentData + toAck + ackedData) / BLOCK.toDouble() / SCALE).toInt()
        // minimum is bigger only if BOTH parameters are bigger, so we restrict both parameters
        if (threshold > s.second || threshold > r.second) return null   // buffers are too small to send a full packet
        val params = iRectOf(max(s.first, threshold), s.second, max(r.first, threshold), r.second).asParams()
        return copy(toSend = toSend - MSS, sent = sent + MSS) to params
    }

    fun TCPState.sendPartialPacket(): List<Pair<TCPState, IParams>> {
        if (toSend == 0 || sentData + toAck + ackedData > 0) return emptyList()    // no data or outstanding data present
        // We can't send a partial packet if full packet is available, even if there are no outstanding data.
        // Furthermore, the size of the packet is limited by window size which depends on parameters.
        // Thankfully, we know that there are no outstanding data, hence window = min(R,S).
        val results = ArrayList<Pair<TCPState, IParams>>()

        // First, we consider when we can send all data - i.e. when window = min(R,S) >= toSend.
        val maxPacket = min(MSS, toSend)
        val windowSendAll = if (maxPacket % (BLOCK * SCALE) == 0) maxPacket / (BLOCK * SCALE) else (maxPacket / (BLOCK * SCALE) + 1)
        if (toSend < MSS) { // However, only if toSend < MSS, since otherwise we can send a full MSS packet.
            val params = iRectOf(max(s.first, windowSendAll), s.second, max(r.first, windowSendAll), r.second).asParams()
            results.add(copy(toSend = 0, sent = sent + toSend) to params)
        }

        // Second, we have to consider situations when we are limited by the window size -> min(R,S) < toSend
        for (window in 1 until windowSendAll) { // consider window sizes where we can't send all data
            // We have window * BLOCK < toSend because (windowSendAll-1) * BLOCK < toSend.
            // Furthermore, for each window size, we have that packetSize = window*BLOCK and it can be sent
            // only if min(R,S) = window * BLOCK
            if (window < s.first || window < r.first) continue  // such small window is not allowed
            val params = mutableSetOf(
                    // S = window, R >= window                 S >= window, R = window
                    iRectOf(window, window, window, r.second), iRectOf(window, s.second, window, window)
            )
            val packet = window * BLOCK * SCALE
            results.add(copy(toSend = toSend - packet, sent = sent + packet) to params)
        }

        return results
    }

    fun TCPState.copyData(sentParams: IParams): List<Pair<TCPState, IParams>> {
        val results = ArrayList<Pair<TCPState, IParams>>()
        var remainingParams = sentParams.not()
        for (toCopy in 4 downTo 1) {  // number of blocks which can be copied
            // BLOCK * toCopy + toSend + sentData + toAck + ackedData < S
            val bufferAfterCopy = toCopy * BLOCK + toSend + sentData + toAck + ackedData
            val threshold = if (bufferAfterCopy % (BLOCK * SCALE) == 0) bufferAfterCopy / (BLOCK * SCALE) else (bufferAfterCopy / (BLOCK * SCALE) + 1)
            if (threshold <= s.second) {
                val params = remainingParams and iRectOf(threshold, s.second, r.first, r.second).asParams()
                remainingParams = remainingParams and params.not()
                if (params.isSat()) {
                    results.add(copy(toSend = toSend + toCopy * BLOCK) to params)
                }
                if (remainingParams.isNotSat()) break
            }
        }
        return results
    }

    fun TCPState.sendRandomAck(): Pair<TCPState, IParams>? {
        if (toAck == 0 || !canRandomAck || sentData > 0 || ackedData > 0) return null
        return copy(toAck = 0, acked = acked + toAck, canRandomAck = false) to tt
    }


}
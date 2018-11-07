package cz.muni.fi.sybila.deadlock

import kotlin.math.min

private val S = 1024 * 16
private val R = 1024 * 24
private val BLOCK = 1024
private val MSS = 9204

private data class State(
        val toSend: Int,
        val dataChannel: List<Int>,
        val toAck: Int,
        val ackChannel: List<Int>,
        val randomAck: Boolean = false
) {

    val dataSize: Int = dataChannel.sum()
    val ackSize: Int = ackChannel.sum()

    val freeSend: Int = S - toSend - dataSize - toAck - ackSize

    val sendWindow: Int = min(R, S) - dataSize - toAck - ackSize

    val outstanding: Int = dataSize + ackSize + toAck

    init {
        if (toSend + dataSize + toAck + ackSize > S) error("Send buffer overflow: $this")
        if (toAck > R) error("Receive buffer overflow: $this")
    }
}

private fun State.next(): Set<State> {

    val result = HashSet<State>()

    if (sendWindow >= MSS && toSend >= MSS) {
        // Send full packet if window is available
        result.add(copy(toSend = toSend - MSS, dataChannel = dataChannel + MSS))
    } else if (sendWindow > 0 && toSend > 0 && outstanding == 0) {
        // Send partial packet if window is available and everything is acknowledged
        val packet = min(sendWindow, toSend)
        result.add(copy(toSend = toSend - packet, dataChannel = dataChannel + packet))
    } else if (freeSend >= BLOCK) {
        // Copy if free space in send buffer available
        val toCopy = min(4, freeSend / BLOCK)
        result.add(copy(toSend = toSend + toCopy * BLOCK))
    }

    if (dataSize > 0) {
        // Receive packet if available (due to window restrictions, toAck should never overflow)
        val packet = dataChannel[0]
        if (toAck + packet >= 0.35 * R || toAck + packet >= 2*MSS) {
            // Receive and acknowledge
            result.add(copy(
                    dataChannel = dataChannel.drop(1), toAck = 0, ackChannel = ackChannel + (toAck + packet)
            ))
        } else {
            // Just receive
            result.add(copy(dataChannel = dataChannel.drop(1), toAck = toAck + packet))
        }
    }

    if (ackSize > 0) {
        // Receive ack packet if available
        result.add(copy(ackChannel = ackChannel.drop(1)))
    }

    if (!randomAck && toAck > 0) {
        result.add(copy(toAck = 0, ackChannel = ackChannel + toAck, randomAck = true))
    }

    return result
}

fun main(args: Array<String>) {
    var states = setOf(State(0, emptyList(), 0, emptyList()))
    repeat(1000) {
        if (states.isNotEmpty()) println("$it: $states")
        states = states.flatMap { s -> s.next().also {
            if (it.isEmpty()) error("Deadlock $s")
        } }.toSet()
    }
}

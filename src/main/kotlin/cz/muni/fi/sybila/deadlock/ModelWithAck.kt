package cz.muni.fi.sybila.deadlock

import java.io.File

fun main(args: Array<String>) {
    runExperiment(1, 64, true, File("TCP_with_ack.json"))
}
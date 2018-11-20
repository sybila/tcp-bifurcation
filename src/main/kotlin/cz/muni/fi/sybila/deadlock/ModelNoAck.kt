package cz.muni.fi.sybila.deadlock

import java.io.File

fun main(args: Array<String>) {
    runExperiment(1, 64, false, File("TCP_no_ack.json"))
}
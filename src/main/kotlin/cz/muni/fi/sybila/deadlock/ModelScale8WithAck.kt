package cz.muni.fi.sybila.deadlock

import java.io.File

fun main(args: Array<String>) {
    runExperiment(8, 8, true, File("TCP_scale_8_with_ack.json"))
}
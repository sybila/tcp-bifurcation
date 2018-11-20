### TCP Bifurcation experiments

This repository contains experimental evaluation of TCP bifurcation behaviour 
in two different models. First model represents RED congestion control and
the second model represents basic TCP packet flow. We also provide simple simulation
mechanisms for both models.

#### Experiment structure

In case of the RED model, we test dependence on two parameters: averaging weight and
number of connections. First two experiments test each parameter separately. The third
experiment then provides combined results for both parameters.  

#### Re-run experiments

The experiments run on a Java Virtual Machine and should be therefore easily
reproducible on almost any platform. The project is built using Gradle and
all dependencies are obtained dynamically when first compiled (internet connection
required).

Each experiment has a dedicated Gradle task which produces the results into a local 
.json file. This file can be then visualised using [Pithya](http://pithya.ics.muni.cz) tool 
(use "advanced settings" and "show grid" checkboxes for better visibility).
Note that the full bifurcation diagram is obtained by combining the visualisations for
various properties in the output file.

*Resources: Note that some experiments can be resource intensive. Each experiment
is configured to use at most 14GB of RAM. If this amount of memory is not 
available, the experiment may fail. Also, experiments typically run from
several minutes up to a few hours on a typical 4-core desktop CPU.*

In order to re-run the experiments, call the following commands in the root of this 
repository (on Windows, substitute gradlew for gradlew.bat):

 - RED Weight: `./gradlew runREDWeight`
 - RED Connections: `./gradlew runREDConnections`
 - RED Both: `./gradlew runREDWeightAndConnections`
 - TCP, 8KiB increments, cyclic timer off: `./gradlew runTCPScale8NoAck`
 - TCP, 8KiB increments, cyclic timer on: `./gradlew runTCPScale8WithAck`
 - TCP, 1KiB increments, cyclic timer off: `./gradlew runTCPNoAck`
 - TCP, 1KiB increments, cyclic timer on: `./gradlew runTCPWithAck`

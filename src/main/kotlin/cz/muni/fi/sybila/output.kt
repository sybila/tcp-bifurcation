package cz.muni.fi.sybila

import com.github.sybila.checker.StateMap
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.model.OdeModel
import com.google.gson.annotations.SerializedName


// For purposes of JSON serialization, intervals are represented as two-element lists.
internal typealias JsonInterval = DoubleArray

// For purposes of JSON serialization, rectangles are represented as lists of intervals.
internal typealias JsonRectangle = Array<JsonInterval>

// For purposes of JSON serialization, parameter sets are represented as lists of rectangles.
internal typealias JsonRectangleSet = Array<JsonRectangle>

// For purposes of JSON serialization, Key-Value pair is a two-element list of integers where
// first integer is an index into the key array and second integer is an index into the value array.
internal typealias JsonKeyValue = IntArray

internal fun jsonKeyValue(a: Int, b: Int) = intArrayOf(a, b)

@Suppress("CanBeParameter", "MemberVisibilityCanBePrivate") // class is for serialisation only
internal class State(
        // Unique ID of a state
        @SerializedName("id")
        val id: Long,
        // List of state bounds for each variable.
        // Each inner list has exactly two elements:
        // [[x1,x2], [y1,y2], [z1, z2]]
        @SerializedName("bounds")
        val bounds: JsonRectangle
) {
    init {
        if (id < 0) error("Invalid state id $id in for $bounds")
        if (bounds.any { it.size != 2 }) error("Invalid state bounds $bounds for id $id")
    }
}

@Suppress("unused")
internal class Result(
        // Name/textual representation of verified property.
        @SerializedName("formula")
        val formula: String,
        // Data array representing mapping from states to parameter values as given by the ResultSet.
        @SerializedName("data")
        val data: List<JsonKeyValue>
)

internal class ResultSet(
        // names of variables in the model
        @SerializedName("variables")
        val variables: List<String>,
        // names of parameters in the model
        @SerializedName("parameters")
        val parameters: List<String>,
        // list of thresholds for each variable
        @SerializedName("thresholds")
        val thresholds: List<List<Double>>,
        @SerializedName("parameter_bounds")
        val parameterBounds: List<JsonInterval>,
        // list of all satisfying states (not necessarily all states in the system)
        @SerializedName("states")
        val states: List<State>,
        @SerializedName("type")
        val type: String,
        // list of all occurring parameter sets used in the result mapping
        @SerializedName("parameter_values")
        val parameterValues: List<JsonRectangleSet>,
        // mapping from states to parameter sets for each result property
        @SerializedName("results")
        val results: List<Result>
)

internal fun Int.expand(model: OdeModel, encoder: NodeEncoder): State {
    return State(id = this.toLong(), bounds = Array(encoder.dimensions) { dim ->
        val c = encoder.coordinate(this, dim)
        val v = model.variables[dim]
        doubleArrayOf(v.thresholds[c], v.thresholds[c + 1])
    })
}

internal fun <T: Any> IntervalSolver<T>.exportResults(odeModel: OdeModel, results: Map<String, List<StateMap<T>>>): ResultSet {
    val encoder = NodeEncoder(odeModel)
    val states = ArrayList<State>()
    val parameterValues = ArrayList<JsonRectangleSet>()
    val stateIndexMap = HashMap<Int, Int>()
    val parameterIndexMap = HashMap<T, Int>()
    val resultSet = results.map { (formula, stateMaps) ->
        val data = ArrayList<JsonKeyValue>()
        stateMaps.forEach { map ->
            map.entries().forEach { (state, params) ->
                val stateIndex = stateIndexMap.computeIfAbsent(state) { s ->
                    states.add(s.expand(odeModel, encoder))
                    states.size - 1
                }
                val paramIndex = parameterIndexMap.computeIfAbsent(params) { p ->
                    parameterValues.add(p.asIntervals())
                    parameterValues.size - 1
                }
                data.add(jsonKeyValue(stateIndex, paramIndex))
            }
        }
        Result(
                formula = formula,
                data = data
        )
    }

    return ResultSet(
            variables = odeModel.variables.map { it.name },
            parameters = odeModel.parameters.map { it.name },
            thresholds = odeModel.variables.map { it.thresholds },
            parameterBounds = odeModel.parameters.map { doubleArrayOf(it.range.first, it.range.second) },
            states = states,
            type = "rectangular",
            parameterValues = parameterValues,
            results = resultSet
    )
}
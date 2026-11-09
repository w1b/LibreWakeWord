package org.librewakeword.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Converts microWakeWord streaming models to the Pixel AoC's SVDF/FULLY_CONNECTED op set.
 *
 * microWakeWord (MixedNet) models stream with resource variables, CONCATENATION, STRIDED_SLICE,
 * SPLIT_V, CONV_2D, DEPTHWISE_CONV_2D and LOGISTIC, none of which the AoC registers. Every layer
 * has an equivalent in ops it does run:
 *
 * - The first CONV_2D sees a window of N feature frames and advances K frames per inference. The
 *   frontend Stacker supplies that window (left context N-1, step K), so it becomes a dense layer.
 * - A DEPTHWISE_CONV_2D over a stream buffer of N steps is an SVDF with identity feature weights,
 *   memory N and the depthwise kernel as time weights. Mixed kernel sizes over channel groups
 *   (SPLIT_V + slices + CONCATENATION) become one SVDF with shorter kernels zero-padded.
 * - A 1x1 CONV_2D is a dense layer. The flatten + FULLY_CONNECTED head over N steps is a rank-N
 *   SVDF; a dense head on the current step only stays a dense layer.
 * - A dense layer is a FULLY_CONNECTED layer, or a memory-1 SVDF if its weights have per-channel
 *   scales: the AoC's FULLY_CONNECTED applies channel 0's scale to every channel.
 * - sigmoid(z) == softmax([0, z])[1], so the head emits [0, z] logits.
 * - Detection follows ESPHome: the mean of the last sliding_window_size probabilities (on
 *   microWakeWord's 1/256 output grid) must reach probability_cutoff, and nothing is detected
 *   during the first 100 inferences after (re)start.
 */
object MicroWakeWord {
    private const val SOFTMAX_SCALE = 1.0 / 65536
    private const val SOFTMAX_ZERO_POINT = -32768L
    private const val PROBABILITY_SCALE = 1.0 / 256
    private const val PROBABILITY_ZERO_POINT = -128L
    /** ESPHome ignores this many inferences after starting and after each detection. */
    private const val MIN_SLICES_BEFORE_DETECTION = 100
    private val IGNORED = setOf("VAR_HANDLE", "READ_VARIABLE", "ASSIGN_VARIABLE", "CALL_ONCE")

    /** A Pixel sound model (sealed TFLite) for a microWakeWord .tflite and its manifest values. */
    fun convert(tflite: ByteArray, probabilityCutoff: Double, slidingWindowSize: Int): ByteArray {
        val src = SourceModel(tflite)
        val blob = PixelModel.build(stacking(src), { sg, features ->
            Converter(src, sg, slidingWindowSize).run(features)
        }, probabilityCutoff)
        if (blob.size > PixelModel.MAX_BYTES) {
            throw UnsupportedModelException(
                "converted model is ${blob.size} bytes; the DSP loads at most ${PixelModel.MAX_BYTES}")
        }
        return blob
    }

    private fun stacking(src: SourceModel): PixelModel.Stacking {
        val perStep = src.tensors[src.inputs[0]].shape[1]
        val firstConv = src.operators.firstOrNull { it.name == "CONV_2D" }
            ?: throw UnsupportedModelException("no CONV_2D")
        val window = src.tensors[firstConv.inputs[1]].shape[1]
        return PixelModel.Stacking(leftContext = window - 1, step = perStep)
    }

    // Symbolic values for tensors of the source graph.
    private sealed interface Sym
    /** A per-inference activation, already emitted into the converted network. */
    private class Value(val tensor: Int, val channels: Int, val quant: Quant) : Sym
    /** The model input: K new feature frames per inference. */
    private object Frames : Sym
    private object State : Sym
    /** The last [length] steps of [src] (a stream buffer), optionally a channel range. */
    private class Window(val src: Sym, val length: Int, val channels: IntRange? = null,
                         val keepLast: Int? = null) : Sym
    /** Pending depthwise convolutions over one stream buffer, merged into one SVDF. */
    private class Depthwise(val window: Window, val out: Quant, val relu: Boolean) : Sym {
        // (first channel, kernel real values [k][c]) aligned to the newest k steps
        val kernels = mutableListOf<Pair<Int, Array<DoubleArray>>>()
        val biases = mutableListOf<Pair<Int, DoubleArray>>()
    }
    private class Flatten(val window: Window) : Sym
    private class Logits(val value: Value) : Sym

    private class Converter(val src: SourceModel, val net: Graph, val window: Int) {
        val stacking = stacking(src)
        val values = HashMap<Int, Sym>()
        val consumers = HashMap<Int, MutableList<String>>()
        lateinit var inputQuant: Quant
        var features = 0
        var channels = 0

        init {
            for (op in src.operators) for (i in op.inputs) {
                consumers.getOrPut(i) { mutableListOf() } += op.name
            }
        }

        fun shape(t: Int) = src.tensors[t].shape
        fun quant(t: Int) = src.tensors[t].quant
            ?: throw UnsupportedModelException("tensor $t is not quantized")
        fun data(t: Int) = src.tensors[t].values()

        fun activation(op: SourceOperator): Boolean = when (op.activation ?: 0) {
            0 -> false
            Builtin.RELU -> true
            else -> throw UnsupportedModelException("unsupported fused activation ${op.activation}")
        }

        fun run(featuresTensor: Int): Int {
            val inp = src.inputs.singleOrNull() ?: throw UnsupportedModelException("expected one input")
            inputQuant = quant(inp)
            values[inp] = Frames
            features = featuresTensor
            channels = shape(inp)[2]
            var logits: Value? = null
            for (op in src.operators) {
                if (op.name in IGNORED) {
                    if (op.name == "READ_VARIABLE") values[op.outputs[0]] = State
                    continue
                }
                when (op.name) {
                    "RESHAPE" -> reshape(op)
                    "CONCATENATION" -> concatenation(op)
                    "STRIDED_SLICE" -> stridedSlice(op)
                    "SPLIT_V" -> splitV(op)
                    "DEPTHWISE_CONV_2D" -> depthwiseConv(op)
                    "CONV_2D" -> conv(op)
                    "FULLY_CONNECTED" -> fullyConnected(op)
                    "LOGISTIC" -> logits = (values[op.inputs[0]] as? Value)
                        ?: throw UnsupportedModelException("unexpected LOGISTIC")
                    "QUANTIZE" -> Unit // int8 → uint8 output cast
                    else -> throw UnsupportedModelException("unsupported op ${op.name}")
                }
            }
            return scores(logits ?: throw UnsupportedModelException("no LOGISTIC head found"))
        }

        fun reshape(op: SourceOperator) {
            values[op.outputs[0]] = when (val s = values[op.inputs[0]]) {
                Frames -> s                   // expand_dims of the input
                is Window -> Flatten(s)       // flatten before the dense head
                else -> throw UnsupportedModelException("unexpected RESHAPE")
            }
        }

        fun concatenation(op: SourceOperator) {
            val ins = op.inputs.map { values[it] }
            if (ins.size == 2 && ins[0] === State) {
                var new = ins[1] ?: throw UnsupportedModelException("unexpected CONCATENATION")
                if (new is Depthwise) new = emitDepthwise(new)
                values[op.outputs[0]] = Window(new, shape(op.outputs[0])[1])
            } else if (ins.all { it is Depthwise }) {
                val merged = ins[0] as Depthwise
                for (other in ins.drop(1).map { it as Depthwise }) {
                    if (other.window.src !== merged.window.src || !other.out.same(merged.out)) {
                        throw UnsupportedModelException("concatenated depthwise convs from different streams")
                    }
                    merged.kernels += other.kernels
                    merged.biases += other.biases
                }
                values[op.outputs[0]] = merged
            } else {
                throw UnsupportedModelException("unexpected CONCATENATION")
            }
        }

        fun stridedSlice(op: SourceOperator) {
            if (consumers[op.outputs[0]].orEmpty().all { it == "ASSIGN_VARIABLE" }) return // state update
            val s = values[op.inputs[0]]
            val strides = data(op.inputs[3])
            if (s !is Window || strides.any { it != 1L }) {
                throw UnsupportedModelException("unexpected STRIDED_SLICE")
            }
            if (shape(op.outputs[0])[3] != shape(op.inputs[0])[3]) {
                throw UnsupportedModelException("STRIDED_SLICE over channels")
            }
            values[op.outputs[0]] = Window(s.src, s.length, s.channels, shape(op.outputs[0])[1])
        }

        fun splitV(op: SourceOperator) {
            val s = values[op.inputs[0]]
            val sizes = data(op.inputs[1])
            val axis = data(op.inputs[2]).single()
            if (s !is Window || (axis != 3L && axis != -1L)) {
                throw UnsupportedModelException("unexpected SPLIT_V")
            }
            var start = 0
            for ((out, size) in op.outputs.zip(sizes.toList())) {
                values[out] = Window(s.src, s.length, start until start + size.toInt())
                start += size.toInt()
            }
        }

        fun depthwiseConv(op: SourceOperator) {
            val win = values[op.inputs[0]]
            if (win !is Window || win.src !is Value) {
                throw UnsupportedModelException("depthwise conv not over a stream buffer")
            }
            val w = data(op.inputs[1])              // [1, k, 1, c]
            val (k, c) = shape(op.inputs[1]).let { it[1] to it[3] }
            val wScale = quant(op.inputs[1]).scale
            val kernel = Array(k) { i -> DoubleArray(c) { j -> w[i * c + j].toDouble() * pick(wScale, j) } }
            if (k != (win.keepLast ?: win.length)) {
                throw UnsupportedModelException("depthwise kernel does not span its window")
            }
            val bScale = quant(op.inputs[2]).scale
            val bias = data(op.inputs[2]).let { b -> DoubleArray(b.size) { b[it].toDouble() * pick(bScale, it) } }
            val first = win.channels?.first ?: 0
            values[op.outputs[0]] = Depthwise(Window(win.src, win.length), quant(op.outputs[0]),
                activation(op)).apply {
                kernels += first to kernel
                biases += first to bias
            }
        }

        fun conv(op: SourceOperator) {
            val s = values[op.inputs[0]]
            val wShape = shape(op.inputs[1])          // [out, kh, kw, in]
            val relu = activation(op)
            val x = if (s is Window && s.src === Frames) {
                if (wShape[1] != stacking.frames || wShape[2] != 1) {
                    throw UnsupportedModelException("first conv must span its frame window")
                }
                input()
            } else {
                val v = if (s is Depthwise) emitDepthwise(s) else s
                if (v !is Value || wShape[1] != 1 || wShape[2] != 1) {
                    throw UnsupportedModelException("unexpected CONV_2D")
                }
                v
            }
            val rows = wShape[0]
            values[op.outputs[0]] = emitFc(x, data(op.inputs[1]), rows, quant(op.inputs[1]),
                data(op.inputs[2]), quant(op.inputs[2]), quant(op.outputs[0]), relu)
        }

        fun fullyConnected(op: SourceOperator) {
            if (shape(op.inputs[1])[0] != 1) throw UnsupportedModelException("expected a single-logit head")
            var s = values[op.inputs[0]]
            if (s is Depthwise) s = emitDepthwise(s)
            values[op.outputs[0]] = when {
                s is Flatten && s.window.src is Value -> emitHead(op, s.window)
                s is Value -> {
                    // Dense head on the current step only: [0, z] logits from one dense layer.
                    val w = data(op.inputs[1])
                    val b = data(op.inputs[2])
                    emitFc(s, LongArray(w.size) + w, 2, quant(op.inputs[1]),
                        LongArray(b.size) + b, quant(op.inputs[2]), quant(op.outputs[0]), relu = false)
                }
                else -> throw UnsupportedModelException("unexpected dense layer")
            }
        }

        // -- emission into the converted network ------------------------------------------
        fun input(): Value {
            val width = channels * stacking.frames
            val x = net.tensor(intArrayOf(1, width), TensorType.INT8,
                inputQuant.perTensorScale, inputQuant.perTensorZeroPoint.toLong())
            net.op(Builtin.QUANTIZE, intArrayOf(features), intArrayOf(x))
            return Value(x, width, inputQuant)
        }

        /** Dense layer y = W x + b; [w] is row-major [rows, x.channels]. */
        fun emitFc(x: Value, w: LongArray, rows: Int, wq: Quant, bias: LongArray, bq: Quant,
                   out: Quant, relu: Boolean): Value {
            val cols = w.size / rows
            if (wq.scale.distinct().size > 1) {
                // The AoC's FULLY_CONNECTED applies channel 0's scale to every channel. An SVDF
                // with memory 1 keeps the int8 weights and carries each channel's scale in its
                // time weight.
                val scales = DoubleArray(rows) { pick(wq.scale, it) }
                val inScale = x.quant.perTensorScale
                val fs = scales.max()
                val stateScale = inScale * fs * max(1.0, accumulatorBound(w, rows, x.quant.perTensorZeroPoint) / 32767.0)
                return svdf(x, ByteArray(w.size) { w[it].toInt().toByte() }, rows, fs,
                    Array(rows) { doubleArrayOf(scales[it] / fs) },
                    DoubleArray(rows) { bias[it].toDouble() * pick(bq.scale, it) },
                    stateScale, rank = 1, out = out, relu = relu)
            }
            val weights = net.constInt8(intArrayOf(rows, cols), ByteArray(w.size) { w[it].toInt().toByte() }, wq.scale)
            val b = net.constInt32(intArrayOf(rows), IntArray(rows) { bias[it].toInt() }, bq.scale)
            val y = net.tensor(intArrayOf(1, rows), TensorType.INT8, out.perTensorScale,
                out.perTensorZeroPoint.toLong())
            net.op(Builtin.FULLY_CONNECTED, intArrayOf(x.tensor, weights, b), intArrayOf(y),
                Builtin.FULLY_CONNECTED_OPTIONS, fullyConnectedOptions(relu))
            return Value(y, rows, out)
        }

        fun emitDepthwise(dw: Depthwise): Value {
            val x = dw.window.src as Value
            val memory = dw.window.length
            val c = x.channels
            val timeReal = Array(c) { DoubleArray(memory) }
            val biasReal = DoubleArray(c)
            for ((first, kernel) in dw.kernels) {
                val k = kernel.size
                for (i in 0 until k) for (j in kernel[i].indices) timeReal[first + j][memory - k + i] = kernel[i][j]
            }
            for ((first, bias) in dw.biases) bias.copyInto(biasReal, first)
            // Identity feature weights at scale 1 make the int16 state hold (x - zero point) exactly.
            val identity = ByteArray(c * c).also { for (i in 0 until c) it[i * c + i] = 1 }
            return svdf(x, identity, c, 1.0, timeReal, biasReal, x.quant.perTensorScale,
                rank = 1, out = dw.out, relu = dw.relu)
        }

        fun emitHead(op: SourceOperator, window: Window): Value {
            val x = window.src as Value
            val memory = window.length
            val c = x.channels
            val w = data(op.inputs[1])              // [1, memory * c]: [time, channel], oldest first
            val wScale = quant(op.inputs[1]).perTensorScale
            val biasReal = data(op.inputs[2])[0].toDouble() * quant(op.inputs[2]).scale[0]
            // Unit 0 is a constant-zero logit; unit 1 has one filter per time step (rank = memory).
            val feature = ByteArray(2 * memory * c)
            for (i in 0 until memory * c) feature[memory * c + i] = w[i].toInt().toByte()
            val timeReal = Array(2 * memory) { DoubleArray(memory) }
            for (t in 0 until memory) timeReal[memory + t][t] = 1.0
            val stateScale = x.quant.perTensorScale * wScale *
                max(1.0, accumulatorBound(w, memory, x.quant.perTensorZeroPoint) / 32767.0)
            return svdf(x, feature, 2 * memory, wScale, timeReal, doubleArrayOf(0.0, biasReal),
                stateScale, rank = memory, out = quant(op.outputs[0]), relu = false)
        }

        /** SVDF with int8 feature weights [filters, x.channels], int16 time weights and state. */
        fun svdf(x: Value, feature: ByteArray, filters: Int, featureScale: Double,
                 timeReal: Array<DoubleArray>, biasReal: DoubleArray, stateScale: Double,
                 rank: Int, out: Quant, relu: Boolean): Value =
            emitSvdf(net, x.tensor, feature, filters, featureScale, timeReal, biasReal, stateScale,
                rank, out.perTensorScale, out.perTensorZeroPoint.toLong(), relu).let {
                Value(it, filters / rank, out)
            }

        fun scores(logits: Value): Int {
            val n = net
            val probs = n.tensor(intArrayOf(1, 2), TensorType.INT16, SOFTMAX_SCALE, SOFTMAX_ZERO_POINT)
            n.op(Builtin.SOFTMAX, intArrayOf(logits.tensor), intArrayOf(probs),
                Builtin.SOFTMAX_OPTIONS, softmaxOptions)
            val p8 = n.tensor(intArrayOf(1, 2), TensorType.INT8, PROBABILITY_SCALE, PROBABILITY_ZERO_POINT)
            n.op(Builtin.QUANTIZE, intArrayOf(probs), intArrayOf(p8))

            // One SVDF keeps the history and emits [mean p0, mean p1, gate]; the gate filter reads
            // p0 + p1 (≈ 1) from MIN_SLICES_BEFORE_DETECTION steps back, scaled ×2 so it
            // saturates to exactly 255/256 once that slot is filled and is exactly 0 before.
            val memory = max(window, MIN_SLICES_BEFORE_DETECTION)
            val feature = byteArrayOf(1, 0, 0, 1, 2, 2)
            val timeReal = Array(3) { DoubleArray(memory) }
            for (f in 0..1) for (t in memory - window until memory) timeReal[f][t] = 1.0 / window
            timeReal[2][0] = 1.0
            val history = emitSvdf(n, p8, feature, 3, 1.0, timeReal, DoubleArray(3),
                PROBABILITY_SCALE, 1, PROBABILITY_SCALE, PROBABILITY_ZERO_POINT, relu = false)

            // relu(mean p1 + gate - 255/256): exactly the mean when open, 0 while gated.
            val gated = n.tensor(intArrayOf(1, 2), TensorType.INT8, PROBABILITY_SCALE, PROBABILITY_ZERO_POINT)
            n.op(Builtin.FULLY_CONNECTED, intArrayOf(history,
                n.constInt8(intArrayOf(2, 3), byteArrayOf(1, 0, 0, 0, 1, 1), doubleArrayOf(1.0)),
                n.constInt32(intArrayOf(2), intArrayOf(0, -255), doubleArrayOf(PROBABILITY_SCALE))),
                intArrayOf(gated), Builtin.FULLY_CONNECTED_OPTIONS, fullyConnectedOptions(true))
            val out = n.tensor(intArrayOf(2), TensorType.INT32, PixelModel.SCORE_SCALE)
            n.op(Builtin.QUANTIZE, intArrayOf(gated), intArrayOf(out))
            return out
        }
    }

    private fun emitSvdf(n: Graph, x: Int, feature: ByteArray, filters: Int, featureScale: Double,
                         timeReal: Array<DoubleArray>, biasReal: DoubleArray, stateScale: Double,
                         rank: Int, outScale: Double, outZeroPoint: Long, relu: Boolean): Int {
        val memory = timeReal[0].size
        val timeScale = max(timeReal.maxOf { row -> row.maxOf { abs(it) } }, 1e-12) / 32767
        val biasScale = stateScale * timeScale
        val y = n.tensor(intArrayOf(1, filters / rank), TensorType.INT8, outScale, outZeroPoint)
        val time = ShortArray(filters * memory) { round(timeReal[it / memory][it % memory] / timeScale).toInt().toShort() }
        val bias = IntArray(biasReal.size) { round(biasReal[it] / biasScale).toLong().toInt() } // per unit
        n.op(Builtin.SVDF, intArrayOf(
            x,
            n.constInt8(intArrayOf(filters, feature.size / filters), feature, doubleArrayOf(featureScale)),
            n.constInt16(intArrayOf(filters, memory), time, timeScale),
            n.constInt32(intArrayOf(bias.size), bias, doubleArrayOf(biasScale)),
            n.tensor(intArrayOf(1, filters * memory), TensorType.INT16, stateScale, variable = true),
        ), intArrayOf(y), Builtin.SVDF_OPTIONS, svdfOptions(rank, relu))
        return y
    }

    /** Largest |sum_k w[r, k] * (x_k - zero point)| over int8 inputs x, over all rows r. */
    private fun accumulatorBound(w: LongArray, rows: Int, zeroPoint: Int): Long {
        val lo = -128L - zeroPoint
        val hi = 127L - zeroPoint
        val cols = w.size / rows
        var bound = 0L
        for (r in 0 until rows) {
            var top = 0L
            var bottom = 0L
            for (k in 0 until cols) {
                val v = w[r * cols + k]
                top += max(v * lo, v * hi)
                bottom += minOf(v * lo, v * hi)
            }
            bound = max(bound, max(abs(top), abs(bottom)))
        }
        return bound
    }

    private fun pick(scale: DoubleArray, i: Int) = if (scale.size == 1) scale[0] else scale[i]
}

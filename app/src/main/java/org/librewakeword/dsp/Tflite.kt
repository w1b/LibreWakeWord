package org.librewakeword.dsp

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.FlexBuffersBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** TFLite schema values used here (tensorflow/lite/schema/schema.fbs). */
internal object Builtin {
    const val CONCATENATION = 2
    const val CONV_2D = 3
    const val DEPTHWISE_CONV_2D = 4
    const val FULLY_CONNECTED = 9
    const val LOGISTIC = 14
    const val RESHAPE = 22
    const val SOFTMAX = 25
    const val SVDF = 27
    const val CUSTOM = 32
    const val STRIDED_SLICE = 45
    const val SPLIT_V = 102
    const val QUANTIZE = 114
    const val IF = 118
    const val CALL_ONCE = 129
    const val VAR_HANDLE = 142
    const val READ_VARIABLE = 143
    const val ASSIGN_VARIABLE = 144

    // BuiltinOptions union members.
    const val SVDF_OPTIONS = 6
    const val FULLY_CONNECTED_OPTIONS = 8
    const val SOFTMAX_OPTIONS = 9
    const val RESHAPE_OPTIONS = 17
    const val IF_OPTIONS = 92

    const val RELU = 1
}

internal object TensorType {
    const val INT32 = 2
    const val UINT8 = 3
    const val INT64 = 4
    const val BOOL = 6
    const val INT16 = 7
    const val INT8 = 9
    const val UINT64 = 12
    const val UINT32 = 15
}

class UnsupportedModelException(message: String) : IllegalArgumentException(message)

/** Per-tensor or per-channel quantization of a source tensor. */
internal class Quant(val scale: DoubleArray, val zeroPoint: LongArray) {
    fun same(other: Quant) =
        scale.contentEquals(other.scale) && zeroPoint.contentEquals(other.zeroPoint)

    val perTensorScale: Double
        get() = if (scale.size == 1) scale[0]
        else throw UnsupportedModelException("expected per-tensor quantization")

    val perTensorZeroPoint: Int
        get() = if (zeroPoint.size == 1) zeroPoint[0].toInt()
        else throw UnsupportedModelException("expected per-tensor quantization")
}

internal class SourceTensor(val shape: IntArray, val type: Int, val quant: Quant?, val data: ByteBuffer?)

internal class SourceOperator(
    val name: String,
    val inputs: IntArray,
    val outputs: IntArray,
    /** Fused activation of CONV_2D / DEPTHWISE_CONV_2D, else null. */
    val activation: Int?,
)

/** The first subgraph of a TFLite flatbuffer, read without generated schema code. */
internal class SourceModel(bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val tensors: List<SourceTensor>
    val operators: List<SourceOperator>
    val inputs: IntArray

    init {
        if (bytes.size < 8 || String(bytes, 4, 4, Charsets.US_ASCII) != "TFL3") {
            throw UnsupportedModelException("not a TFLite model")
        }
        val model = indirect(0)
        val buffers = tables(model, 4)
        val codes = tables(model, 1).map { opcodeName(it) }
        val subgraph = tables(model, 2).firstOrNull()
            ?: throw UnsupportedModelException("model has no subgraph")
        tensors = tables(subgraph, 0).map { readTensor(it, buffers) }
        inputs = ints(subgraph, 1)
        operators = tables(subgraph, 3).map { op ->
            val name = codes[uint(op, 0)]
            val options = field(op, 4).let { if (it == 0) 0 else indirect(it) }
            val activation = when {
                options == 0 -> null
                name == "CONV_2D" -> byte(options, 3)
                name == "DEPTHWISE_CONV_2D" -> byte(options, 4)
                else -> null
            }
            SourceOperator(name, ints(op, 1), ints(op, 2), activation)
        }
    }

    private fun readTensor(t: Int, buffers: List<Int>): SourceTensor {
        val q = field(t, 4).let { if (it == 0) 0 else indirect(it) }
        val quant = if (q == 0) null else {
            val scale = vector(q, 2)?.let { (start, n) ->
                DoubleArray(n) { buf.getFloat(start + 4 * it).toDouble() }
            }
            val zp = vector(q, 3)?.let { (start, n) -> LongArray(n) { buf.getLong(start + 8 * it) } }
            if (scale == null || zp == null) null else Quant(scale, zp)
        }
        val buffer = buffers[uint(t, 2)]
        val data = vector(buffer, 0)?.let { (start, n) -> slice(start, n) } ?: run {
            // Large models keep tensor data after the flatbuffer: Buffer.offset/size.
            val offset = long(buffer, 1)
            val size = long(buffer, 2)
            if (offset > 1 && size > 0) slice(offset.toInt(), size.toInt()) else null
        }
        return SourceTensor(ints(t, 0), byte(t, 1), quant, data)
    }

    private fun slice(start: Int, size: Int): ByteBuffer {
        val d = buf.duplicate()
        d.position(start)
        d.limit(start + size)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun opcodeName(code: Int): String {
        val builtin = maxOf(byte(code, 0), int(code, 3))
        if (builtin == Builtin.CUSTOM) return string(code, 1) ?: "CUSTOM"
        return BUILTIN_NAMES[builtin] ?: "builtin op $builtin"
    }

    // -- flatbuffer access ------------------------------------------------------------------
    private fun indirect(pos: Int) = pos + buf.getInt(pos)

    /** Absolute position of field [id] of the table at [table], or 0 if absent. */
    private fun field(table: Int, id: Int): Int {
        val vtable = table - buf.getInt(table)
        val offset = 4 + 2 * id
        if (offset >= (buf.getShort(vtable).toInt() and 0xffff)) return 0
        val rel = buf.getShort(vtable + offset).toInt() and 0xffff
        return if (rel == 0) 0 else table + rel
    }

    private fun vector(table: Int, id: Int): Pair<Int, Int>? {
        val f = field(table, id)
        if (f == 0) return null
        val v = indirect(f)
        return Pair(v + 4, buf.getInt(v))
    }

    private fun tables(table: Int, id: Int): List<Int> =
        vector(table, id)?.let { (start, n) -> List(n) { indirect(start + 4 * it) } } ?: emptyList()

    private fun ints(table: Int, id: Int): IntArray =
        vector(table, id)?.let { (start, n) -> IntArray(n) { buf.getInt(start + 4 * it) } }
            ?: IntArray(0)

    private fun string(table: Int, id: Int): String? =
        vector(table, id)?.let { (start, n) -> String(ByteArray(n) { buf.get(start + it) }) }

    private fun byte(table: Int, id: Int) = field(table, id).let { if (it == 0) 0 else buf.get(it).toInt() and 0xff }
    private fun int(table: Int, id: Int) = field(table, id).let { if (it == 0) 0 else buf.getInt(it) }
    private fun uint(table: Int, id: Int) = int(table, id)
    private fun long(table: Int, id: Int) = field(table, id).let { if (it == 0) 0L else buf.getLong(it) }

    private companion object {
        val BUILTIN_NAMES = mapOf(
            Builtin.CONCATENATION to "CONCATENATION", Builtin.CONV_2D to "CONV_2D",
            Builtin.DEPTHWISE_CONV_2D to "DEPTHWISE_CONV_2D",
            Builtin.FULLY_CONNECTED to "FULLY_CONNECTED", Builtin.LOGISTIC to "LOGISTIC",
            Builtin.RESHAPE to "RESHAPE", Builtin.STRIDED_SLICE to "STRIDED_SLICE",
            Builtin.SPLIT_V to "SPLIT_V", Builtin.QUANTIZE to "QUANTIZE",
            Builtin.CALL_ONCE to "CALL_ONCE", Builtin.VAR_HANDLE to "VAR_HANDLE",
            Builtin.READ_VARIABLE to "READ_VARIABLE", Builtin.ASSIGN_VARIABLE to "ASSIGN_VARIABLE",
        )
    }
}

/** Typed values of a source tensor's data. */
internal fun SourceTensor.values(): LongArray {
    val d = data ?: throw UnsupportedModelException("expected constant data")
    return when (type) {
        TensorType.INT8 -> LongArray(d.remaining()) { d.get(it).toLong() }
        TensorType.INT32 -> LongArray(d.remaining() / 4) { d.getInt(4 * it).toLong() }
        TensorType.INT64 -> LongArray(d.remaining() / 8) { d.getLong(8 * it) }
        else -> throw UnsupportedModelException("unexpected constant type $type")
    }
}

// -- writing ----------------------------------------------------------------------------------

internal class TensorDef(
    val shape: IntArray,
    val type: Int,
    val buffer: Int,
    val scale: DoubleArray?,
    val zeroPoint: LongArray?,
    val variable: Boolean,
)

internal class OperatorDef(
    val opcode: Int,
    val inputs: IntArray,
    val outputs: IntArray,
    val optionsType: Int = 0,
    val options: ((FlatBufferBuilder) -> Int)? = null,
    val customOptions: ByteArray? = null,
)

/** A TFLite model under construction, for graphs the TFLite converter can't emit. */
internal class ModelBuilder {
    private class Code(val builtin: Int, val custom: String?)

    private val codes = mutableListOf<Code>()
    private val buffers = mutableListOf<ByteArray?>(null) // buffer 0: the empty sentinel
    private val subgraphs = mutableListOf<Graph>()

    fun subgraph(): Graph = Graph(this, subgraphs.size).also { subgraphs += it }

    fun opcode(builtin: Int, custom: String? = null): Int {
        val i = codes.indexOfFirst { it.builtin == builtin && it.custom == custom }
        if (i >= 0) return i
        codes += Code(builtin, custom)
        return codes.size - 1
    }

    fun buffer(data: ByteArray?): Int {
        buffers += data
        return buffers.size - 1
    }

    fun serialize(): ByteArray {
        val b = FlatBufferBuilder(1 shl 16)
        val bufferOffsets = buffers.map { data ->
            val vec = data?.let {
                // TFLite asks for 16-byte aligned buffer data (force_align: 16).
                b.startVector(1, it.size, 16)
                for (i in it.indices.reversed()) b.addByte(it[i])
                b.endVector()
            }
            b.startTable(3)
            if (vec != null) b.addOffset(0, vec, 0)
            b.endTable()
        }
        val codeOffsets = codes.map { code ->
            val custom = code.custom?.let { b.createString(it) }
            b.startTable(4)
            b.addByte(0, minOf(code.builtin, 127).toByte(), 0)
            if (custom != null) b.addOffset(1, custom, 0)
            b.addInt(2, 1, 1)
            b.addInt(3, code.builtin, 0)
            b.endTable()
        }
        val graphOffsets = subgraphs.map { it.serialize(b) }
        val codesVec = b.createVectorOfTables(codeOffsets.toIntArray())
        val graphsVec = b.createVectorOfTables(graphOffsets.toIntArray())
        val buffersVec = b.createVectorOfTables(bufferOffsets.toIntArray())
        b.startTable(5)
        b.addInt(0, 3, 0)
        b.addOffset(1, codesVec, 0)
        b.addOffset(2, graphsVec, 0)
        b.addOffset(4, buffersVec, 0)
        b.finish(b.endTable(), "TFL3")
        return b.sizedByteArray()
    }
}

internal class Graph(private val model: ModelBuilder, val index: Int) {
    val tensors = mutableListOf<TensorDef>()
    val operators = mutableListOf<OperatorDef>()
    private var inputs = IntArray(0)
    private var outputs = IntArray(0)

    fun tensor(
        shape: IntArray, type: Int, data: ByteArray? = null, scale: DoubleArray? = null,
        zeroPoint: LongArray? = null, variable: Boolean = false,
    ): Int {
        val zp = when {
            scale == null -> null
            zeroPoint == null -> LongArray(scale.size)
            zeroPoint.size == 1 && scale.size > 1 -> LongArray(scale.size) { zeroPoint[0] }
            else -> zeroPoint
        }
        tensors += TensorDef(shape, type, model.buffer(data), scale, zp, variable)
        return tensors.size - 1
    }

    fun tensor(shape: IntArray, type: Int, scale: Double, zeroPoint: Long = 0, variable: Boolean = false) =
        tensor(shape, type, null, doubleArrayOf(scale), longArrayOf(zeroPoint), variable)

    fun constInt8(shape: IntArray, values: ByteArray, scale: DoubleArray? = null) =
        tensor(shape, TensorType.INT8, values, scale)

    fun constInt16(shape: IntArray, values: ShortArray, scale: Double? = null) =
        tensor(shape, TensorType.INT16, le(values.size * 2) { b -> values.forEach { b.putShort(it) } },
            scale?.let { doubleArrayOf(it) })

    fun constInt32(shape: IntArray, values: IntArray, scale: DoubleArray? = null) =
        tensor(shape, TensorType.INT32, le(values.size * 4) { b -> values.forEach { b.putInt(it) } },
            scale)

    fun constBool(shape: IntArray, values: BooleanArray) =
        tensor(shape, TensorType.BOOL, ByteArray(values.size) { if (values[it]) 1 else 0 })

    fun op(builtin: Int, inputs: IntArray, outputs: IntArray, optionsType: Int = 0,
           options: ((FlatBufferBuilder) -> Int)? = null) {
        operators += OperatorDef(model.opcode(builtin), inputs, outputs, optionsType, options)
    }

    /** A custom op; [options] become a FlexBuffers map (signal kernels read it by key). */
    fun custom(name: String, inputs: IntArray, outputs: IntArray, options: Map<String, Any> = emptyMap()) {
        val flex = FlexBuffersBuilder()
        val start = flex.startMap()
        for ((key, value) in options) when (value) {
            is Boolean -> flex.putBoolean(key, value)
            is Int -> flex.putInt(key, value.toLong())
            else -> error("unsupported custom option $key")
        }
        flex.endMap(null, start)
        val bytes = flex.finish().let { ByteArray(it.remaining()).also { out -> it.get(out) } }
        operators += OperatorDef(model.opcode(Builtin.CUSTOM, name), inputs, outputs,
            customOptions = bytes)
    }

    fun io(inputs: IntArray, outputs: IntArray) {
        this.inputs = inputs
        this.outputs = outputs
    }

    fun serialize(b: FlatBufferBuilder): Int {
        val tensorOffsets = tensors.map { t ->
            val shape = intVector(b, t.shape)
            val quant = t.scale?.let { scale ->
                val s = floatVector(b, FloatArray(scale.size) { scale[it].toFloat() })
                val z = longVector(b, t.zeroPoint!!)
                b.startTable(7)
                b.addOffset(2, s, 0)
                b.addOffset(3, z, 0)
                b.endTable()
            }
            b.startTable(6)
            b.addOffset(0, shape, 0)
            b.addByte(1, t.type.toByte(), 0)
            b.addInt(2, t.buffer, 0)
            if (quant != null) b.addOffset(4, quant, 0)
            b.addBoolean(5, t.variable, false)
            b.endTable()
        }
        val operatorOffsets = operators.map { op ->
            val ins = intVector(b, op.inputs)
            val outs = intVector(b, op.outputs)
            val options = op.options?.invoke(b)
            val custom = op.customOptions?.let { b.createByteVector(it) }
            b.startTable(7)
            b.addInt(0, op.opcode, 0)
            b.addOffset(1, ins, 0)
            b.addOffset(2, outs, 0)
            if (options != null) {
                b.addByte(3, op.optionsType.toByte(), 0)
                b.addOffset(4, options, 0)
            }
            if (custom != null) b.addOffset(5, custom, 0)
            b.endTable()
        }
        val tensorsVec = b.createVectorOfTables(tensorOffsets.toIntArray())
        val inputsVec = intVector(b, inputs)
        val outputsVec = intVector(b, outputs)
        val operatorsVec = b.createVectorOfTables(operatorOffsets.toIntArray())
        b.startTable(5)
        b.addOffset(0, tensorsVec, 0)
        b.addOffset(1, inputsVec, 0)
        b.addOffset(2, outputsVec, 0)
        b.addOffset(3, operatorsVec, 0)
        return b.endTable()
    }

    private inline fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        fill(b)
        return b.array()
    }
}

// Builtin options tables.
internal fun fullyConnectedOptions(relu: Boolean): (FlatBufferBuilder) -> Int = { b ->
    b.startTable(1); b.addByte(0, if (relu) Builtin.RELU.toByte() else 0, 0); b.endTable()
}

internal fun svdfOptions(rank: Int, relu: Boolean): (FlatBufferBuilder) -> Int = { b ->
    b.startTable(2); b.addInt(0, rank, 0)
    b.addByte(1, if (relu) Builtin.RELU.toByte() else 0, 0); b.endTable()
}

internal val softmaxOptions: (FlatBufferBuilder) -> Int = { b ->
    b.startTable(1); b.addFloat(0, 1.0f, 0.0); b.endTable()
}

internal fun reshapeOptions(shape: IntArray): (FlatBufferBuilder) -> Int = { b ->
    val v = intVector(b, shape)
    b.startTable(1); b.addOffset(0, v, 0); b.endTable()
}

internal fun ifOptions(thenSubgraph: Int, elseSubgraph: Int): (FlatBufferBuilder) -> Int = { b ->
    b.startTable(2); b.addInt(0, thenSubgraph, 0); b.addInt(1, elseSubgraph, 0); b.endTable()
}

private fun intVector(b: FlatBufferBuilder, values: IntArray): Int {
    b.startVector(4, values.size, 4)
    for (i in values.indices.reversed()) b.addInt(values[i])
    return b.endVector()
}

private fun floatVector(b: FlatBufferBuilder, values: FloatArray): Int {
    b.startVector(4, values.size, 4)
    for (i in values.indices.reversed()) b.addFloat(values[i])
    return b.endVector()
}

private fun longVector(b: FlatBufferBuilder, values: LongArray): Int {
    b.startVector(8, values.size, 8)
    for (i in values.indices.reversed()) b.addLong(values[i])
    return b.endVector()
}

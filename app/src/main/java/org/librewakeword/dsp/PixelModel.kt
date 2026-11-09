package org.librewakeword.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.round

/**
 * A streaming keyphrase model in the Pixel AoC's graph contract (docs/dsp/pixel-8a.md):
 *
 *     main:      int16[160] audio → Framer → IF(frame ready) frontend → IF(features ready) network
 *     frontend:  frame → log-mel features → Stacker → int16[stack] features, ready
 *     network:   int16[stack] features → … → int32[2] scores → BasicClassifier(threshold)
 *
 * Outputs of main are (detected bool[1], keyword int32[1], features ready bool[]).
 */
internal object PixelModel {
    const val SCORE_SCALE = 1.0 / 32767
    /** Largest model the AoC loads; larger blobs are truncated and fail the CRC check. */
    const val MAX_BYTES = 114 * 1024

    class Stacking(val leftContext: Int, val step: Int) {
        val frames get() = leftContext + 1
    }

    /** Builds the network body: given the features tensor, returns int32[2] scores. */
    fun interface Network {
        fun build(sg: Graph, features: Int): Int
    }

    fun build(stacking: Stacking, network: Network, threshold: Double): ByteArray {
        val model = ModelBuilder()
        val main = model.subgraph()
        val ch = Frontend.NUM_CHANNELS
        val width = ch * stacking.frames
        val i16 = TensorType.INT16
        val bool = TensorType.BOOL

        val audio = main.tensor(intArrayOf(Frontend.FRAME_STEP), i16)
        val frame = main.tensor(intArrayOf(1, Frontend.FRAME_SIZE), i16)
        val frameReady = main.tensor(intArrayOf(), bool)
        main.custom("Framer", intArrayOf(audio), intArrayOf(frame, frameReady), mapOf(
            "frame_size" to Frontend.FRAME_SIZE, "frame_step" to Frontend.FRAME_STEP,
            "prefill" to false))

        // Frontend and its idle counterpart: (features, features ready).
        val fe = model.subgraph()
        val feIn = fe.tensor(intArrayOf(1, Frontend.FRAME_SIZE), i16)
        val flat = fe.tensor(intArrayOf(Frontend.FRAME_SIZE), i16)
        fe.op(Builtin.RESHAPE, intArrayOf(feIn, fe.constInt32(intArrayOf(1),
            intArrayOf(Frontend.FRAME_SIZE))), intArrayOf(flat))
        val mel = Frontend.addFeatures(fe, flat)
        val stacked = fe.tensor(intArrayOf(width), i16)
        val stackedReady = fe.tensor(intArrayOf(), bool)
        fe.custom("Stacker", intArrayOf(mel), intArrayOf(stacked, stackedReady), mapOf(
            "num_channels" to ch, "stacker_left_context" to stacking.leftContext,
            "stacker_right_context" to 0, "stacker_step" to stacking.step))
        fe.io(intArrayOf(feIn), intArrayOf(stacked, stackedReady))
        val feIdle = model.subgraph()
        feIdle.io(intArrayOf(feIdle.tensor(intArrayOf(1, Frontend.FRAME_SIZE), i16)), intArrayOf(
            feIdle.constInt16(intArrayOf(width), ShortArray(width)),
            feIdle.constBool(intArrayOf(), booleanArrayOf(false))))

        val features = main.tensor(intArrayOf(width), i16)
        val featuresReady = main.tensor(intArrayOf(), bool)
        main.op(Builtin.IF, intArrayOf(frameReady, frame), intArrayOf(features, featuresReady),
            Builtin.IF_OPTIONS, ifOptions(fe.index, feIdle.index))

        // Network and its idle counterpart.
        val net = model.subgraph()
        val netIn = net.tensor(intArrayOf(width), i16, Frontend.FEATURE_SCALE)
        val scores = network.build(net, netIn)
        val detected = net.tensor(intArrayOf(1), bool)
        val keyword = net.tensor(intArrayOf(1), TensorType.INT32)
        net.custom("BasicClassifier", intArrayOf(scores,
            net.constInt32(intArrayOf(1), intArrayOf(1)),
            net.constInt32(intArrayOf(1), intArrayOf(round(threshold / SCORE_SCALE).toInt()))),
            intArrayOf(detected, keyword))
        net.io(intArrayOf(netIn), intArrayOf(detected, keyword))
        val netIdle = model.subgraph()
        netIdle.io(intArrayOf(netIdle.tensor(intArrayOf(width), i16, Frontend.FEATURE_SCALE)),
            intArrayOf(netIdle.constBool(intArrayOf(1), booleanArrayOf(false)),
                netIdle.constInt32(intArrayOf(1), intArrayOf(0))))
        val outs = intArrayOf(main.tensor(intArrayOf(1), bool),
            main.tensor(intArrayOf(1), TensorType.INT32))
        main.op(Builtin.IF, intArrayOf(featuresReady, features), outs, Builtin.IF_OPTIONS,
            ifOptions(net.index, netIdle.index))
        main.io(intArrayOf(audio), outs + featuresReady)
        return seal(model.serialize())
    }

    /** The sound-model container: the TFLite flatbuffer plus a little-endian zlib CRC32 of it. */
    fun seal(tflite: ByteArray): ByteArray {
        val crc = CRC32().apply { update(tflite) }.value.toInt()
        return tflite + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array()
    }
}

package org.librewakeword.dsp

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * micro_frontend (the feature generator microWakeWord trains on) built from the TFLM signal ops
 * the Pixel AoC registers: Window → FftAutoScale → Rfft → Energy → FilterBank →
 * FilterBankSquareRoot → FilterBankSpectralSubtraction → Pcan → FilterBankLog. The int16 output
 * equals micro_frontend's uint16 features.
 *
 * Constants are ported from micro_frontend (tensorflow/lite/experimental/microfrontend/lib,
 * Apache-2.0): window_util, filterbank_util and pcan_gain_control_util, keeping their float32
 * arithmetic and rounding.
 */
internal object Frontend {
    /** Feature scale microWakeWord applies to micro_frontend output (uint16 / 25.6). */
    const val FEATURE_SCALE = 0.0390625

    // microWakeWord's settings: 30 ms window, 10 ms step, 40 channels.
    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = SAMPLE_RATE * 30 / 1000
    const val FRAME_STEP = SAMPLE_RATE * 10 / 1000
    const val NUM_CHANNELS = 40
    private const val WINDOW_SCALING_BITS = 12
    private const val LOWER_BAND_HZ = 125.0f
    private const val UPPER_BAND_HZ = 7500.0f
    private const val FILTER_BANK_SCALING_BITS = 12
    private const val FILTER_BANK_ALIGNMENT = 4
    private const val CHANNEL_BLOCK_SIZE = 4
    private const val POST_SCALING_BITS = 6
    private const val SPECTRAL_SUBTRACTION_BITS = 14
    private const val SMOOTHING_BITS = 10
    private const val EVEN_SMOOTHING = 0.025
    private const val ODD_SMOOTHING = 0.06
    private const val MIN_SIGNAL_REMAINING = 0.05
    private const val PCAN_STRENGTH = 0.95f
    private const val PCAN_OFFSET = 80.0f
    private const val PCAN_GAIN_BITS = 21

    private val FFT_BITS = ceil(ln(FRAME_SIZE.toDouble()) / ln(2.0)).toInt()
    private val CORRECTION_BITS = FFT_BITS - FILTER_BANK_SCALING_BITS / 2

    /** Signal ops are registered under bare names on the Pixel AoC. */
    fun addFeatures(sg: Graph, frame: Int): Int {
        val fb = filterBank()
        val fftLength = 1 shl FFT_BITS
        val i16 = TensorType.INT16
        val u32 = TensorType.UINT32

        val windowed = sg.tensor(intArrayOf(FRAME_SIZE), i16)
        sg.custom("Window", intArrayOf(frame, sg.constInt16(intArrayOf(FRAME_SIZE),
            hannWindow(FRAME_SIZE, WINDOW_SCALING_BITS))), intArrayOf(windowed),
            mapOf("shift" to WINDOW_SCALING_BITS))
        val scaled = sg.tensor(intArrayOf(FRAME_SIZE), i16)
        val fftShift = sg.tensor(intArrayOf(), TensorType.INT32)
        sg.custom("FftAutoScale", intArrayOf(windowed), intArrayOf(scaled, fftShift))
        val spectrum = sg.tensor(intArrayOf(fftLength + 2), i16)
        sg.custom("Rfft", intArrayOf(scaled), intArrayOf(spectrum),
            mapOf("T" to TensorType.INT16, "fft_length" to fftLength))
        val energy = sg.tensor(intArrayOf(fftLength / 2 + 1), u32)
        sg.custom("Energy", intArrayOf(spectrum), intArrayOf(energy),
            mapOf("start_index" to fb.startIndex, "end_index" to fb.endIndex))
        val banks = sg.tensor(intArrayOf(NUM_CHANNELS), TensorType.UINT64)
        sg.custom("FilterBank", intArrayOf(energy,
            sg.constInt16(intArrayOf(fb.weights.size), fb.weights),
            sg.constInt16(intArrayOf(fb.unweights.size), fb.unweights),
            sg.constInt16(intArrayOf(NUM_CHANNELS + 1), fb.channelFrequencyStarts),
            sg.constInt16(intArrayOf(NUM_CHANNELS + 1), fb.channelWeightStarts),
            sg.constInt16(intArrayOf(NUM_CHANNELS + 1), fb.channelWidths)),
            intArrayOf(banks), mapOf("num_channels" to NUM_CHANNELS))
        val amplitude = sg.tensor(intArrayOf(NUM_CHANNELS), u32)
        sg.custom("FilterBankSquareRoot", intArrayOf(banks, fftShift), intArrayOf(amplitude))

        val one = 1 shl SPECTRAL_SUBTRACTION_BITS
        val smoothing = (EVEN_SMOOTHING * one).toInt()
        val alternate = (ODD_SMOOTHING * one).toInt()
        val denoised = sg.tensor(intArrayOf(NUM_CHANNELS), u32)
        val noise = sg.tensor(intArrayOf(NUM_CHANNELS), u32)
        sg.custom("FilterBankSpectralSubtraction", intArrayOf(amplitude), intArrayOf(denoised, noise),
            mapOf(
                "num_channels" to NUM_CHANNELS,
                "smoothing" to smoothing, "one_minus_smoothing" to one - smoothing,
                "alternate_smoothing" to alternate, "alternate_one_minus_smoothing" to one - alternate,
                "smoothing_bits" to SMOOTHING_BITS,
                "min_signal_remaining" to (MIN_SIGNAL_REMAINING * one).toInt(),
                "clamping" to false,
                "spectral_subtraction_bits" to SPECTRAL_SUBTRACTION_BITS,
            ))
        val lut = pcanGainLut(SMOOTHING_BITS - CORRECTION_BITS)
        val normalized = sg.tensor(intArrayOf(NUM_CHANNELS), u32)
        // tflite-micro's pcan_op wrapper always passes snr_shift = 6.
        sg.custom("Pcan", intArrayOf(denoised, noise, sg.constInt16(intArrayOf(lut.size), lut)),
            intArrayOf(normalized), mapOf("snr_shift" to 6))
        val features = sg.tensor(intArrayOf(NUM_CHANNELS), i16)
        sg.custom("FilterBankLog", intArrayOf(normalized), intArrayOf(features), mapOf(
            "input_correction_bits" to CORRECTION_BITS,
            "output_scale" to (1 shl POST_SCALING_BITS),
        ))
        return features
    }

    private fun freqToMel(freq: Float): Float = 1127.0f * ln1p((freq / 700.0f).toDouble()).toFloat()

    /** window_util: float32 Hann, rounded. */
    fun hannWindow(length: Int, shift: Int): ShortArray {
        val arg = Math.PI.toFloat() * 2.0f / length.toFloat()
        return ShortArray(length) { i ->
            val value = 0.5f - 0.5f * cos((arg * (i.toFloat() + 0.5f)).toDouble()).toFloat()
            floor(value * (1 shl shift).toFloat() + 0.5f).toInt().toShort()
        }
    }

    class FilterBank(
        val startIndex: Int,
        val endIndex: Int,
        val weights: ShortArray,
        val unweights: ShortArray,
        val channelFrequencyStarts: ShortArray,
        val channelWeightStarts: ShortArray,
        val channelWidths: ShortArray,
    )

    /** filterbank_util (float32 throughout, rounded weights). */
    fun filterBank(): FilterBank {
        val spectrumSize = (1 shl FFT_BITS) / 2 + 1
        val n = NUM_CHANNELS + 1
        val indexAlignment = maxOf(1, FILTER_BANK_ALIGNMENT / 2)
        val starts = ShortArray(n)
        val weightStarts = ShortArray(n)
        val widths = ShortArray(n)
        val actualStarts = IntArray(n)
        val actualWidths = IntArray(n)

        val melLow = freqToMel(LOWER_BAND_HZ)
        val melSpacing = (freqToMel(UPPER_BAND_HZ) - melLow) / n.toFloat()
        val centerMel = FloatArray(n) { melLow + melSpacing * (it + 1).toFloat() }

        val hzPerSbin = 0.5f * SAMPLE_RATE.toFloat() / (spectrumSize - 1).toFloat()
        val startIndex = (1.5f + LOWER_BAND_HZ / hzPerSbin).toInt()
        var chanStart = startIndex
        var weightIndex = 0
        var needsZeros = false
        for (chan in 0 until n) {
            var freqIndex = chanStart
            while (freqToMel(freqIndex.toFloat() * hzPerSbin) <= centerMel[chan]) freqIndex++
            val width = freqIndex - chanStart
            actualStarts[chan] = chanStart
            actualWidths[chan] = width
            if (width == 0) {
                starts[chan] = 0
                weightStarts[chan] = 0
                widths[chan] = CHANNEL_BLOCK_SIZE.toShort()
                if (!needsZeros) {
                    needsZeros = true
                    for (j in 0 until chan) {
                        weightStarts[j] = (weightStarts[j] + CHANNEL_BLOCK_SIZE).toShort()
                    }
                    weightIndex += CHANNEL_BLOCK_SIZE
                }
            } else {
                val alignedStart = chanStart / indexAlignment * indexAlignment
                val padded = ((chanStart - alignedStart + width - 1) / CHANNEL_BLOCK_SIZE + 1) *
                    CHANNEL_BLOCK_SIZE
                starts[chan] = alignedStart.toShort()
                weightStarts[chan] = weightIndex.toShort()
                widths[chan] = padded.toShort()
                weightIndex += padded
            }
            chanStart = freqIndex
        }

        val weights = ShortArray(weightIndex)
        val unweights = ShortArray(weightIndex)
        var endIndex = 0
        val scale = (1 shl FILTER_BANK_SCALING_BITS).toFloat()
        for (chan in 0 until n) {
            var frequency = actualStarts[chan]
            val offset = frequency - starts[chan]
            val denom = if (chan == 0) melLow else centerMel[chan - 1]
            for (j in 0 until actualWidths[chan]) {
                val w = (centerMel[chan] - freqToMel(frequency.toFloat() * hzPerSbin)) /
                    (centerMel[chan] - denom)
                val idx = weightStarts[chan] + offset + j
                weights[idx] = floor(w * scale + 0.5f).toInt().toShort()
                unweights[idx] = floor((1.0f - w) * scale + 0.5f).toInt().toShort()
                frequency++
            }
            endIndex = maxOf(endIndex, frequency)
        }
        check(endIndex < spectrumSize) { "filter bank exceeds the spectrum" }
        return FilterBank(startIndex, endIndex, weights, unweights, starts, weightStarts, widths)
    }

    /** tflite-micro's wide_dynamic_func_lut (piecewise-quadratic PCAN gain table). */
    fun pcanGainLut(inputBits: Int): ShortArray {
        fun gain(x: Long): Long {
            val xf = x.toFloat() / (1L shl inputBits).toFloat()
            val g = (1 shl PCAN_GAIN_BITS).toFloat() *
                (xf + PCAN_OFFSET).toDouble().pow((-PCAN_STRENGTH).toDouble()).toFloat()
            return if (g > 32767f) 32767 else (g + 0.5f).toInt().toShort().toLong()
        }

        val bits = 32
        val lut = LongArray(4 * bits - 3 + 1)
        lut[0] = gain(0)
        lut[1] = gain(1)
        for (interval in 2..bits) {
            val x0 = 1L shl (interval - 1)
            val x1 = x0 + (x0 shr 1)
            val x2 = if (interval == bits) x0 + (x0 - 1) else 2 * x0
            val y0 = gain(x0)
            val y1 = gain(x1)
            val y2 = gain(x2)
            val a1 = 4 * (y1 - y0) - (y2 - y0)
            val base = 4 * interval - 6
            lut[base] = y0
            lut[base + 1] = a1
            lut[base + 2] = (y2 - y0) - a1
            lut[base + 3] = 0
        }
        return ShortArray(4 * bits - 3) { lut[it].toShort() }
    }
}

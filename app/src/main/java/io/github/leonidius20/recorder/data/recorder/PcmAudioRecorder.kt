package io.github.leonidius20.recorder.data.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.leonidius20.recorder.data.settings.AudioChannels
import io.github.leonidius20.recorder.data.settings.PcmBitDepthOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max

private const val WAV_HEADER_LENGTH_BYTES = 44

class PcmAudioRecorder(
    private val descriptor: ParcelFileDescriptor,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val sampleRate: Int,
    private val monoOrStereo: AudioChannels = AudioChannels.MONO,
    private val bitDepth: PcmBitDepthOption = PcmBitDepthOption.PCM_16BIT_INT,

    /**
     * used to launch the coroutine reading bytes from mic in loop
     */
    private val coroutineScope: CoroutineScope,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AudioRecorder {


    private lateinit var audioRecord: AudioRecord

    private lateinit var micReadingThread: Job

    private val inputChannel = when (monoOrStereo) {
        AudioChannels.MONO -> AudioFormat.CHANNEL_IN_MONO
        AudioChannels.STEREO -> AudioFormat.CHANNEL_IN_STEREO
    }

    val encoder = bitDepth.valueForAudioRecordApi

    val minBufSize = AudioRecord.getMinBufferSize(
        sampleRate, inputChannel, encoder
    )
    val bufSize = minBufSize * 4 // why 4?

    private val isPausedState = MutableStateFlow(false)

    private val maxAmplitudeState = MutableStateFlow(0)

    private val maxAmplitudeExtractor = bitDepth.maxAmplitudeExtractorFactory()

    @SuppressLint("MissingPermission")
    override fun start() {

        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            inputChannel,
            encoder,
            bufSize
        )

        audioRecord.startRecording()

        micReadingThread = coroutineScope.launch(cpuDispatcher) {
            val outStream = FileOutputStream(descriptor.fileDescriptor).also {
                // leaving space for the header
                it.channel.position(WAV_HEADER_LENGTH_BYTES.toLong())
            }

            val buffer = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.LITTLE_ENDIAN)

            var bytesRecorded = 0

            while (isActive) {
                //val time = measureTime {

                if (isPausedState.value == true) {
                    // waiting either to be resumed, or to be stopped (cancelled)
                    try {
                        isPausedState.first { it == false }
                    } catch (_: CancellationException) {
                        // recording got stopped (coroutine cancelled) while waiting
                        break // get to writing the header and closing streams
                    }
                }

                val bytesRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioRecord.read(
                        buffer, bufSize,
                        AudioRecord.READ_NON_BLOCKING,
                    )
                } else {
                    audioRecord.read(
                        buffer, bufSize,
                    )
                }

                if (bytesRead == 0
                    || bytesRead == AudioRecord.ERROR_INVALID_OPERATION
                    || bytesRead == AudioRecord.ERROR_BAD_VALUE
                    || bytesRead == AudioRecord.ERROR_DEAD_OBJECT
                    || bytesRead == AudioRecord.ERROR
                ) {
                    continue
                }

                //val readBytesAsArray = buffer.capacity()//.sliceArray(0 until bytesRead)

                //val bb = buffer
                buffer.limit(bytesRead)

                outStream.channel.write(buffer)
                // outStream.write(readBytesAsArray)
                bytesRecorded += bytesRead
                extractAndRecordMaxAmplitude(buffer)

                buffer.clear()
                //}

                // Log.d("timing", "It took $time ms to run one iteration of loop")
            }

            audioRecord.apply {
                stop()
                release()
            }

            outStream.channel.position(0) // back to the start to fill in the header
            outStream.write(
                generateWavHeader(
                    bytesRecorded = bytesRecorded,
                    numOfChannels = monoOrStereo.numberOfChannels().toShort(),
                    sampleRateHz = sampleRate,
                )
            )

            outStream.close()
        }

    }

    override suspend fun stop() {
        micReadingThread.cancelAndJoin()
    }

    override fun pause() {
        isPausedState.value = true
    }

    override fun resume() {
        isPausedState.value = false
    }

    private fun generateWavHeader(
        bytesRecorded: Int,
        numOfChannels: Short,
        sampleRateHz: Int,
    ): ByteArray {

        // todo: redo this header with bit shifts

        val header = ByteArray(WAV_HEADER_LENGTH_BYTES)

        /*
         *   [Master RIFF chunk]
         */
        // "RIFF".toByteArray().copyInto(header, destinationOffset = 0) // 4 bytes
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()


        val fileSizeMinus8Bytes = bytesRecorded + WAV_HEADER_LENGTH_BYTES - 8

        //fileSizeMinus8Bytes.toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 4) // 4 bytes

        header[4] = (fileSizeMinus8Bytes and 0xff).toByte()
        header[5] = (fileSizeMinus8Bytes shr 8 and 0xff).toByte()
        header[6] = (fileSizeMinus8Bytes shr 16 and 0xff).toByte()
        header[7] = (fileSizeMinus8Bytes shr 24 and 0xff).toByte()

        //"WAVE".toByteArray().copyInto(header, destinationOffset = 8) // 4 bytes
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        /*
         *   [Chunk describing the data format]
         */
        // "fmt ".toByteArray().copyInto(header, destinationOffset = 12) // 4 bytes
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        //val sizeOfDataChunkWithoutFirstTwoFields = 16
        //sizeOfDataChunkWithoutFirstTwoFields
        //    .toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 16) // 4 bytes
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        //val audioFormat: Short = 1 // 1 - pcm int, 3 - IEEE 754 float
        // audioFormat
        //    .toLittleEndianByteArray()
        //    .copyInto(header, 20) // 2 bytes
        header[20] = if (bitDepth.isFloat) 3 else 1 // 1 - pcm int, 3 - IEEE 754 float
        header[21] = 0

        // numOfChannels
        //   .toLittleEndianByteArray()
        //     .copyInto(header, 22) // 2 bytes
        header[22] = numOfChannels.toByte()
        header[23] = 0

        //sampleRateHz
        //     .toLittleEndianByteArray()
        //    .copyInto(header, 24) // 4 bytes
        header[24] = (sampleRateHz and 0xff).toByte()
        header[25] = (sampleRateHz shr 8 and 0xff).toByte()
        header[26] = 0 // could >> 16, but it will never be more than 48000 which fits in 2 bytes
        header[27] = 0

        val bitsPerSample: Short = bitDepth.bitsPerSample
        val bytesPerBlock: Short = ((numOfChannels * bitsPerSample) / 8).toShort() // max 4
        val bytesPerSecond: Int = bytesPerBlock * sampleRateHz // max 4 * 48_000 = ?

        //bytesPerSecond
        //   .toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 28) // 4 bytes
        header[28] = (bytesPerSecond and 0xff).toByte()
        header[29] = (bytesPerSecond shr 8 and 0xff).toByte()
        header[30] = (bytesPerSecond shr 16 and 0xff).toByte()
        header[31] = (bytesPerSecond shr 24 and 0xff).toByte()

        //bytesPerBlock
        //    .toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 32) // 2 bytes
        header[32] = bytesPerBlock.toByte() // fits into 1 byte even though 2 are given here
        header[33] = 0


        // bitsPerSample
        //    .toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 34) // 2 bytes
        header[34] = bitsPerSample.toByte()
        header[35] = 0  // fits into 1 byte even though 2 are given here


        /*
         *   [Chunk containing the sampled data]
         */
        // "data".toByteArray()
        //   .copyInto(header, destinationOffset = 36) // 4 bytes
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()


        //bytesRecorded
        //    .toLittleEndianByteArray()
        //    .copyInto(header, destinationOffset = 40) // 4 bytes
        header[40] = (bytesRecorded and 0xff).toByte()
        header[41] = (bytesRecorded shr 8 and 0xff).toByte()
        header[42] = (bytesRecorded shr 16 and 0xff).toByte()
        header[43] = (bytesRecorded shr 24 and 0xff).toByte()


        return header
    }

    private val bitsPerSample =
        bitDepth.bitsPerSample // for now 16_BIT // means 16 bits per one sample. If stereo, there are going to be 2 samples for left and right for a total of 32 bits (4 bytes)

    // bytes per one sample, if stereo that would be only left or only right channel sample
    private val bytesPerSample = (bitsPerSample / 8)

    // by instant i mean 1 sample if it is mono or 2 samples (left and right) from one instant in time, if it is stereo
    private val bytesPerInstant = bytesPerSample * monoOrStereo.numberOfChannels()


    // this is happening in a non-main thread that reads bytes from mic
    private fun extractAndRecordMaxAmplitude(pcmBytes: ByteBuffer) {
        val valueForThisBuffer = maxAmplitudeExtractor.extractFrom(
            buffer = pcmBytes,
            numberOfChannels = monoOrStereo.numberOfChannels()
        )

        maxAmplitudeState.update { currentValue ->
            max(currentValue, valueForThisBuffer)
        }
    }

    override fun maxAmplitude(): Int {
        // return old value and set new value to 0
        return maxAmplitudeState.getAndUpdate { 0 }
    }

    override fun supportsPausing() = true

}
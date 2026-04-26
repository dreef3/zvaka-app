package com.dreef3.weightlossapp.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

class SmolVlmFoodEstimationEngine(
    private val context: Context,
    private val modelFile: File,
) : FoodEstimationEngine {
    private val interpreterMutex = Mutex()
    private val assetsMutex = Mutex()
    private var interpreter: Interpreter? = null
    private var assets: SmolVlmAssets? = null

    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                logModelLookup("estimate-start")
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
                }

                val bitmap = BitmapFactory.decodeFile(request.imagePath)
                    ?: throw FoodEstimationException(FoodEstimationError.UnreadableImage)

                val activeInterpreter = getOrCreateInterpreter()
                val activeAssets = getOrCreateAssets()
                val raw = runInference(
                    interpreter = activeInterpreter,
                    assets = activeAssets,
                    bitmap = bitmap,
                )
                FoodEstimationTextParser.parse(raw)
            }.recoverCatching { error ->
                if (error is FoodEstimationException) throw error
                Log.e(TAG, "SmolVLM estimation failed", error)
                throw FoodEstimationException(FoodEstimationError.EstimationFailed)
            }
        }

    override suspend fun warmUp(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            logModelLookup("warmup-start")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw FoodEstimationException(FoodEstimationError.ModelUnavailable)
            }
            getOrCreateAssets()
            getOrCreateInterpreter()
            Unit
        }
    }

    private suspend fun getOrCreateInterpreter(): Interpreter = interpreterMutex.withLock {
        interpreter?.let { return it }
        val options = Interpreter.Options().apply {
            setNumThreads(DEFAULT_NUM_THREADS)
        }
        return Interpreter(modelFile, options).also { created ->
            interpreter = created
            Log.i(TAG, "Initialized SmolVLM interpreter with signatures=${created.signatureKeys.joinToString()}")
        }
    }

    private suspend fun getOrCreateAssets(): SmolVlmAssets = assetsMutex.withLock {
        assets?.let { return it }
        return context.assets.open(PROMPT_ASSET_NAME).bufferedReader().use { promptReader ->
            val promptJson = JSONObject(promptReader.readText())
            val promptArray = promptJson.getJSONArray("input_ids")
            val promptIds = IntArray(promptArray.length()) { index -> promptArray.getInt(index) }
            context.assets.open(TOKEN_MAP_ASSET_NAME).bufferedReader().use { tokenReader ->
                val tokenJson = JSONObject(tokenReader.readText())
                val piecesJson = tokenJson.getJSONArray("pieces")
                val pieces = List(piecesJson.length()) { index -> piecesJson.getString(index) }
                SmolVlmAssets(
                    eosTokenId = promptJson.getInt("eos"),
                    promptTokenIds = promptIds,
                    tokenPieces = pieces,
                ).also { loaded ->
                    assets = loaded
                }
            }
        }
    }

    private fun runInference(
        interpreter: Interpreter,
        assets: SmolVlmAssets,
        bitmap: Bitmap,
    ): String {
        val prefillSignature = interpreter.signatureKeys
            .firstOrNull { key -> key.contains("prefill") && key.contains("pixel") }
            ?: throw FoodEstimationException(FoodEstimationError.ModelLoadFailed)
        val decodeSignature = DECODE_SIGNATURE
        val decodeInputNames = interpreter.getSignatureInputs(decodeSignature).toSet()
        val decodeOutputNames = interpreter.getSignatureOutputs(decodeSignature).toSet()
        val kvCacheNames = decodeInputNames
            .intersect(decodeOutputNames)
            .filter { it.startsWith(KV_CACHE_PREFIX) }
            .sorted()
        if (kvCacheNames.isEmpty()) {
            throw FoodEstimationException(FoodEstimationError.ModelLoadFailed)
        }

        val kvBuffers = kvCacheNames.associateWith { name ->
            allocateBuffer(interpreter.getInputTensorFromSignature(name, decodeSignature))
        }

        val pixelValues = createPixelValuesBuffer(bitmap)
        val prefillPrompt = assets.promptTokenIds
        val prefillTokenCount = max(prefillPrompt.size - 1, 1)
        val prefillInputs = mutableMapOf<String, Any>(
            TOKENS_INPUT to createPaddedIntBuffer(
                tensor = interpreter.getInputTensorFromSignature(TOKENS_INPUT, prefillSignature),
                values = prefillPrompt.copyOf(prefillTokenCount),
                batched = true,
            ),
            INPUT_POS_INPUT to createInputPositionsBuffer(
                tensor = interpreter.getInputTensorFromSignature(INPUT_POS_INPUT, prefillSignature),
                count = prefillTokenCount,
            ),
            PIXEL_VALUES_INPUT to pixelValues,
        )
        kvCacheNames.forEach { name ->
            prefillInputs[name] = kvBuffers.getValue(name).duplicateWithNativeOrder()
        }
        val prefillOutputs = kvCacheNames.associateWith { name ->
            kvBuffers.getValue(name).duplicateWithNativeOrder() as Any
        }.toMutableMap()

        interpreter.runSignature(prefillInputs, prefillOutputs, prefillSignature)

        var nextToken = prefillPrompt.last()
        var nextPosition = prefillPrompt.size - 1
        val decodeBuilder = StringBuilder()
        val logitsTensor = interpreter.getOutputTensorFromSignature(LOGITS_OUTPUT, decodeSignature)
        val logitsBuffer = allocateBuffer(logitsTensor)

        repeat(MAX_DECODE_STEPS) {
            val decodeInputs = mutableMapOf<String, Any>(
                TOKENS_INPUT to createSingleTokenBuffer(nextToken),
                INPUT_POS_INPUT to createSinglePositionBuffer(nextPosition),
            )
            if (MASK_INPUT in decodeInputNames) {
                decodeInputs[MASK_INPUT] = createDecodeMaskBuffer(
                    tensor = interpreter.getInputTensorFromSignature(MASK_INPUT, decodeSignature),
                    position = nextPosition,
                )
            }
            kvCacheNames.forEach { name ->
                decodeInputs[name] = kvBuffers.getValue(name).duplicateWithNativeOrder()
            }

            val decodeOutputs = mutableMapOf<String, Any>(
                LOGITS_OUTPUT to logitsBuffer.duplicateWithNativeOrder(),
            )
            kvCacheNames.forEach { name ->
                decodeOutputs[name] = kvBuffers.getValue(name).duplicateWithNativeOrder()
            }

            interpreter.runSignature(decodeInputs, decodeOutputs, decodeSignature)
            val predictedToken = argMaxTokenId(logitsBuffer, logitsTensor.numElements())
            if (predictedToken == assets.eosTokenId) {
                return@repeat
            }

            val piece = assets.tokenPieces.getOrNull(predictedToken).orEmpty()
            if (piece.isEmpty()) {
                return@repeat
            }
            decodeBuilder.append(piece)
            nextToken = predictedToken
            nextPosition += 1
        }

        val decodedText = decodeBuilder.toString().trim()
        Log.i(TAG, "SmolVLM raw output=$decodedText")
        return decodedText
    }

    private fun createPixelValuesBuffer(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        return ByteBuffer.allocateDirect(1 * 1 * 3 * IMAGE_SIZE * IMAGE_SIZE * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer ->
                for (channel in 0 until 3) {
                    for (y in 0 until IMAGE_SIZE) {
                        for (x in 0 until IMAGE_SIZE) {
                            val pixel = pixels[y * IMAGE_SIZE + x]
                            val value = when (channel) {
                                0 -> (pixel shr 16) and 0xFF
                                1 -> (pixel shr 8) and 0xFF
                                else -> pixel and 0xFF
                            }
                            val normalized = ((value / 255f) - IMAGE_MEAN) / IMAGE_STD
                            buffer.putFloat(normalized)
                        }
                    }
                }
                buffer.rewind()
            }
    }

    private fun createPaddedIntBuffer(
        tensor: Tensor,
        values: IntArray,
        batched: Boolean,
    ): ByteBuffer {
        val shape = tensor.shape()
        val capacity = if (batched) shape.last() else tensor.numElements()
        return ByteBuffer.allocateDirect(capacity * INT_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer ->
                repeat(capacity) { index ->
                    buffer.putInt(values.getOrElse(index) { 0 })
                }
                buffer.rewind()
            }
    }

    private fun createInputPositionsBuffer(tensor: Tensor, count: Int): ByteBuffer {
        val capacity = tensor.numElements()
        return ByteBuffer.allocateDirect(capacity * INT_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer ->
                repeat(capacity) { index ->
                    buffer.putInt(if (index < count) index else 0)
                }
                buffer.rewind()
            }
    }

    private fun createSingleTokenBuffer(token: Int): ByteBuffer =
        ByteBuffer.allocateDirect(INT_BYTES)
            .order(ByteOrder.nativeOrder())
            .putInt(token)
            .also { it.rewind() }

    private fun createSinglePositionBuffer(position: Int): ByteBuffer =
        ByteBuffer.allocateDirect(INT_BYTES)
            .order(ByteOrder.nativeOrder())
            .putInt(position)
            .also { it.rewind() }

    private fun createDecodeMaskBuffer(tensor: Tensor, position: Int): ByteBuffer {
        val shape = tensor.shape()
        val batch = shape.getOrElse(0) { 1 }
        val dim1 = shape.getOrElse(1) { 1 }
        val seqLen = shape.getOrElse(2) { 1 }
        val kvCacheSize = shape.getOrElse(3) { 1 }
        return ByteBuffer.allocateDirect(tensor.numBytes())
            .order(ByteOrder.nativeOrder())
            .also { buffer ->
                repeat(batch) {
                    repeat(dim1) {
                        repeat(seqLen) { seq ->
                            repeat(kvCacheSize) { kv ->
                                val allowed = kv < seq + position + 1
                                buffer.putFloat(if (allowed) 0f else Float.NEGATIVE_INFINITY)
                            }
                        }
                    }
                }
                buffer.rewind()
            }
    }

    private fun allocateBuffer(tensor: Tensor): ByteBuffer =
        ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder()).also { it.rewind() }

    private fun argMaxTokenId(buffer: ByteBuffer, elementCount: Int): Int {
        val floatBuffer = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        repeat(elementCount) { index ->
            val value = floatBuffer.get(index)
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun ByteBuffer.duplicateWithNativeOrder(): ByteBuffer =
        duplicate().order(ByteOrder.nativeOrder()).also { it.rewind() }

    private fun logModelLookup(stage: String) {
        Log.i(
            TAG,
            "stage=$stage path=${modelFile.absolutePath} exists=${modelFile.exists()} canRead=${modelFile.canRead()} length=${modelFile.length()}",
        )
    }

    private data class SmolVlmAssets(
        val eosTokenId: Int,
        val promptTokenIds: IntArray,
        val tokenPieces: List<String>,
    )

    companion object {
        private const val TAG = "SmolVlmFoodEngine"
        private const val DEFAULT_NUM_THREADS = 4
        private const val IMAGE_SIZE = 512
        private const val IMAGE_MEAN = 0.5f
        private const val IMAGE_STD = 0.5f
        private const val FLOAT_BYTES = 4
        private const val INT_BYTES = 4
        private const val MAX_DECODE_STEPS = 48
        private const val TOKENS_INPUT = "tokens"
        private const val INPUT_POS_INPUT = "input_pos"
        private const val PIXEL_VALUES_INPUT = "pixel_values"
        private const val MASK_INPUT = "mask"
        private const val LOGITS_OUTPUT = "logits"
        private const val DECODE_SIGNATURE = "decode"
        private const val KV_CACHE_PREFIX = "kv_cache_"
        private const val PROMPT_ASSET_NAME = "smolvlm_prompt_ids.json"
        private const val TOKEN_MAP_ASSET_NAME = "smolvlm_token_map.json"
    }
}

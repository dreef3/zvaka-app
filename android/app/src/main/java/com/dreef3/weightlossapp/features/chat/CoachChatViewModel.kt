package com.dreef3.weightlossapp.features.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.BuildConfig
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.chat.DietChatSnapshot
import com.dreef3.weightlossapp.chat.DietEntryContext
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.inference.FoodEstimationException
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

private data class ParsedCorrection(
    val lookupQuery: String?,
    val correctedDescription: String?,
    val correctedCalories: Int?,
    val reason: String,
)

data class CoachChatUiState(
    val messages: List<DietChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val showOverviewSuggestion: Boolean = true,
    val readOnly: Boolean = false,
    val attachedImagePath: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CoachChatViewModel(
    private val container: AppContainer,
    sessionId: Long? = null,
    private val readOnly: Boolean = false,
) : ViewModel() {
    private val today = container.localDateProvider.today()
    private val currentSessionId = MutableStateFlow<Long?>(sessionId)

    private val snapshotState: StateFlow<DietChatSnapshot> = combine(
        container.profileRepository.observeBudgetPeriods(),
        container.foodEntryRepository.observeAllEntries(),
    ) { periods, entries ->
        val today = container.localDateProvider.today()
        val budget = periods
            .filter { it.effectiveFromDate <= today }
            .maxByOrNull { it.effectiveFromDate }
            ?.caloriesPerDay
        val todayEntries = entries.filter { entry ->
            entry.entryDate == today &&
                entry.deletedAt == null &&
                entry.confirmationStatus != ConfirmationStatus.Rejected &&
                entry.entryStatus == FoodEntryStatus.Ready
        }
        DietChatSnapshot(
            todayBudgetCalories = budget,
            todayConsumedCalories = todayEntries.sumOf { it.finalCalories },
            todayRemainingCalories = budget?.minus(todayEntries.sumOf { it.finalCalories }),
            entries = entries
                .filter { it.deletedAt == null && it.confirmationStatus != ConfirmationStatus.Rejected }
                .sortedByDescending { it.capturedAt }
                .map { entry ->
                    DietEntryContext(
                        entryId = entry.id,
                        dateIso = entry.entryDate.toString(),
                        description = entry.detectedFoodLabel,
                        finalCalories = entry.finalCalories,
                        estimatedCalories = entry.estimatedCalories,
                        needsManual = entry.entryStatus == FoodEntryStatus.NeedsManual,
                        source = entry.source.name,
                    )
                },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DietChatSnapshot(
            todayBudgetCalories = null,
            todayConsumedCalories = 0,
            todayRemainingCalories = null,
            entries = emptyList(),
        ),
    )

    private val persistedMessages: StateFlow<List<DietChatMessage>> = currentSessionId
        .flatMapLatest { activeSessionId ->
            if (activeSessionId == null) flowOf(emptyList())
            else container.coachChatRepository.observeMessages(activeSessionId)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(CoachChatUiState(readOnly = readOnly))
    val uiState: StateFlow<CoachChatUiState> = _uiState

    init {
        if (sessionId == null) {
            viewModelScope.launch {
                container.coachChatRepository.observeSessionForDate(today).collect { session ->
                    currentSessionId.value = session?.id
                }
            }
        }
        viewModelScope.launch {
            persistedMessages.collect { messages ->
                _uiState.value = _uiState.value.copy(
                    messages = if (messages.isEmpty()) defaultMessages() else messages,
                    showOverviewSuggestion = !readOnly && messages.isEmpty() && !_uiState.value.isSending,
                )
            }
        }
    }

    fun updateInput(value: String) {
        if (readOnly) return
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun attachImage(uriString: String) {
        if (readOnly || _uiState.value.isSending) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val sourceUri = Uri.parse(uriString)
                val targetFile = container.photoStorage.createPhotoFile()
                container.appContext.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Unable to open selected image." }
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                container.photoStorage.normalizePhoto(targetFile.absolutePath)
                _uiState.value = _uiState.value.copy(attachedImagePath = targetFile.absolutePath)
            }.onFailure { throwable ->
                Log.e(TAG, "attachImage failed", throwable)
            }
        }
    }

    fun clearAttachment() {
        if (readOnly) return
        _uiState.value = _uiState.value.copy(attachedImagePath = null)
    }

    fun insertCorrectionExample() {
        if (readOnly) return
        debugLog("insertCorrectionExample")
        _uiState.value = _uiState.value.copy(
            input = "That pastry entry was actually potato burek, around 420 kcal.",
        )
    }

    fun send() {
        if (readOnly) return
        val text = _uiState.value.input.trim()
        val attachedImagePath = _uiState.value.attachedImagePath
        if ((text.isBlank() && attachedImagePath == null) || _uiState.value.isSending) return
        if (attachedImagePath != null) {
            sendAttachedPhoto(text, attachedImagePath)
            return
        }
        val directCorrection = parseCorrection(text)
        if (directCorrection != null) {
            debugLog(
                "send detected direct correction query=${directCorrection.lookupQuery} " +
                    "description=${directCorrection.correctedDescription} calories=${directCorrection.correctedCalories}",
            )
            applyDirectCorrection(text, directCorrection)
            return
        }
        debugLog("send normal message=${text.take(160)}")
        sendMessage(
            userVisibleText = text,
            actualPrompt = text,
            clearInput = true,
        )
    }

    fun requestOverview() {
        if (readOnly) return
        if (_uiState.value.isSending) return
        val snapshot = snapshotState.value
        val todayIso = container.localDateProvider.today().toString()
        val hasTodayEntries = snapshot.entries.any { it.dateIso == todayIso }
        debugLog("requestOverview hasTodayEntries=$hasTodayEntries entryCount=${snapshot.entries.size}")
        val actualPrompt = if (hasTodayEntries) {
            "Analyze today's tracked meals for weight loss and healthy eating habits. Start with a short overview of what was eaten, then give concise practical advice focused on calories, protein, satiety, meal balance, and one or two concrete next steps."
        } else {
            "Today's meals are empty. Analyze yesterday's tracked meals instead, if available, for weight loss and healthy eating habits. Start with a short overview of what was eaten, then give concise practical advice focused on calories, protein, satiety, meal balance, and one or two concrete next steps. If yesterday is also empty, say briefly that there is not enough recent meal data yet."
        }
        sendMessage(
            userVisibleText = "Give me overview for today",
            actualPrompt = actualPrompt,
            clearInput = false,
        )
    }

    private fun sendAttachedPhoto(
        text: String,
        imagePath: String,
    ) {
        _uiState.value = _uiState.value.copy(
            input = "",
            attachedImagePath = null,
            isSending = true,
            showOverviewSuggestion = false,
        )

        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = ensureWritableSessionId()
            val userVisibleText = text.ifBlank { "Attached a food photo for calorie estimate." }
            container.coachChatRepository.appendMessage(
                sessionId = sessionId,
                role = ChatRole.User,
                text = userVisibleText,
                createdAtEpochMs = System.currentTimeMillis(),
                imagePath = imagePath,
            )
            val capturedAt = Instant.now()
            val estimation = container.foodEstimationEngine.estimate(
                FoodEstimationRequest(
                    imagePath = imagePath,
                    capturedAtEpochMs = capturedAt.toEpochMilli(),
                    userContext = text.takeIf { it.isNotBlank() },
                    preferredDescription = text.takeIf { it.isNotBlank() },
                ),
            )
            val reply = estimation.fold(
                onSuccess = { result ->
                    if (result.confidenceState == ConfidenceState.High) {
                        val description = text.takeIf { it.isNotBlank() }
                            ?: result.detectedFoodLabel
                            ?: "Meal photo"
                        container.updateFoodEntryUseCase(
                            FoodEntry(
                                capturedAt = capturedAt,
                                entryDate = container.localDateProvider.dateFor(capturedAt),
                                imagePath = imagePath,
                                estimatedCalories = result.estimatedCalories,
                                finalCalories = result.estimatedCalories,
                                confidenceState = result.confidenceState,
                                detectedFoodLabel = description,
                                confidenceNotes = result.confidenceNotes,
                                confirmationStatus = ConfirmationStatus.NotRequired,
                                source = FoodEntrySource.AiEstimate,
                                entryStatus = FoodEntryStatus.Ready,
                                debugInteractionLog = result.debugInteractionLog,
                            ),
                        )
                        "Logged $description at ${result.estimatedCalories} kcal."
                    } else {
                        saveManualPhotoEntry(capturedAt, imagePath, text, result.debugInteractionLog)
                    }
                },
                onFailure = { throwable ->
                    saveManualPhotoEntry(
                        capturedAt = capturedAt,
                        imagePath = imagePath,
                        userText = text,
                        debugTrace = (throwable as? FoodEstimationException)?.debugInteractionLog,
                    )
                },
            )
            container.coachChatRepository.appendMessage(
                sessionId = sessionId,
                role = ChatRole.Assistant,
                text = reply,
                createdAtEpochMs = System.currentTimeMillis(),
                imagePath = null,
            )
            container.coachChatRepository.updateSessionSummary(sessionId, summarizeConversation(reply, userVisibleText))
            _uiState.value = _uiState.value.copy(
                isSending = false,
                showOverviewSuggestion = false,
            )
        }
    }

    private fun sendMessage(
        userVisibleText: String,
        actualPrompt: String,
        clearInput: Boolean,
    ) {
        _uiState.value = _uiState.value.copy(
            input = if (clearInput) "" else _uiState.value.input,
            isSending = true,
            showOverviewSuggestion = false,
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val sessionId = ensureWritableSessionId()
                val userMessageTime = System.currentTimeMillis()
                container.coachChatRepository.appendMessage(
                    sessionId = sessionId,
                    role = ChatRole.User,
                    text = userVisibleText,
                    createdAtEpochMs = userMessageTime,
                    imagePath = null,
                )
                val history = container.coachChatRepository.getMessages(sessionId)
                val response = container.dietChatEngine.sendMessage(
                    message = actualPrompt,
                    history = history,
                    snapshot = snapshotState.value,
                ).getOrElse {
                    Log.e(TAG, "dietChatEngine returned failure", it)
                    "I couldn't answer that yet. Try again in a moment."
                }
                val assistantTime = System.currentTimeMillis()
                container.coachChatRepository.appendMessage(
                    sessionId = sessionId,
                    role = ChatRole.Assistant,
                    text = response,
                    createdAtEpochMs = assistantTime,
                    imagePath = null,
                )
                container.coachChatRepository.updateSessionSummary(sessionId, summarizeConversation(response, history.lastOrNull()?.text))
                debugLog("assistant response chars=${response.length}")
            }.onFailure { throwable ->
                Log.e(TAG, "sendMessage flow failed", throwable)
                runCatching {
                    val sessionId = ensureWritableSessionId()
                    val fallback = "I hit an internal error while answering. Please try again."
                    container.coachChatRepository.appendMessage(
                        sessionId = sessionId,
                        role = ChatRole.Assistant,
                        text = fallback,
                        createdAtEpochMs = System.currentTimeMillis(),
                        imagePath = null,
                    )
                    container.coachChatRepository.updateSessionSummary(sessionId, summarizeConversation(fallback, userVisibleText))
                }
            }
            _uiState.value = _uiState.value.copy(
                isSending = false,
                showOverviewSuggestion = false,
            )
        }
    }

    private fun applyDirectCorrection(
        userText: String,
        correction: ParsedCorrection,
    ) {
        _uiState.value = _uiState.value.copy(
            input = "",
            isSending = true,
            showOverviewSuggestion = false,
        )

        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = ensureWritableSessionId()
            container.coachChatRepository.appendMessage(
                sessionId = sessionId,
                role = ChatRole.User,
                text = userText,
                createdAtEpochMs = System.currentTimeMillis(),
                imagePath = null,
            )
            val snapshot = snapshotState.value
            val candidates = resolveCorrectionCandidates(snapshot, correction.lookupQuery)
            debugLog("applyDirectCorrection candidates=${candidates.map { it.entryId to it.description }}")
            val reply = when {
                candidates.isEmpty() ->
                    "I couldn't find which saved meal to correct. Mention the meal name a bit more specifically."
                candidates.size > 1 ->
                    "I found more than one possible meal to correct. Mention the meal more specifically so I can update the right one."
                correction.correctedCalories == null && correction.correctedDescription.isNullOrBlank() ->
                    "Tell me the corrected calories, the corrected meal name, or both."
                else -> {
                    val target = candidates.single()
                    val result = container.dietEntryCorrectionService.applyCorrection(
                        com.dreef3.weightlossapp.chat.DietEntryCorrectionRequest(
                            entryId = target.entryId,
                            correctedCalories = correction.correctedCalories,
                            correctedDescription = correction.correctedDescription,
                            reason = correction.reason,
                        ),
                    )
                    debugLog("applyDirectCorrection result=$result")
                    if (result["success"] == true) {
                        val description = result["description"]?.toString().orEmpty()
                        val calories = result["finalCalories"]?.toString().orEmpty()
                        "Updated that entry to $description at $calories kcal."
                    } else {
                        result["message"]?.toString() ?: "I couldn't update that meal entry."
                    }
                }
            }
            container.coachChatRepository.appendMessage(
                sessionId = sessionId,
                role = ChatRole.Assistant,
                text = reply,
                createdAtEpochMs = System.currentTimeMillis(),
                imagePath = null,
            )
            container.coachChatRepository.updateSessionSummary(sessionId, summarizeConversation(reply, userText))
            _uiState.value = _uiState.value.copy(
                isSending = false,
                showOverviewSuggestion = false,
            )
        }
    }

    private fun resolveCorrectionCandidates(
        snapshot: DietChatSnapshot,
        lookupQuery: String?,
    ): List<DietEntryContext> {
        val entries = snapshot.entries
        if (entries.isEmpty()) return emptyList()
        val normalizedQuery = lookupQuery?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (normalizedQuery == null) {
            return entries.take(1)
        }
        val directMatches = entries.filter { entry ->
            (entry.description ?: "").lowercase().contains(normalizedQuery)
        }
        return if (directMatches.isNotEmpty()) directMatches else entries.filter { entry ->
            normalizedQuery.split(" ").all { token ->
                token.isBlank() || (entry.description ?: "").lowercase().contains(token)
            }
        }
    }

    private fun parseCorrection(text: String): ParsedCorrection? {
        val calories = Regex("""(?i)\b(?:around|about)?\s*(\d{2,5})\s*k?cal\b""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        val entryActuallyPattern = Regex(
            pattern = """(?i)^(?:that|the)?\s*(.+?)\s+entry\s+was\s+(?:actually|in fact)\s+(.+?)(?:[,.]|$)""",
        )
        val simpleActuallyPattern = Regex(
            pattern = """(?i)^(?:that|the)?\s*(.+?)\s+was\s+(?:actually|in fact)\s+(.+?)(?:[,.]|$)""",
        )
        val updateToPattern = Regex(
            pattern = """(?i)^(?:please\s+)?(?:update|change|rename|set)\s+(?:the\s+)?(.+?)(?:\s+entry)?\s+to\s+(.+?)(?:[,.]|$)""",
        )
        val descriptionToPattern = Regex(
            pattern = """(?i)^(?:please\s+)?(?:update|change|set)\s+(?:the\s+)?description\s+(?:for|of)\s+(.+?)\s+to\s+(.+?)(?:[,.]|$)""",
        )
        val entryIsPattern = Regex(
            pattern = """(?i)^(?:the\s+)?(.+?)\s+entry\s+(?:is|should be|should've been)\s+(.+?)(?:[,.]|$)""",
        )

        entryActuallyPattern.find(text)?.let { match ->
            return ParsedCorrection(
                lookupQuery = match.groupValues[1].trim(),
                correctedDescription = cleanDescription(match.groupValues[2]),
                correctedCalories = calories,
                reason = text,
            )
        }
        simpleActuallyPattern.find(text)?.let { match ->
            return ParsedCorrection(
                lookupQuery = match.groupValues[1].trim(),
                correctedDescription = cleanDescription(match.groupValues[2]),
                correctedCalories = calories,
                reason = text,
            )
        }
        descriptionToPattern.find(text)?.let { match ->
            return ParsedCorrection(
                lookupQuery = match.groupValues[1].trim(),
                correctedDescription = cleanDescription(match.groupValues[2]),
                correctedCalories = calories,
                reason = text,
            )
        }
        updateToPattern.find(text)?.let { match ->
            return ParsedCorrection(
                lookupQuery = match.groupValues[1].trim(),
                correctedDescription = cleanDescription(match.groupValues[2]),
                correctedCalories = calories,
                reason = text,
            )
        }
        entryIsPattern.find(text)?.let { match ->
            return ParsedCorrection(
                lookupQuery = match.groupValues[1].trim(),
                correctedDescription = cleanDescription(match.groupValues[2]),
                correctedCalories = calories,
                reason = text,
            )
        }

        if (calories != null) {
            val namedEntryPattern = Regex("""(?i)^(?:that|the)?\s*(.+?)\s+(?:was|is|should be|should've been).*$""")
            namedEntryPattern.find(text)?.let { match ->
                val query = match.groupValues[1]
                    .replace(Regex("""\bentry\b""", RegexOption.IGNORE_CASE), "")
                    .trim()
                return ParsedCorrection(
                    lookupQuery = query.takeIf { it.isNotBlank() },
                    correctedDescription = null,
                    correctedCalories = calories,
                    reason = text,
                )
            }
        }

        return null
    }

    private fun cleanDescription(raw: String): String? {
        val stripped = raw
            .replace(Regex("""(?i),?\s*(around|about)\s*\d{2,5}\s*k?cal.*$"""), "")
            .replace(Regex("""(?i),?\s*\d{2,5}\s*k?cal.*$"""), "")
            .trim()
            .trimEnd('.', ',')
        return stripped.ifBlank { null }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    private suspend fun ensureWritableSessionId(): Long {
        currentSessionId.value?.let { return it }
        val created = container.coachChatRepository.ensureSessionForDate(today)
        currentSessionId.value = created
        return created
    }

    private fun summarizeConversation(assistantText: String, userText: String?): String {
        val assistantLine = assistantText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val userLine = userText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return assistantLine?.take(120)
            ?: userLine?.take(120)
            ?: "Coach conversation"
    }

    private fun defaultMessages(): List<DietChatMessage> = listOf(
        DietChatMessage(
            role = ChatRole.Assistant,
            text = if (readOnly) {
                "No saved messages in this coach conversation."
            } else {
                "Ask about your meals, calories, or how to improve today's diet."
            },
        ),
    )

    companion object {
        private const val TAG = "CoachChat"
    }

    private suspend fun saveManualPhotoEntry(
        capturedAt: Instant,
        imagePath: String,
        userText: String,
        debugTrace: String?,
    ): String {
        container.updateFoodEntryUseCase(
            FoodEntry(
                capturedAt = capturedAt,
                entryDate = container.localDateProvider.dateFor(capturedAt),
                imagePath = imagePath,
                estimatedCalories = 0,
                finalCalories = 0,
                confidenceState = ConfidenceState.Failed,
                detectedFoodLabel = userText.takeIf { it.isNotBlank() },
                confidenceNotes = "Could not estimate calories automatically. Enter them manually.",
                confirmationStatus = ConfirmationStatus.NotRequired,
                source = FoodEntrySource.AiEstimate,
                entryStatus = FoodEntryStatus.NeedsManual,
                debugInteractionLog = debugTrace,
            ),
        )
        return "I saved the photo, but automatic estimation wasn't reliable enough. Open Today and enter calories manually."
    }
}

class CoachChatViewModelFactory(
    private val container: AppContainer,
    private val sessionId: Long? = null,
    private val readOnly: Boolean = false,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CoachChatViewModel(container, sessionId, readOnly) as T
    }
}

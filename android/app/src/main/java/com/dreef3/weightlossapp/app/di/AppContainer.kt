package com.dreef3.weightlossapp.app.di

import android.content.Context
import com.dreef3.weightlossapp.app.health.HealthConnectCaloriesExporter
import com.dreef3.weightlossapp.app.health.HealthConnectBackfillService
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.chat.DietChatEngine
import com.dreef3.weightlossapp.chat.DietEntryCorrectionService
import com.dreef3.weightlossapp.chat.DietEntryInspectionService
import com.dreef3.weightlossapp.chat.LiteRtDietChatEngine
import com.dreef3.weightlossapp.chat.SelectableDietChatEngine
import com.dreef3.weightlossapp.app.media.ModelDownloadRepository
import com.dreef3.weightlossapp.app.media.ModelDownloader
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.media.PhotoStorage
import com.dreef3.weightlossapp.app.network.NetworkConnectionMonitor
import com.dreef3.weightlossapp.app.training.ModelImprovementUploader
import com.dreef3.weightlossapp.app.training.ModelImprovementUploadScheduler
import com.dreef3.weightlossapp.app.sync.AppDataBackupManager
import com.dreef3.weightlossapp.app.sync.DriveSyncScheduler
import com.dreef3.weightlossapp.app.sync.GoogleDriveSyncManager
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.data.repository.CoachChatRepositoryImpl
import com.dreef3.weightlossapp.data.repository.FoodEntryRepositoryImpl
import com.dreef3.weightlossapp.data.repository.ProfileRepositoryImpl
import com.dreef3.weightlossapp.domain.calculation.CalorieBudgetCalculator
import com.dreef3.weightlossapp.domain.calculation.SummaryAggregator
import com.dreef3.weightlossapp.domain.calculation.TrendAggregator
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.ConfirmFoodEstimateUseCase
import com.dreef3.weightlossapp.domain.usecase.SaveManualCaloriesUseCase
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.LiteRtFoodEstimationEngine
import com.dreef3.weightlossapp.inference.SelectableFoodEstimationEngine
import com.dreef3.weightlossapp.work.WorkManagerPhotoProcessingScheduler

class AppContainer private constructor(context: Context) {
    val appContext: Context = context
    val database = AppDatabase.build(context)
    val driveSyncScheduler = DriveSyncScheduler(context)
    val preferences = AppPreferences(context, driveSyncScheduler)
    val healthConnectCaloriesExporter = HealthConnectCaloriesExporter(context)
    val localDateProvider = LocalDateProvider()
    val photoStorage = PhotoStorage(context)
    val modelImprovementUploadScheduler = ModelImprovementUploadScheduler(context)
    val foodEntryRepository: FoodEntryRepository = FoodEntryRepositoryImpl(
        foodEntryDao = database.foodEntryDao(),
        driveSyncTrigger = driveSyncScheduler,
        preferences = preferences,
        healthConnectCaloriesExporter = healthConnectCaloriesExporter,
    )
    val healthConnectBackfillService = HealthConnectBackfillService(
        foodEntryRepository = foodEntryRepository,
        exporter = healthConnectCaloriesExporter,
        localDateProvider = localDateProvider,
    )
    val modelImprovementUploader = ModelImprovementUploader(context, preferences, foodEntryRepository)
    val modelStorage = ModelStorage(context)
    val modelDownloader = ModelDownloader(modelStorage)
    val modelDownloadRepository = ModelDownloadRepository(context, modelStorage)
    val appDataBackupManager = AppDataBackupManager(context, database, preferences)
    val googleDriveSyncManager = GoogleDriveSyncManager(context, preferences, appDataBackupManager)
    val networkConnectionMonitor = NetworkConnectionMonitor(context)
    val photoProcessingScheduler = WorkManagerPhotoProcessingScheduler(context)
    val budgetCalculator = CalorieBudgetCalculator()
    val summaryAggregator = SummaryAggregator()
    val trendAggregator = TrendAggregator()

    val profileRepository: ProfileRepository = ProfileRepositoryImpl(
        profileDao = database.profileDao(),
        budgetDao = database.dailyCalorieBudgetPeriodDao(),
        driveSyncTrigger = driveSyncScheduler,
    )

    val coachChatRepository: CoachChatRepository = CoachChatRepositoryImpl(
        sessionDao = database.coachChatSessionDao(),
        messageDao = database.coachChatMessageDao(),
        driveSyncTrigger = driveSyncScheduler,
    )

    val saveUserProfileUseCase = SaveUserProfileUseCase(
        profileRepository = profileRepository,
        calorieBudgetCalculator = budgetCalculator,
        localDateProvider = localDateProvider,
    )

    val confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase()
    val updateFoodEntryUseCase = UpdateFoodEntryUseCase(foodEntryRepository)
    val backgroundPhotoCaptureUseCase = BackgroundPhotoCaptureUseCase(
        repository = foodEntryRepository,
        scheduler = photoProcessingScheduler,
        localDateProvider = localDateProvider,
        photoStorage = photoStorage,
    )
    val saveManualCaloriesUseCase = SaveManualCaloriesUseCase(foodEntryRepository)
    val dietEntryCorrectionService = DietEntryCorrectionService(
        foodEntryRepository = foodEntryRepository,
        updateFoodEntryUseCase = updateFoodEntryUseCase,
        localDateProvider = localDateProvider,
    )
    val gemmaFoodEstimationEngine: FoodEstimationEngine = LiteRtFoodEstimationEngine(
        modelFile = modelStorage.defaultModelFile,
    )
    val foodEstimationEngine: FoodEstimationEngine = SelectableFoodEstimationEngine(
        preferences = preferences,
        gemmaEngine = gemmaFoodEstimationEngine,
    )
    val dietEntryInspectionService = DietEntryInspectionService(
        foodEntryRepository = foodEntryRepository,
        foodEstimationEngine = foodEstimationEngine,
    )
    val gemmaDietChatEngine: DietChatEngine = LiteRtDietChatEngine(
        modelFile = modelStorage.defaultModelFile,
        correctionService = dietEntryCorrectionService,
        inspectionService = dietEntryInspectionService,
        backendPreferenceProvider = preferences::readGemmaBackend,
    )
    val dietChatEngine: DietChatEngine = SelectableDietChatEngine(
        preferences = preferences,
        gemmaEngine = gemmaDietChatEngine,
    )

    companion object {
        @Volatile
        private var _instance: AppContainer? = null

        val instance: AppContainer
            get() = requireNotNull(_instance) { "AppContainer has not been initialized" }

        fun initialize(context: Context) {
            if (_instance == null) {
                _instance = AppContainer(context.applicationContext)
            }
        }
    }
}

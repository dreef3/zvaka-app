package com.dreef3.weightlossapp.app.di

import android.content.Context
import com.dreef3.weightlossapp.app.media.ModelDownloader
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.media.PhotoStorage
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.data.repository.FoodEntryRepositoryImpl
import com.dreef3.weightlossapp.data.repository.ProfileRepositoryImpl
import com.dreef3.weightlossapp.domain.calculation.CalorieBudgetCalculator
import com.dreef3.weightlossapp.domain.calculation.SummaryAggregator
import com.dreef3.weightlossapp.domain.calculation.TrendAggregator
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.ConfirmFoodEstimateUseCase
import com.dreef3.weightlossapp.domain.usecase.SaveManualCaloriesUseCase
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.LiteRtFoodEstimationEngine
import com.dreef3.weightlossapp.work.WorkManagerPhotoProcessingScheduler
class AppContainer private constructor(context: Context) {
    private val database = AppDatabase.build(context)
    val preferences = AppPreferences(context)
    val localDateProvider = LocalDateProvider()
    val photoStorage = PhotoStorage(context)
    val modelStorage = ModelStorage(context)
    val modelDownloader = ModelDownloader(modelStorage)
    val photoProcessingScheduler = WorkManagerPhotoProcessingScheduler(context)
    val budgetCalculator = CalorieBudgetCalculator()
    val summaryAggregator = SummaryAggregator()
    val trendAggregator = TrendAggregator()

    val profileRepository: ProfileRepository = ProfileRepositoryImpl(
        profileDao = database.profileDao(),
        budgetDao = database.dailyCalorieBudgetPeriodDao(),
    )

    val foodEntryRepository: FoodEntryRepository = FoodEntryRepositoryImpl(
        foodEntryDao = database.foodEntryDao(),
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
    )
    val saveManualCaloriesUseCase = SaveManualCaloriesUseCase(foodEntryRepository)

    val foodEstimationEngine: FoodEstimationEngine = LiteRtFoodEstimationEngine(
        modelFile = modelStorage.defaultModelFile,
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

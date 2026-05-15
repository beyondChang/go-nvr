package com.beyond.nvr.di

import com.beyond.nvr.data.api.AuthInterceptor
import com.beyond.nvr.data.api.GoNvrApi
import com.beyond.nvr.data.repository.*
import com.beyond.nvr.ui.cameras.CamerasViewModel
import com.beyond.nvr.ui.dashboard.DashboardViewModel
import com.beyond.nvr.ui.login.LoginViewModel
import com.beyond.nvr.ui.recordings.RecordingsViewModel
import com.beyond.nvr.ui.recordings.RecordingDetailViewModel
import com.beyond.nvr.ui.settings.SettingsViewModel
import com.beyond.nvr.ui.stats.StatsViewModel
import com.beyond.nvr.ui.liveview.LiveViewViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

val appModule = module {
    // Preferences
    single { PreferencesRepository(androidContext()) }

    // JSON
    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }

    // OkHttp
    single {
        val authInterceptor = AuthInterceptor()
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Retrofit
    single<GoNvrApi> {
        val baseUrl = runBlocking {
            get<PreferencesRepository>().getServerUrl()
        }
        val okHttpClient: OkHttpClient = get()
        val json: Json = get()

        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val apiUrl = "${url}api/"
        val contentType = "application/json".toMediaType()

        Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GoNvrApi::class.java)
    }

    // Repositories
    single { AuthRepository(get(), get()) }
    single { CameraRepository(get()) }
    single { RecordingRepository(get()) }
    single { SettingsRepository(get()) }
    single { StatsRepository(get()) }

    // ViewModels
    viewModel { LoginViewModel(get()) }
    viewModel { DashboardViewModel(get(), get()) }
    viewModel { CamerasViewModel(get()) }
    viewModel { LiveViewViewModel(get()) }
    viewModel { RecordingsViewModel(get(), get()) }
    viewModel { RecordingDetailViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { StatsViewModel(get(), get()) }
}

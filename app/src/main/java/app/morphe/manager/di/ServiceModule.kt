package app.morphe.manager.di

import app.morphe.manager.domain.update.VerifiedYouTubeSourceDownloader
import app.morphe.manager.network.service.HttpService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val serviceModule = module {
    singleOf(::HttpService)
    singleOf(::VerifiedYouTubeSourceDownloader)
}

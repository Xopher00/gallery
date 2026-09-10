// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.content.Context
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HfCardDescriptionModule {

  @Provides
  @Singleton
  fun provideHfCardDescriptionStore(@ApplicationContext context: Context): HfCardDescriptionStore =
    HfCardDescriptionStore(getModelStorageDir(context))

  @Provides
  @Singleton
  fun provideHfCardDescriptions(
    store: HfCardDescriptionStore,
    dataStoreRepository: DataStoreRepository,
  ): HfCardDescriptions =
    HfCardDescriptions(store) { dataStoreRepository.readAccessTokenData()?.accessToken }
}

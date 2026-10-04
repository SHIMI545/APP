package com.nagen.player.di
import android.content.Context
import androidx.room.Room
import com.nagen.player.data.NagenDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun db(@ApplicationContext context:Context):NagenDatabase =
        Room.databaseBuilder(context,NagenDatabase::class.java,"nagen.db").build()
    @Provides fun tracks(db:NagenDatabase)=db.tracks()
    @Provides fun playlists(db:NagenDatabase)=db.playlists()
    @Provides fun queues(db:NagenDatabase)=db.queues()
    @Provides fun settings(db:NagenDatabase)=db.settings()
}
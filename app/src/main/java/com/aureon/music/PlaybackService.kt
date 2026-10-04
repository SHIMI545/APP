package com.aureon.music
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
class PlaybackService : MediaSessionService() {
 private var session: MediaSession? = null
 private lateinit var player: ExoPlayer
 override fun onCreate(){ super.onCreate(); player=ExoPlayer.Builder(this).build(); session=MediaSession.Builder(this,player).build() }
 override fun onGetSession(controllerInfo: MediaSession.ControllerInfo)=session
 override fun onDestroy(){ session?.release(); player.release(); super.onDestroy() }
}
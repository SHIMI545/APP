package com.aureon.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentUris
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Track(val uri:String,val title:String,val artist:String,val album:String)

class MainActivity:ComponentActivity(){
 private var tracks by mutableStateOf(listOf<Track>())
 private var controller:MediaController?=null
 private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->loadUris(uris?:emptyList())}
 private val folderPicker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)loadFolder(uri)}
 override fun onCreate(b:Bundle?){super.onCreate(b); requestAudioPermission()
  val future=MediaController.Builder(this,SessionToken(this,android.content.ComponentName(this,PlaybackService::class.java))).buildAsync()
  future.addListener({controller=future.get()},MoreExecutors.directExecutor())
  setContent{AureonApp(tracks,{picker.launch(arrayOf("audio/*"))},{folderPicker.launch(null)},{play(it)})}
 }
 private fun requestAudioPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO),10) else scan()}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==10&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)scan()}
 private fun scan(){lifecycleScope.launch(Dispatchers.IO){val out=mutableListOf<Track>();val pr=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM);contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,pr,"${MediaStore.Audio.Media.IS_MUSIC}=1",null,"${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use{c->val id=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);val t=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);val a=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);val al=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);while(c.moveToNext())out.add(Track(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,c.getLong(id)).toString(),c.getString(t)?:"Unknown",c.getString(a)?:"Unknown artist",c.getString(al)?:"Unknown album"))};withContext(Dispatchers.Main){tracks=out}}}
 private fun loadUris(us:List<Uri>){tracks=tracks+us.map{Track(it.toString(),DocumentFile.fromSingleUri(this,it)?.name?.substringBeforeLast(".")?:"Imported song","Unknown artist","Imported")}}
 private fun loadFolder(u:Uri){lifecycleScope.launch(Dispatchers.IO){val f=DocumentFile.fromTreeUri(this@MainActivity,u)?.listFiles()?.filter{it.isFile&&(it.type?.startsWith("audio/")==true||it.name?.endsWith(".flac",true)==true)}?:emptyList();withContext(Dispatchers.Main){tracks=tracks+f.map{Track(it.uri.toString(),it.name?.substringBeforeLast(".")?:"Song","Unknown artist","Imported folder")}}}}
 private fun play(t:Track){controller?.setMediaItem(MediaItem.Builder().setUri(t.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()).build());controller?.prepare();controller?.play()}
 override fun onDestroy(){controller?.release();super.onDestroy()}
}

@Composable fun AureonApp(tracks:List<Track>,import:()->Unit,folder:()->Unit,play:(Track)->Unit){
 var q by remember{mutableStateOf("")};var tab by remember{mutableIntStateOf(0)};var fav by remember{mutableStateOf(setOf<String>())}
 val list=tracks.filter{it.title.contains(q,true)||it.artist.contains(q,true)||it.album.contains(q,true)}
 MaterialTheme(colorScheme=darkColorScheme(background=Color(0xFF090A0F),surface=Color(0xFF11131A),primary=Color(0xFFD6B36A),onSurface=Color(0xFFF4F1E8))){
 Scaffold(containerColor=Color(0xFF090A0F),bottomBar={NavigationBar(containerColor=Color(0xFF0D0F15)){listOf("Library","Favorites","Playlists").forEachIndexed{i,s->NavigationBarItem(tab==i,{tab=i},{Icon(if(i==0)Icons.Default.LibraryMusic else if(i==1)Icons.Default.Favorite else Icons.Default.QueueMusic,s)},label={Text(s)})}}}){pad->
 Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=18.dp)){
  Spacer(Modifier.height(20.dp));Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("AUREON",letterSpacing=4.sp,fontSize=12.sp,color=Color(0xFFD6B36A),fontWeight=FontWeight.Bold);Text("Your music.",fontSize=28.sp,fontWeight=FontWeight.SemiBold)}IconButton(onClick=import){Icon(Icons.Default.AddCircleOutline,"Import")}}
  Spacer(Modifier.height(16.dp));OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp),placeholder={Text("Search songs, artists, albums")},leadingIcon={Icon(Icons.Default.Search,null)},colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Color(0xFFD6B36A))})
  Spacer(Modifier.height(10.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){AssistChip(onClick=folder,label={Text("Import folder")},leadingIcon={Icon(Icons.Default.FolderOpen,null)});AssistChip(onClick=import,label={Text("Import files")},leadingIcon={Icon(Icons.Default.FileOpen,null)})}
  Spacer(Modifier.height(14.dp))
  if(tab==0){Text("${list.size} songs",color=Color.Gray,fontSize=13.sp);LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(vertical=8.dp)){items(list,key={it.uri}){t->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF11131A)).clickable{play(t)}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF34313A),Color(0xFF171A22)))),contentAlignment=Alignment.Center){Icon(Icons.Default.MusicNote,null,tint=Color(0xFFD6B36A))};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(t.title,maxLines=1,fontWeight=FontWeight.Medium);Text("${t.artist} • ${t.album}",maxLines=1,fontSize=12.sp,color=Color.Gray)};IconButton(onClick={fav=if(t.uri in fav)fav-t.uri else fav+t.uri}){Icon(if(t.uri in fav)Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=if(t.uri in fav)Color(0xFFD6B36A)else Color.Gray)}}}}}
  else if(tab==1){Text("Favorites",fontSize=22.sp,fontWeight=FontWeight.SemiBold);LazyColumn{items(list.filter{it.uri in fav}){t->Text(t.title,Modifier.fillMaxWidth().clickable{play(t)}.padding(16.dp))}}}
  else {Text("Playlists",fontSize=22.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(8.dp));Text("Your playlists will appear here.",color=Color.Gray)}
 }
 }}
}
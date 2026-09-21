package com.leyu.melora.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import com.leyu.melora.MeloraApplication
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.UserLibrary
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 可复现的离线目录，不导入音源、不解析或播放音频，不接触正式版数据。 */
class LibraryFixtureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "seed")
        val context = requireNotNull(context)
        runBlocking { (context.applicationContext as MeloraApplication).awaitStartup() }
        val covers = List(24) { index ->
            File(context.filesDir, "benchmark-cover-$index.png").also { file ->
                if (!file.exists()) {
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.HSVToColor(floatArrayOf(index * 15f, 0.45f, 0.8f)))
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }.toURI().toString()
        }
        val songs = List(300) { index ->
            JSONObject().put("source", "local").put("songmid", "benchmark-$index")
                .put("name", "测试歌曲 ${index + 1}").put("singer", "测试歌手 ${index % 24 + 1}")
                .put("albumName", "测试专辑 ${index % 24 + 1}").put("img", covers[index % covers.size])
                .put("interval", "03:30")
        }
        val lists = List(12) { index ->
            JSONObject().put("id", "benchmark-$index").put("name", "测试歌单 ${index + 1}")
                .put("source", "local").put("author", "离线性能测试").put("img", covers[index])
        }
        UserLibrary.replaceFromBackup(
            JSONObject().put("favorites", JSONArray(songs)).put("recents", JSONArray(songs.take(100)))
                .put("favoritePlaylists", JSONArray(lists)).toString(),
        )
        MeloraSettings.updateAutoPlay(false)
        MeloraSettings.updateShowSongCovers(true)
        MeloraSettings.updateBlurTopBar(arg != "solid")
        return Bundle().apply { putInt("songs", songs.size) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

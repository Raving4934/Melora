package com.leyu.melora.playback.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import java.util.Arrays;

/** 仅由 ContentResolver.wrap 使用；不注册、不读写系统媒体库。 */
public final class MediaStoreScanProvider extends ContentProvider {
    private final ContentProvider documents;
    private long[] ids = new long[0];
    private String folder = "";
    public Runnable onQuery;
    public long shortTrackId = -1;
    public long smallTrackId = -1;
    public long nonMusicTrackId = -1;

    public MediaStoreScanProvider(ContentProvider documents) { this.documents = documents; }

    public void setSongs(long[] ids, String folder) { this.ids = ids; this.folder = folder; }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal cancellation) {
        return "media".equals(uri.getAuthority()) ? super.query(uri, projection, args, cancellation)
            : documents.query(uri, projection, args, cancellation);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (onQuery != null && projection != null && Arrays.asList(projection).contains("_id")) {
            Runnable callback = onQuery;
            onQuery = null;
            callback.run();
        }
        MatrixCursor cursor = new MatrixCursor(projection);
        for (long id : ids) {
            if ("is_music != 0".equals(selection) && id == nonMusicTrackId) continue;
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : projection) {
                Object value;
                switch (column) {
                    case "_id": value = id; break;
                    case "title": value = "scanned-" + id; break;
                    case "artist": value = "scanned-artist"; break;
                    case "album": value = "scanned-album"; break;
                    case "duration": value = id == shortTrackId ? 30_000L : 180_000L; break;
                    case "_size": value = id == smallTrackId ? 500_000L : 3_000_000L; break;
                    case "mime_type": value = "audio/mpeg"; break;
                    case "date_modified": value = 1L; break;
                    case "date_added": value = 1L; break;
                    case "year": value = 2024; break;
                    case "_data": value = folder + "/song-" + id + ".mp3"; break;
                    case "is_music": value = id == nonMusicTrackId ? 0 : 1; break;
                    default: value = null;
                }
                row.add(value);
            }
        }
        return cursor;
    }
    @Override public String getType(Uri uri) { return "media".equals(uri.getAuthority()) ? "audio/mpeg" : documents.getType(uri); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

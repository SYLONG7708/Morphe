package com.sylong.bluem.update;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the package installer read access to one verified, private APK only. */
public final class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"app.morphe.android.youtube.bluem.updates".equals(uri.getAuthority()) ||
            uri.getPathSegments().size() != 1 || name == null || !name.matches("[0-9a-f]{64}\\.apk"))
            throw new FileNotFoundException("Invalid update path");
        SharedPreferences prefs = getContext().getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
        if (!name.equals(prefs.getString("ready_file", ""))) throw new FileNotFoundException("APK not approved");
        File result = new File(new File(getContext().getFilesDir(), "bluem-updates"), name);
        if (!result.isFile()) throw new FileNotFoundException("APK missing");
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] requested = columns == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : columns;
            MatrixCursor cursor = new MatrixCursor(requested, 1);
            Object[] row = new Object[requested.length];
            for (int i = 0; i < requested.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(requested[i])) row[i] = "BlueM-update.apk";
                if (OpenableColumns.SIZE.equals(requested[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

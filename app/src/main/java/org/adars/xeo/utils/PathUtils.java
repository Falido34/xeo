package org.adars.xeo.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Resolves Android URIs to paths that the native Xenia layer can open.
 *
 * <p>For content:// URIs, prefer a /proc/self/fd path. This avoids relying on
 * deprecated filesystem paths that are commonly inaccessible under Android's
 * scoped-storage rules.</p>
 */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Resolves a URI to a path the native layer can open.
     * Returns null if the URI cannot be resolved.
     */
    @Nullable
    public static String uriToNativePath(@NonNull final Context context,
                                         @NonNull final Uri uri) {
        final String scheme = uri.getScheme();

        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            return uri.getPath();
        }

        if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            // SAF and cloud providers may not expose a real filesystem path.
            // Keep the descriptor open after detaching it: native code opens
            // /proc/self/fd/<N> after the emulator activity starts.
            final String fdPath = openAsFdPath(context, uri);
            if (fdPath != null) return fdPath;

            // Compatibility fallback for providers that expose MediaStore's
            // legacy DATA column but do not support openFileDescriptor().
            return queryDataColumn(context, uri);
        }

        return null;
    }

    @Nullable
    private static String queryDataColumn(@NonNull final Context context,
                                          @NonNull final Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final String path = cursor.getString(0);
                if (path != null && !path.isEmpty()) return path;
            }
        } catch (final Exception ignored) {
            // Most SAF providers intentionally do not expose filesystem paths.
        }
        return null;
    }

    @Nullable
    private static String openAsFdPath(@NonNull final Context context,
                                       @NonNull final Uri uri) {
        try {
            final android.os.ParcelFileDescriptor pfd =
                    context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;

            // detachFd() transfers ownership to this process. Do not close the
            // descriptor here: Xenia needs it to remain valid while opening
            // the /proc/self/fd path.
            final int fd = pfd.detachFd();
            return "/proc/self/fd/" + fd;
        } catch (final Exception ignored) {
            return null;
        }
    }
}

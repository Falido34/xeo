package org.adars.xeo.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans a SAF document-tree URI for recognised Xbox 360 disc/executable images
 * and returns a list of {@link GameEntry} objects for the UI.
 *
 * Supported extensions: .iso, .xex, .zar, .stfs (XBLA packages)
 */
public final class GameLibraryManager {

    private static final String PREF_LIBRARY_ROOT = "library_root_uri";

    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(
            Arrays.asList(".iso", ".xex", ".zar", ".stfs"));

    private final Context mContext;
    private final SharedPreferences mPrefs;

    public GameLibraryManager(@NonNull final Context context) {
        mContext = context.getApplicationContext();
        mPrefs   = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public void setLibraryRoot(@NonNull final String uriString) {
        mPrefs.edit().putString(PREF_LIBRARY_ROOT, uriString).apply();
    }

    @NonNull
    public String getLibraryRoot() {
        return mPrefs.getString(PREF_LIBRARY_ROOT, "");
    }

    /**
     * Synchronously scans the configured library root and returns all found
     * game entries. Call on a background thread for large libraries.
     */
    @NonNull
    public List<GameEntry> scanLibrary() {
        final List<GameEntry> results = new ArrayList<>();
        final String rootUriString = getLibraryRoot();
        if (!rootUriString.isEmpty()) {
            try {
                final Uri treeUri = Uri.parse(rootUriString);
                final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                scanDirectory(childrenUri, treeUri, results);
            } catch (final Exception e) {
                // URI may have been revoked — continue scanning app-imported games.
            }
        }

        // Each imported folder represents one game. Only index its main
        // default.xex; nested system executables are game dependencies, not games.
        final File importedRoot = mContext.getExternalFilesDir("games");
        if (importedRoot != null && importedRoot.isDirectory()) {
            final File[] importedGames = importedRoot.listFiles();
            if (importedGames != null) {
                for (final File gameFolder : importedGames) {
                    if (!gameFolder.isDirectory()) continue;
                    final File defaultXex = findDefaultXex(gameFolder);
                    if (defaultXex != null && isValidLocalXex(defaultXex)) {
                        final String uriString = Uri.fromFile(defaultXex).toString();
                        boolean duplicate = false;
                        for (final GameEntry existing : results) {
                            if (existing.getUri().equals(uriString)) {
                                duplicate = true;
                                break;
                            }
                        }
                        if (!duplicate) {
                            results.add(new GameEntry(uriString, gameFolder.getName(),
                                    defaultXex.getAbsolutePath(), null));
                        }
                    }
                }
            }
        }

        return results;
    }

    @Nullable
    private File findDefaultXex(@NonNull final File directory) {
        final File[] children = directory.listFiles();
        if (children == null) return null;
        // Prefer a default.xex in this directory before searching subfolders.
        for (final File child : children) {
            if (child.isFile() && "default.xex".equalsIgnoreCase(child.getName())) {
                return child;
            }
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                final File found = findDefaultXex(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isValidLocalXex(@NonNull final File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            final byte[] magic = new byte[4];
            if (input.read(magic) != 4) return false;
            final String signature = new String(magic, StandardCharsets.US_ASCII);
            return "XEX1".equals(signature) || "XEX2".equals(signature);
        } catch (final Exception ignored) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void scanDirectory(@NonNull final Uri childrenUri,
                                @NonNull final Uri treeUri,
                                @NonNull final List<GameEntry> out) {
        final String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };

        try (Cursor cursor = mContext.getContentResolver()
                .query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                final String docId   = cursor.getString(0);
                final String name    = cursor.getString(1);
                final String mime    = cursor.getString(2);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // Recurse one level deep.
                    final Uri subChildren =
                            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
                    scanDirectory(subChildren, treeUri, out);
                } else if (hasRecognisedExtension(name)) {
                    final Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, docId);
                    if (isValidXbox360Game(fileUri, name)) {
                        out.add(new GameEntry(
                                fileUri.toString(),
                                stripExtension(name),
                                name,
                                null  // icon extraction not yet implemented
                        ));
                    }
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private boolean isValidXbox360Game(@NonNull final Uri fileUri, @NonNull final String name) {
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        try (final ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd == null) return false;
            final FileDescriptor fd = pfd.getFileDescriptor();
            try (final FileInputStream fis = new FileInputStream(fd)) {
                final FileChannel channel = fis.getChannel();

                // Read first 4 bytes
                channel.position(0);
                final ByteBuffer buf = ByteBuffer.allocate(20);
                buf.limit(4);
                if (channel.read(buf) == 4) {
                    buf.flip();
                    final int magic = buf.getInt();
                    if (magic == 0x58455832 || magic == 0x58455831 || magic == 0x7F454C46) {
                        // "XEX2", "XEX1", "\x7FELF"
                        return true;
                    }
                    if (magic == 0x434F4E20 || magic == 0x50495253 || magic == 0x4C495645) {
                        // "CON ", "PIRS", "LIVE"
                        return true;
                    }
                }

                // For ISO games, check for "MICROSOFT*XBOX*MEDIA" at likely offsets + 32 * 2048
                if (lower.endsWith(".iso")) {
                    final long[] likelyOffsets = {0x00000000L, 0x0000FB20L, 0x00020600L, 0x02080000L, 0x0FD90000L};
                    final byte[] magicBytes = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
                    for (final long offset : likelyOffsets) {
                        final long targetPos = offset + 65536L; // 32 * 2048
                        if (channel.size() >= targetPos + 20) {
                            channel.position(targetPos);
                            buf.clear();
                            buf.limit(20);
                            if (channel.read(buf) == 20) {
                                buf.flip();
                                final byte[] readBytes = new byte[20];
                                buf.get(readBytes);
                                if (Arrays.equals(readBytes, magicBytes)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (final Exception e) {
            // Fail validation safely
        }
        return false;
    }

    private static boolean hasRecognisedExtension(@NonNull final String name) {
        final String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (final String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String stripExtension(@NonNull final String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

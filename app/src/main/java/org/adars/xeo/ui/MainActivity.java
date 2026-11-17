package org.adars.xeo.ui;

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.adars.xeo.R;
import org.adars.xeo.emulator.EmulatorActivity;
import org.adars.xeo.emulator.GpuTraceViewerActivity;
import org.adars.xeo.emulator.WindowedAppActivity;
import org.adars.xeo.utils.GameEntry;
import org.adars.xeo.utils.GameLibraryManager;
import org.adars.xeo.utils.PathUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main launcher screen for the game library and file/folder launch actions. */
public class MainActivity extends AppCompatActivity
        implements GameLibraryAdapter.OnGameClickListener {

    private GameLibraryManager mLibraryManager;
    private GameLibraryAdapter mAdapter;
    private TextView mEmptyView;
    private ExecutorService mScanExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private int mScanGeneration = 0;

    private final ActivityResultLauncher<String[]> mPickGameFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) launchGame(uri); });

    private final ActivityResultLauncher<String[]> mPickTraceFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) launchGpuTraceViewer(uri); });

    private final ActivityResultLauncher<Uri> mPickLibraryFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    persistTreePermission(uri);
                    mLibraryManager.setLibraryRoot(uri.toString());
                    refreshLibrary();
                }
            });

    private final ActivityResultLauncher<Uri> mPickExtractedGameFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    persistTreePermission(uri);
                    copyAndLaunchExtractedGame(uri);
                }
            });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) setSupportActionBar(toolbar);

        mLibraryManager = new GameLibraryManager(this);
        mScanExecutor = Executors.newSingleThreadExecutor();
        mEmptyView = findViewById(R.id.tv_empty);

        findViewById(R.id.button_import_file).setOnClickListener(
                view -> mPickGameFile.launch(new String[]{"application/octet-stream", "*/*"}));
        findViewById(R.id.button_import_folder).setOnClickListener(
                view -> mPickExtractedGameFolder.launch(null));

        final RecyclerView recyclerView = findViewById(R.id.rv_games);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new GameLibraryAdapter(this);
        recyclerView.setAdapter(mAdapter);

        handleIncomingIntent(getIntent());
        refreshLibrary();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (mScanExecutor != null) mScanExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_open_file) {
            mPickGameFile.launch(new String[]{"application/octet-stream", "*/*"});
            return true;
        } else if (id == R.id.action_open_game_folder) {
            mPickExtractedGameFolder.launch(null);
            return true;
        } else if (id == R.id.action_set_library_folder) {
            mPickLibraryFolder.launch(null);
            return true;
        } else if (id == R.id.action_gpu_trace_viewer) {
            mPickTraceFile.launch(new String[]{"application/octet-stream"});
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onGameClick(final GameEntry game) {
        launchGame(Uri.parse(game.getUri()));
    }

    private void persistTreePermission(final Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (final SecurityException ignored) {
            // Keep the temporary picker grant if the provider does not persist it.
        }
    }

    private void handleIncomingIntent(final Intent intent) {
        if (intent == null) return;
        final Uri data = intent.getData();
        if (data != null && Intent.ACTION_VIEW.equals(intent.getAction())) launchGame(data);
    }

    private void launchGame(final Uri gameUri) {
        if (ContentResolver.SCHEME_CONTENT.equals(gameUri.getScheme())) {
            try {
                getContentResolver().takePersistableUriPermission(
                        gameUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (final SecurityException ignored) { }
        }
        final String path = PathUtils.uriToNativePath(this, gameUri);
        if (path == null) {
            Toast.makeText(this, R.string.error_cannot_open_file, Toast.LENGTH_SHORT).show();
            return;
        }
        final Bundle cvars = new Bundle();
        cvars.putString("target", path);
        final Intent intent = new Intent(this, EmulatorActivity.class);
        intent.setData(gameUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(WindowedAppActivity.EXTRA_CVARS, cvars);
        intent.putExtra("game_uri", gameUri.toString());
        startActivity(intent);
    }

    /**
     * Xenia needs ordinary filesystem paths for the files beside default.xex.
     * SAF document URIs cannot provide that directory layout to native code,
     * so copy the selected extracted game tree into app-private external storage.
     */
    private void copyAndLaunchExtractedGame(final Uri treeUri) {
        if (mScanExecutor == null || mScanExecutor.isShutdown()) return;

        final ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle(R.string.action_import_game_folder);
        progressDialog.setMessage(getString(R.string.copying_game_folder));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setIndeterminate(true);
        progressDialog.setProgress(0);
        progressDialog.setMax(1000);
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        mScanExecutor.execute(() -> {
            String error = null;
            File copiedRoot = null;
            try {
                final DocumentFile sourceRoot = DocumentFile.fromTreeUri(this, treeUri);
                if (sourceRoot == null || !sourceRoot.isDirectory()) {
                    throw new IllegalStateException("Selected item is not a folder");
                }
                final DocumentFile defaultXex = findDefaultXex(sourceRoot);
                if (defaultXex == null) {
                    throw new IllegalStateException("default.xex was not found");
                }

                final long totalBytes = calculateTotalBytes(sourceRoot);
                mMainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed() && progressDialog.isShowing()) {
                        progressDialog.setIndeterminate(totalBytes <= 0);
                        progressDialog.setProgress(0);
                        progressDialog.setMessage(getString(R.string.copy_progress, "0 B",
                                formatBytes(totalBytes)));
                    }
                });

                final File gamesDir = getExternalFilesDir("games");
                if (gamesDir == null) throw new IllegalStateException("Storage unavailable");
                final String folderName = safeName(sourceRoot.getName());
                copiedRoot = new File(gamesDir, folderName + "_" + System.currentTimeMillis());
                if (!copiedRoot.mkdirs()) throw new IllegalStateException("Cannot create game folder");

                final long[] copiedBytes = {0L};
                final int[] lastProgress = {-1};
                copyDocumentTree(sourceRoot, copiedRoot, totalBytes, copiedBytes,
                        lastProgress, progressDialog);

                final String relative = relativeDocumentPath(sourceRoot, defaultXex);
                final File executable = new File(copiedRoot, relative);
                if (!executable.isFile()) throw new IllegalStateException("Could not copy default.xex");
                final File finalExecutable = executable;
                mMainHandler.post(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    refreshLibrary();
                    Toast.makeText(this, R.string.import_folder_success, Toast.LENGTH_LONG).show();
                });
                return;
            } catch (final Exception e) {
                error = e.getMessage();
                if (copiedRoot != null) deleteRecursively(copiedRoot);
            }
            final String message = error == null
                    ? getString(R.string.error_cannot_open_game_folder) : error;
            mMainHandler.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private long calculateTotalBytes(final DocumentFile directory) {
        long total = 0L;
        for (final DocumentFile child : directory.listFiles()) {
            if (child.isDirectory()) {
                total += calculateTotalBytes(child);
            } else {
                final long length = child.length();
                if (length > 0) total += length;
            }
        }
        return total;
    }

    private void copyDocumentTree(final DocumentFile source, final File destination,
                                  final long totalBytes, final long[] copiedBytes,
                                  final int[] lastProgress,
                                  final ProgressDialog progressDialog) throws Exception {
        for (final DocumentFile child : source.listFiles()) {
            final String name = child.getName();
            if (name == null) continue;
            final File target = new File(destination, safeName(name));
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new java.io.IOException("Cannot create " + name);
                }
                copyDocumentTree(child, target, totalBytes, copiedBytes,
                        lastProgress, progressDialog);
            } else {
                try (InputStream in = getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new java.io.IOException("Cannot read " + name);
                    final byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        copiedBytes[0] += count;
                        final long current = copiedBytes[0];
                        if (totalBytes > 0) {
                            final int progress = (int) Math.min(1000L,
                                    current * 1000L / totalBytes);
                            // Limit UI updates to at most 1,000 progress steps.
                            if (progress > lastProgress[0]) {
                                lastProgress[0] = progress;
                                mMainHandler.post(() -> {
                                    if (!isFinishing() && !isDestroyed() && progressDialog.isShowing()) {
                                        progressDialog.setIndeterminate(false);
                                        progressDialog.setProgress(progress);
                                        progressDialog.setMessage(getString(R.string.copy_progress,
                                                formatBytes(current), formatBytes(totalBytes)));
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }

    private static String formatBytes(final long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f KB",
                bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f MB",
                bytes / (1024.0 * 1024.0));
        return String.format(Locale.getDefault(), "%.2f GB",
                bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private DocumentFile findDefaultXex(final DocumentFile directory) {
        final DocumentFile[] children = directory.listFiles();
        for (final DocumentFile child : children) {
            if (child.isDirectory()) {
                final DocumentFile found = findDefaultXex(child);
                if (found != null) return found;
            } else if ("default.xex".equalsIgnoreCase(child.getName())) {
                return child;
            }
        }
        return null;
    }

    private void copyDocumentTree(final DocumentFile source, final File destination) throws Exception {
        for (final DocumentFile child : source.listFiles()) {
            final String name = child.getName();
            if (name == null) continue;
            final File target = new File(destination, name);
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) throw new java.io.IOException("Cannot create " + name);
                copyDocumentTree(child, target);
            } else {
                try (InputStream in = getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new java.io.IOException("Cannot read " + name);
                    final byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
        }
    }

    private String relativeDocumentPath(final DocumentFile root, final DocumentFile file) {
        // Walk the SAF parent chain to preserve the executable's subdirectory.
        String path = safeName(file.getName());
        DocumentFile parent = file.getParentFile();
        while (parent != null && !parent.getUri().equals(root.getUri())) {
            path = safeName(parent.getName()) + File.separator + path;
            parent = parent.getParentFile();
        }
        return path;
    }

    private static String safeName(final String value) {
        if (value == null || value.trim().isEmpty()) return "Xbox360Game";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void deleteRecursively(final File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) for (final File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private void launchNativePath(final String path) {
        final Bundle cvars = new Bundle();
        cvars.putString("target", path);
        final Intent intent = new Intent(this, EmulatorActivity.class);
        intent.putExtra(WindowedAppActivity.EXTRA_CVARS, cvars);
        startActivity(intent);
    }

    private void launchGpuTraceViewer(final Uri traceUri) {
        final Bundle cvars = new Bundle();
        cvars.putString("target_trace_file", traceUri.toString());
        final Intent intent = new Intent(this, GpuTraceViewerActivity.class);
        intent.setData(traceUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(WindowedAppActivity.EXTRA_CVARS, cvars);
        startActivity(intent);
    }

    private void refreshLibrary() {
        if (mScanExecutor == null || mScanExecutor.isShutdown()) return;
        final int generation = ++mScanGeneration;
        mEmptyView.setVisibility(View.GONE);
        mScanExecutor.execute(() -> {
            final List<GameEntry> games = mLibraryManager.scanLibrary();
            mMainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || generation != mScanGeneration) return;
                mAdapter.submitList(games);
                mEmptyView.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }
}

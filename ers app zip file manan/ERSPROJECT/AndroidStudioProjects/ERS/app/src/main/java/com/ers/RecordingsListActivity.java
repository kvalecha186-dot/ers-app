package com.ers;

import android.content.SharedPreferences;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecordingsListActivity
 *
 * BUGS FIXED in this version:
 *   [Bug 3]  Adapter is now a static inner class — no implicit Activity reference → no OOM
 *   [Bug 7]  Share uses exact MIME "audio/mp4" — WhatsApp no longer rejects
 *   [Bug 13] onResume() calls loadRecordings() — new recordings always visible
 *
 * NEW FEATURE:
 *   [Sent Status] Each recording shows a 🔴/🟢 dot — red = never sent, green = sent.
 *                 Stored in SharedPreferences as "sent_<filename>".
 */
public class RecordingsListActivity extends AppCompatActivity {

    private RecyclerView      recyclerView;
    private RecordingsAdapter adapter;
    private List<File>        recordings;
    private LinearLayout      emptyState;
    private SharedPreferences prefs;

    private MediaPlayer mediaPlayer;
    private int         currentlyPlayingPos = -1;

    private final Handler  seekHandler = new Handler(Looper.getMainLooper());
    private       Runnable seekRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings_list);

        prefs        = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        recyclerView = findViewById(R.id.recordingsList);
        emptyState   = findViewById(R.id.emptyState);

        View back = findViewById(R.id.backButton);
        if (back != null) back.setOnClickListener(v -> onBackPressed());

        recordings = new ArrayList<>();
        adapter    = new RecordingsAdapter(recordings, prefs, recyclerView,
                        mediaPlayer, currentlyPlayingPos,
                        this::startPlayback, this::stopPlayback,
                        this::shareRecording, this::showDeleteDialog);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    // [Bug 13] Refresh list every time screen becomes visible
    @Override
    protected void onResume() {
        super.onResume();
        loadRecordings();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadRecordings() {
        recordings.clear();

        File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "ERS_Recordings");

        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".m4a"));
            if (files != null && files.length > 0) {
                recordings.addAll(Arrays.asList(files));
                recordings.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                adapter.notifyDataSetChanged();
                emptyState.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                return;
            }
        }
        emptyState.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void startPlayback(File file, int position) {
        try {
            releasePlayer();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());

            mediaPlayer.setOnPreparedListener(mp -> {
                currentlyPlayingPos = position;
                adapter.setPlayingPos(position, mp);
                adapter.notifyItemChanged(position);
                mp.start();
                startSeekUpdater();
            });

            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.setOnErrorListener((mp, w, e) -> { stopPlayback(); return true; });
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Toast.makeText(this, "❌ Cannot play file", Toast.LENGTH_SHORT).show();
        }
    }

    private void startSeekUpdater() {
        seekHandler.removeCallbacks(seekRunnable);
        seekRunnable = new Runnable() {
            @Override public void run() {
                if (mediaPlayer == null || !mediaPlayer.isPlaying()) return;
                RecyclerView.ViewHolder vh =
                        recyclerView.findViewHolderForAdapterPosition(currentlyPlayingPos);
                if (vh instanceof RecordingsAdapter.ViewHolder) {
                    RecordingsAdapter.ViewHolder h = (RecordingsAdapter.ViewHolder) vh;
                    h.seekBar.setProgress(mediaPlayer.getCurrentPosition());
                    h.durationText.setText(fmtDuration(
                            mediaPlayer.getDuration() - mediaPlayer.getCurrentPosition()));
                }
                seekHandler.postDelayed(this, 200);
            }
        };
        seekHandler.post(seekRunnable);
    }

    private void stopPlayback() {
        seekHandler.removeCallbacks(seekRunnable);
        int prev = currentlyPlayingPos;
        releasePlayer();
        currentlyPlayingPos = -1;
        adapter.setPlayingPos(-1, null);
        if (prev != -1) adapter.notifyItemChanged(prev);
    }

    private void releasePlayer() {
        if (mediaPlayer == null) return;
        try { mediaPlayer.stop();    } catch (Exception ignore) {}
        try { mediaPlayer.release(); } catch (Exception ignore) {}
        mediaPlayer = null;
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private void shareRecording(File file) {
        try {
            Uri    uri = FileProvider.getUriForFile(this, "com.ers.fileprovider", file);
            Intent i   = new Intent(Intent.ACTION_SEND);

            // [Bug 7] FIXED: exact MIME type "audio/mp4" — WhatsApp accepts this
            i.setType("audio/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "🚨 Emergency Recording");
            i.putExtra(Intent.EXTRA_TEXT,
                    "ERS recording — " + new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
                            .format(new Date(file.lastModified())));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share Recording"));

            // Mark as sent after user opens share chooser
            prefs.edit().putBoolean("sent_" + file.getName(), true).apply();
            adapter.notifyDataSetChanged();

        } catch (Exception e) {
            Toast.makeText(this, "❌ Unable to share", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteDialog(File file, int position) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Recording?")
                .setMessage("This will be permanently deleted and cannot be recovered.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (currentlyPlayingPos == position) stopPlayback();
                    // Remove sent-status entry too
                    prefs.edit().remove("sent_" + file.getName()).apply();
                    if (file.delete()) {
                        recordings.remove(position);
                        adapter.notifyItemRemoved(position);
                        if (currentlyPlayingPos > position) {
                            currentlyPlayingPos--;
                            adapter.setPlayingPos(currentlyPlayingPos, mediaPlayer);
                        }
                        if (recordings.isEmpty()) {
                            emptyState.setVisibility(View.VISIBLE);
                            recyclerView.setVisibility(View.GONE);
                        }
                        Toast.makeText(this, "✅ Deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ Delete failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        stopPlayback();
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    private String fmtDuration(int ms) {
        int sec = (ms / 1000) % 60;
        int min = ms / 60_000;
        return min + ":" + String.format(Locale.US, "%02d", sec);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  [Bug 3] STATIC adapter — no implicit Activity reference
    // ═════════════════════════════════════════════════════════════════════════

    static class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {

        // Callbacks replace direct Activity references
        interface OnPlay   { void play(File f, int pos); }
        interface OnStop   { void stop(); }
        interface OnShare  { void share(File f); }
        interface OnDelete { void delete(File f, int pos); }

        private final List<File>        files;
        private final SharedPreferences prefs;
        private final RecyclerView      rv;
        private       int               playingPos = -1;
        private       MediaPlayer       mp;

        private final OnPlay   onPlay;
        private final OnStop   onStop;
        private final OnShare  onShare;
        private final OnDelete onDelete;

        RecordingsAdapter(List<File> files, SharedPreferences prefs, RecyclerView rv,
                          MediaPlayer mp, int playingPos,
                          OnPlay onPlay, OnStop onStop,
                          OnShare onShare, OnDelete onDelete) {
            this.files     = files;
            this.prefs     = prefs;
            this.rv        = rv;
            this.mp        = mp;
            this.playingPos = playingPos;
            this.onPlay    = onPlay;
            this.onStop    = onStop;
            this.onShare   = onShare;
            this.onDelete  = onDelete;
        }

        void setPlayingPos(int pos, MediaPlayer player) {
            this.playingPos = pos;
            this.mp         = player;
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File    file    = files.get(position);
            boolean playing = (position == playingPos && mp != null && mp.isPlaying());

            // ── Human-readable filename ────────────────────────────────────
            String name = file.getName().replace(".m4a", "").replace("ERS_", "");
            try {
                Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name);
                if (d != null) name = new SimpleDateFormat(
                        "dd MMM yyyy  hh:mm a", Locale.US).format(d);
            } catch (Exception ignore) {}
            holder.fileNameText.setText(name);

            holder.fileSizeText.setText(String.format(Locale.US, "%.2f MB",
                    file.length() / (1024.0 * 1024.0)));
            holder.dateText.setText(new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
                    .format(new Date(file.lastModified())));

            // ── [SENT STATUS DOT] 🟢 sent / 🔴 not sent ───────────────────
            boolean wasSent = prefs.getBoolean("sent_" + file.getName(), false);
            if (holder.sentStatusDot != null) {
                holder.sentStatusDot.setVisibility(View.VISIBLE);
                holder.sentStatusDot.setColorFilter(
                        ContextCompat.getColor(holder.itemView.getContext(),
                                wasSent ? R.color.success : R.color.error));
            }
            if (holder.sentStatusText != null) {
                holder.sentStatusText.setText(wasSent ? "Sent ✓" : "Not sent");
                holder.sentStatusText.setTextColor(
                        ContextCompat.getColor(holder.itemView.getContext(),
                                wasSent ? R.color.success : R.color.error));
            }

            // ── Play state ─────────────────────────────────────────────────
            holder.playPauseButton.setImageResource(
                    playing ? R.drawable.icon_stop : R.drawable.icon_start);
            holder.seekBar.setVisibility(playing ? View.VISIBLE : View.GONE);
            holder.durationText.setVisibility(playing ? View.VISIBLE : View.GONE);

            if (playing && mp != null) {
                holder.seekBar.setMax(mp.getDuration());
                holder.seekBar.setProgress(mp.getCurrentPosition());
            }

            holder.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user && mp != null) mp.seekTo(p);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });

            // ── Buttons ────────────────────────────────────────────────────
            holder.playPauseButton.setOnClickListener(v -> {
                v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.button_click));
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                if (playingPos == pos && mp != null) onStop.stop();
                else onPlay.play(files.get(pos), pos);
            });

            holder.shareButton.setOnClickListener(v -> {
                v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.button_click));
                onShare.share(file);
            });

            holder.deleteButton.setOnClickListener(v -> {
                v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.button_click));
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) onDelete.delete(files.get(pos), pos);
            });

            holder.itemView.setAnimation(AnimationUtils.loadAnimation(
                    holder.itemView.getContext(), R.anim.slide_in_right));
        }

        @Override public int getItemCount() { return files.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView    fileNameText, fileSizeText, dateText, durationText, sentStatusText;
            ImageButton shareButton, deleteButton, playPauseButton;
            ImageView   sentStatusDot;
            SeekBar     seekBar;

            ViewHolder(View v) {
                super(v);
                fileNameText   = v.findViewById(R.id.fileNameText);
                fileSizeText   = v.findViewById(R.id.fileSizeText);
                dateText       = v.findViewById(R.id.dateText);
                durationText   = v.findViewById(R.id.durationText);
                sentStatusText = v.findViewById(R.id.sentStatusText);
                sentStatusDot  = v.findViewById(R.id.sentStatusDot);
                shareButton    = v.findViewById(R.id.shareButton);
                deleteButton   = v.findViewById(R.id.deleteButton);
                playPauseButton = v.findViewById(R.id.playPauseButton);
                seekBar        = v.findViewById(R.id.audioSeekBar);
            }
        }
    }
}

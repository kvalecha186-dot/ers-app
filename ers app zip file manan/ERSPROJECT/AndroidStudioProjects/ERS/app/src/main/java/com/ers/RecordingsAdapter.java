package com.ers;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {
    private List<File> recordingsList;

    public RecordingsAdapter(List<File> recordingsList) {
        this.recordingsList = recordingsList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = recordingsList.get(position);
        holder.fileNameText.setText(file.getName());
        
        long fileSize = file.length();
        String size = String.format(Locale.US, "%.2f MB", fileSize / (1024.0 * 1024.0));
        holder.fileSizeText.setText(size);
        
        Date date = new Date(file.lastModified());
        String dateStr = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(date);
        holder.dateText.setText(dateStr);
    }

    @Override
    public int getItemCount() {
        return recordingsList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView fileNameText;
        TextView fileSizeText;
        TextView dateText;

        ViewHolder(View itemView) {
            super(itemView);
            fileNameText = itemView.findViewById(R.id.fileNameText);
            fileSizeText = itemView.findViewById(R.id.fileSizeText);
            dateText = itemView.findViewById(R.id.dateText);
        }
    }
}

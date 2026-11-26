package com.livetv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.model.LiveSource;

import java.util.ArrayList;
import java.util.List;

public class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.Holder> {

    public interface Listener {
        void onSourceClick(int position);

        void onSourceLongClick(int position);
    }

    private final List<LiveSource> items = new ArrayList<>();
    private final Listener listener;
    private int active;

    public SourceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<LiveSource> sources, int active) {
        items.clear();
        items.addAll(sources);
        this.active = active;
        notifyDataSetChanged();
    }

    public LiveSource get(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_source, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LiveSource source = items.get(position);
        holder.name.setText(source.displayName());
        holder.url.setText(source.url);
        holder.active.setVisibility(position == active ? View.VISIBLE : View.GONE);
        final int index = position;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSourceClick(index);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onSourceLongClick(index);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final TextView name;
        final TextView url;
        final TextView active;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.src_name);
            url = itemView.findViewById(R.id.src_url);
            active = itemView.findViewById(R.id.src_active);
        }
    }
}

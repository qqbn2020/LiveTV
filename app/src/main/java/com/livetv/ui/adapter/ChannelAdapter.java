package com.livetv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.Store;
import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.ui.LogoLoader;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {

    public interface Listener {
        void onChannelClick(int position);

        void onChannelLongClick(int position);
    }

    private final List<Channel> items = new ArrayList<>();
    private final Listener listener;
    private int selected = -1;

    public ChannelAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Channel> channels, int selected) {
        items.clear();
        items.addAll(channels);
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void setSelected(int selected) {
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void refresh() {
        notifyDataSetChanged();
    }

    public Channel get(int position) {
        return items.get(position);
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Channel channel = items.get(position);
        LogoLoader.load(holder.logo, channel.logo, channel.ua);
        holder.number.setText(channel.number);
        holder.name.setText(channel.name);
        EpgProgram program = channel.now();
        holder.program.setText(program == null ? "" : program.title);
        holder.lines.setText(channel.urls.size() > 1 ? channel.urls.size() + "线" : "");
        holder.favorite.setVisibility(Store.isFavorite(holder.itemView.getContext(), channel) ? View.VISIBLE : View.GONE);
        holder.itemView.setSelected(position == selected);
        final int index = position;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onChannelClick(index);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onChannelLongClick(index);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final TextView number;
        final TextView name;
        final TextView program;
        final TextView lines;
        final ImageView logo;
        final TextView favorite;

        Holder(@NonNull View itemView) {
            super(itemView);
            logo = itemView.findViewById(R.id.ch_logo);
            number = itemView.findViewById(R.id.ch_number);
            name = itemView.findViewById(R.id.ch_name);
            program = itemView.findViewById(R.id.ch_program);
            lines = itemView.findViewById(R.id.ch_lines);
            favorite = itemView.findViewById(R.id.ch_fav);
        }
    }
}



package com.livetv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.util.Fmt;

import java.util.ArrayList;
import java.util.List;

public class ProgramAdapter extends RecyclerView.Adapter<ProgramAdapter.Holder> {

    public interface Listener {
        void onProgramClick(int position);
    }

    private final List<EpgProgram> items = new ArrayList<>();
    private final Listener listener;
    private final Channel channel;

    public ProgramAdapter(Channel channel, Listener listener) {
        this.channel = channel;
        this.listener = listener;
    }

    public Channel channel() {
        return channel;
    }

    public void setItems(List<EpgProgram> programs) {
        items.clear();
        items.addAll(programs);
        notifyDataSetChanged();
    }

    public EpgProgram get(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_program, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        EpgProgram program = items.get(position);
        holder.time.setText(Fmt.range(program.startTime, program.endTime));
        holder.title.setText(program.title);
        holder.tag.setVisibility(channel != null && channel.hasCatchup() && program.isPast() ? View.VISIBLE : View.GONE);
        int color = ContextCompat.getColor(holder.itemView.getContext(), program.isPlaying() ? R.color.accent : R.color.text);
        holder.title.setTextColor(color);
        final int index = position;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onProgramClick(index);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final TextView time;
        final TextView title;
        final TextView tag;

        Holder(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.prog_time);
            title = itemView.findViewById(R.id.prog_title);
            tag = itemView.findViewById(R.id.prog_tag);
        }
    }
}

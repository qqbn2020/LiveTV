package com.livetv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.model.Group;

import java.util.ArrayList;
import java.util.List;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.Holder> {

    public interface Listener {
        void onGroupClick(int position);

        void onGroupFocus(int position);

        void onGroupLongClick(int position);
    }

    private final List<Group> items = new ArrayList<>();
    private final Listener listener;
    private int selected;

    public GroupAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Group> groups, int selected) {
        items.clear();
        items.addAll(groups);
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void setSelected(int selected) {
        this.selected = selected;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Group group = items.get(position);
        holder.name.setText(group.name);
        holder.itemView.setSelected(position == selected);
        final int index = position;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onGroupClick(index);
            }
        });
        holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) listener.onGroupFocus(index);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onGroupLongClick(index);
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

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.group_name);
        }
    }
}

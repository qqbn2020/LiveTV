package com.livetv.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.ui.adapter.ProgramAdapter;
import com.livetv.util.Fmt;

public final class Dialogs {

    public interface ProgramListener {
        void onCatchup(EpgProgram program);
    }

    public interface LineListener {
        void onLine(int index);
    }

    public interface MenuListener {
        void onFavorite();

        void onLines();

        void onPrograms();
    }

    public interface GroupListener {
        void onToggleHidden();
    }

    private Dialogs() {
    }

    public static void programs(Context context, Channel channel, ProgramListener listener) {
        if (channel == null) return;
        if (channel.programs.isEmpty()) {
            Toast.makeText(context, R.string.no_program, Toast.LENGTH_SHORT).show();
            return;
        }
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_list, null);
        TextView title = view.findViewById(R.id.dialog_title);
        title.setText(context.getString(R.string.programs) + " · " + channel.name);
        RecyclerView list = view.findViewById(R.id.dialog_list);
        list.setLayoutManager(new LinearLayoutManager(context));

        final AlertDialog[] holder = new AlertDialog[1];
        final ProgramAdapter adapter = new ProgramAdapter(channel, new ProgramAdapter.Listener() {
            @Override
            public void onProgramClick(int position) {
                EpgProgram program = channel.programs.get(position);
                if (program.isPast()) {
                    if (!channel.hasCatchup()) {
                        Toast.makeText(context, R.string.no_catchup, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (holder[0] != null) holder[0].dismiss();
                    listener.onCatchup(program);
                } else {
                    Toast.makeText(context, R.string.no_program, Toast.LENGTH_SHORT).show();
                }
            }
        });
        list.setAdapter(adapter);
        adapter.setItems(channel.programs);

        holder[0] = new AlertDialog.Builder(context, R.style.DialogTheme)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create();
        holder[0].show();
    }

    public static void lines(Context context, Channel channel, LineListener listener) {
        if (channel == null || channel.urls.size() <= 1) {
            Toast.makeText(context, "该频道只有一条线路", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[channel.urls.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = "线路 " + (i + 1) + "  " + Fmt.host(channel.urls.get(i));
        }
        new AlertDialog.Builder(context, R.style.DialogTheme)
                .setTitle(channel.name + " · " + context.getString(R.string.lines))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listener.onLine(which);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void channelMenu(Context context, Channel channel, boolean favorite, MenuListener listener) {
        if (channel == null) return;
        String[] items = new String[]{
                context.getString(favorite ? R.string.favorite_remove : R.string.favorite_add),
                context.getString(R.string.line_switch),
                context.getString(R.string.programs)
        };
        new AlertDialog.Builder(context, R.style.DialogTheme)
                .setTitle(channel.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) listener.onFavorite();
                        else if (which == 1) listener.onLines();
                        else listener.onPrograms();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void groupMenu(Context context, String group, boolean hidden, GroupListener listener) {
        String[] items = new String[]{context.getString(hidden ? R.string.show_hidden : R.string.hide_group)};
        new AlertDialog.Builder(context, R.style.DialogTheme)
                .setTitle(context.getString(R.string.group_menu) + " · " + group)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listener.onToggleHidden();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

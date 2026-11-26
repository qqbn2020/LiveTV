package com.livetv.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.Store;
import com.livetv.data.EpgRepository;
import com.livetv.model.LiveSource;
import com.livetv.net.LanServer;
import com.livetv.player.PlayerController;
import com.livetv.ui.adapter.SourceAdapter;
import com.livetv.ui.dialog.SourceEditDialog;

import java.util.List;

public class SettingsActivity extends AppCompatActivity implements SourceAdapter.Listener, SourceEditDialog.Listener {

    private RecyclerView list;
    private TextView hint;
    private SourceAdapter adapter;
    private List<LiveSource> sources;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        immersive();
        list = findViewById(R.id.source_list);
        hint = findViewById(R.id.source_hint);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SourceAdapter(this);
        list.setAdapter(adapter);
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SourceEditDialog.show(SettingsActivity.this, -1, null);
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EpgRepository.clear();
                Store.bumpReload(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this, "已触发刷新", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        findViewById(R.id.btn_playback).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playbackSettings();
            }
        });
        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this, R.style.DialogTheme)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.about_text)
                        .setPositiveButton(R.string.confirm, null)
                        .show();
            }
        });
        reload();
    }

    private void immersive() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void reload() {
        sources = Store.sources(this);
        int active = Store.activeIndex(this);
        adapter.setItems(sources, active);
        hint.setText(sources.isEmpty() ? getString(R.string.empty_source) : getString(R.string.click_to_switch));
    }

    @Override
    public void onSourceClick(int position) {
        Store.setActive(this, position);
        Store.bumpReload(this);
        finish();
    }

    @Override
    public void onSourceLongClick(int position) {
        SourceEditDialog.show(this, position, adapter.get(position));
    }

    @Override
    public void onSourceSaved(int index, LiveSource source) {
        if (index < 0) {
            int created = Store.add(this, source);
            Store.setActive(this, created);
        } else {
            Store.update(this, index, source);
        }
        Store.bumpReload(this);
        reload();
        Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onSourceDeleted(int index) {
        Store.remove(this, index);
        Store.bumpReload(this);
        reload();
    }

    private void playbackSettings() {
        final String[] items = new String[]{
                getString(R.string.aspect) + "：" + aspectName(Store.aspect(this)),
                getString(R.string.buffer_seconds) + "：" + Store.buffer(this) + "s",
                getString(R.string.cross_group) + "：" + onOff(Store.crossGroup(this)),
                getString(R.string.auto_start) + "：" + onOff(Store.autoStart(this)),
                getString(R.string.timeshift) + "：" + onOff(Store.timeshift(this)),
                getString(R.string.timeshift_window) + "：" + getString(R.string.minutes, Store.timeshiftMinutes(this)),
                getString(R.string.decode_mode) + "：" + decodeName(Store.decodeMode(this)),
                getString(R.string.show_hidden) + "：" + onOff(Store.showHiddenGroups(this)),
                lanEntry(),
                getString(R.string.danmaku) + "：" + onOff(Store.danmaku(this)),
                getString(R.string.player_engine) + "：" + engineName(Store.playerEngine(this))
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.playback_settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) chooseAspect();
                        else if (which == 1) chooseBuffer();
                        else if (which == 2) {
                            Store.setCrossGroup(SettingsActivity.this, !Store.crossGroup(SettingsActivity.this));
                            playbackSettings();
                        } else if (which == 3) {
                            Store.setAutoStart(SettingsActivity.this, !Store.autoStart(SettingsActivity.this));
                            playbackSettings();
                        } else if (which == 4) {
                            Store.setTimeshift(SettingsActivity.this, !Store.timeshift(SettingsActivity.this));
                            playbackSettings();
                        } else if (which == 5) {
                            chooseTimeshiftWindow();
                        } else if (which == 6) {
                            chooseDecode();
                        } else if (which == 7) {
                            Store.setShowHiddenGroups(SettingsActivity.this, !Store.showHiddenGroups(SettingsActivity.this));
                            Store.bumpReload(SettingsActivity.this);
                            playbackSettings();
                        } else if (which == 9) {
                            Store.setDanmaku(SettingsActivity.this, !Store.danmaku(SettingsActivity.this));
                            playbackSettings();
                        } else if (which == 10) {
                            chooseEngine();
                        } else {
                            boolean enabled = !Store.lanServer(SettingsActivity.this);
                            Store.setLanServer(SettingsActivity.this, enabled);
                            if (enabled) {
                                String address = LanServer.address();
                                Toast.makeText(SettingsActivity.this, address == null ? "局域网地址不可用" : "手机浏览器访问：" + address, Toast.LENGTH_LONG).show();
                            }
                            Store.bumpReload(SettingsActivity.this);
                            playbackSettings();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String decodeName(int mode) {
        return getString(mode == 1 ? R.string.decode_soft : R.string.decode_auto);
    }

    private String engineName(int engine) {
        if (engine == 1) return getString(R.string.engine_ijk);
        if (engine == 2) return getString(R.string.engine_mpv);
        return getString(R.string.engine_exo);
    }

    private void chooseEngine() {
        String[] items = new String[]{
                getString(R.string.engine_exo),
                getString(R.string.engine_ijk),
                getString(R.string.engine_mpv)
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.player_engine)
                .setSingleChoiceItems(items, Store.playerEngine(this), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!PlayerController.available(which)) {
                            Toast.makeText(SettingsActivity.this, R.string.engine_unavailable, Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            return;
                        }
                        Store.setPlayerEngine(SettingsActivity.this, which);
                        Store.bumpReload(SettingsActivity.this);
                        dialog.dismiss();
                        playbackSettings();
                    }
                })
                .setNeutralButton(R.string.engine_restart_hint, null)
                .show();
    }

    private String lanEntry() {
        boolean enabled = Store.lanServer(this);
        String address = enabled ? LanServer.address() : null;
        return getString(R.string.lan_server) + "：" + onOff(enabled) + (address == null ? "" : "  " + address);
    }

    private void chooseDecode() {
        String[] items = new String[]{getString(R.string.decode_auto), getString(R.string.decode_soft)};
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.decode_mode)
                .setSingleChoiceItems(items, Store.decodeMode(this), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Store.setDecodeMode(SettingsActivity.this, which);
                        Store.bumpReload(SettingsActivity.this);
                        dialog.dismiss();
                        playbackSettings();
                    }
                })
                .show();
    }

    private void chooseTimeshiftWindow() {
        final int[] values = new int[]{5, 10, 30};
        String[] items = new String[]{
                getString(R.string.minutes, 5),
                getString(R.string.minutes, 10),
                getString(R.string.minutes, 30)
        };
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == Store.timeshiftMinutes(this)) checked = i;
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.timeshift_window)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Store.setTimeshiftMinutes(SettingsActivity.this, values[which]);
                        dialog.dismiss();
                        playbackSettings();
                    }
                })
                .show();
    }

    private String aspectName(int aspect) {
        if (aspect == 1) return getString(R.string.aspect_fill);
        if (aspect == 2) return getString(R.string.aspect_zoom);
        return getString(R.string.aspect_fit);
    }

    private String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private void chooseAspect() {
        String[] items = new String[]{getString(R.string.aspect_fit), getString(R.string.aspect_fill), getString(R.string.aspect_zoom)};
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.aspect)
                .setSingleChoiceItems(items, Store.aspect(this), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Store.setAspect(SettingsActivity.this, which);
                        dialog.dismiss();
                        playbackSettings();
                    }
                })
                .show();
    }

    private void chooseBuffer() {
        final int[] values = new int[]{4, 8, 15, 30};
        String[] items = new String[]{"4 秒", "8 秒", "15 秒", "30 秒"};
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == Store.buffer(this)) checked = i;
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.buffer_seconds)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Store.setBuffer(SettingsActivity.this, values[which]);
                        dialog.dismiss();
                        playbackSettings();
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
    }
}



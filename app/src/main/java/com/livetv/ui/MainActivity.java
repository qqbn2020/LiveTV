package com.livetv.ui;

import android.content.Intent;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.livetv.R;
import com.livetv.Store;
import com.livetv.data.EpgRepository;
import com.livetv.data.LiveLoader;
import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.model.Group;
import com.livetv.model.LiveSource;
import com.livetv.model.Playlist;
import com.livetv.net.LanServer;
import com.livetv.player.DanmakuItem;
import com.livetv.player.DanmakuRepository;
import com.livetv.player.DanmakuView;
import com.livetv.player.PlayerController;
import com.livetv.ui.adapter.ChannelAdapter;
import com.livetv.ui.adapter.GroupAdapter;
import com.livetv.ui.dialog.Dialogs;
import com.livetv.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements GroupAdapter.Listener, ChannelAdapter.Listener, PlayerController.Listener {

    private PlayerController controller;
    private int playerConfigToken = -1;
    private ViewGroup playerView;

    private View root;
    private View touchLayer;
    private View infoBar;
    private View panel;
    private View toolbar;
    private View timeshiftBar;
    private SeekBar tsSeek;
    private TextView tsState;
    private TextView tsClock;
    private TextView hint;
    private View loadingPanel;
    private TextView numberInput;

    private TextView chNumber;
    private TextView chName;
    private TextView chProgram;
    private TextView chClock;
    private TextView chNext;
    private TextView loadingText;

    private ProgressBar progress;

    private RecyclerView groupList;
    private RecyclerView channelList;

    private GroupAdapter groupAdapter;
    private ChannelAdapter channelAdapter;
    private DanmakuView danmakuView;
    private int danmakuGen;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 弹幕必须跟随播放器位置，而不是墙钟：暂停/缓冲/时移都靠它保持同步。 */
    private final Runnable danmakuTickTask = new Runnable() {
        @Override
        public void run() {
            if (danmakuView != null && controller != null && Store.danmaku(MainActivity.this)) {
                danmakuView.setPosition(controller.position());
            }
            handler.postDelayed(this, 250);
        }
    };

    private Playlist playlist;
    private List<Group> groups = new ArrayList<>();
    private Group currentGroup;
    private Group searchGroup;
    private int groupIndex;
    private int channelIndex = -1;

    private Channel playing;
    private LiveSource loadedSource;
    private int loadedToken = -1;
    private boolean loading;
    private boolean isTv;
    private boolean panelVisible;
    private boolean toolbarVisible;
    private boolean okLong;
    private boolean sourcePromptShown;
    private boolean seekDragging;
    private long lastBack;
    private int reloadSeq;
    private int epgGen;
    private String searchKeyword = "";

    private String number = "";
    private String restoreGroup = "";
    private String restoreName = "";

    private GestureDetector gestureDetector;

    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            handler.postDelayed(this, 20000);
        }
    };

    private final Runnable hideToolbarTask = new Runnable() {
        @Override
        public void run() {
            if (seekDragging) {
                handler.postDelayed(this, 2000);
                return;
            }
            hideToolbar();
        }
    };

    /** INFO 信息条按需显示，几秒后自动隐藏，避免长期遮挡直播画面。 */
    private final Runnable hideInfoTask = new Runnable() {
        @Override
        public void run() {
            infoBar.setVisibility(View.GONE);
        }
    };

    private final Runnable numberJumpTask = new Runnable() {
        @Override
        public void run() {
            jumpByNumber();
        }
    };

    private final Runnable seekTask = new Runnable() {
        @Override
        public void run() {
            updateSeekBar();
            if (toolbarVisible) handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        isTv = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        bindViews();
        immersive();
        handleIntent(getIntent());
        setupPlayer();
        setupLists();
        setupGestures();
        showLoading(getString(R.string.loading));
        startDanmakuTicker();
        reload();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        playerView = findViewById(R.id.player_view);
        danmakuView = findViewById(R.id.danmaku_view);
        touchLayer = findViewById(R.id.touch_layer);
        infoBar = findViewById(R.id.info_bar);
        panel = findViewById(R.id.panel);
        toolbar = findViewById(R.id.toolbar);
        timeshiftBar = findViewById(R.id.timeshift_bar);
        tsSeek = findViewById(R.id.ts_seek);
        tsState = findViewById(R.id.ts_state);
        tsClock = findViewById(R.id.ts_clock);
        hint = findViewById(R.id.hint);
        loadingPanel = findViewById(R.id.loading_panel);
        numberInput = findViewById(R.id.number_input);
        chNumber = findViewById(R.id.ch_number);
        chName = findViewById(R.id.ch_name);
        chProgram = findViewById(R.id.ch_program);
        chClock = findViewById(R.id.ch_clock);
        chNext = findViewById(R.id.ch_next);
        loadingText = findViewById(R.id.loading_text);
        progress = findViewById(R.id.prog);
        groupList = findViewById(R.id.group_list);
        channelList = findViewById(R.id.channel_list);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        timeshiftBar.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        numberInput.setVisibility(View.GONE);
        findViewById(R.id.btn_back30).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controller.seekBack(30000);
                updateSeekBar();
                showToolbar();
            }
        });
        findViewById(R.id.btn_forward30).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controller.seekForward(30000);
                updateSeekBar();
                showToolbar();
            }
        });
        findViewById(R.id.btn_live).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controller.seekToLive();
                updateSeekBar();
                showToolbar();
            }
        });
        tsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = controller.duration();
                if (duration > 0) tsClock.setText(Fmt.duration(duration * progress / 1000) + " / " + Fmt.duration(duration));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekDragging = true;
                handler.removeCallbacks(seekTask);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekDragging = false;
                long duration = controller.duration();
                if (duration > 0) controller.seekTo(duration * seekBar.getProgress() / 1000);
                updateSeekBar();
                showToolbar();
            }
        });
        findViewById(R.id.btn_channels).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel();
            }
        });
        findViewById(R.id.btn_programs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPrograms();
            }
        });
        findViewById(R.id.btn_lines).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLines();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch();
            }
        });
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

    private void setupPlayer() {
        if (controller != null) controller.release();
        controller = new PlayerController(this, playerView, this);
        playerConfigToken = configToken();
    }

    /** 解码器与缓冲参数是 ExoPlayer 构造参数，变化后必须重建控制器。 */
    private int configToken() {
        return Store.playerEngine(this) * 1000000 + Store.decodeMode(this) * 100000 + Store.buffer(this);
    }

    private void setupLists() {
        groupList.setLayoutManager(new LinearLayoutManager(this));
        channelList.setLayoutManager(new LinearLayoutManager(this));
        groupAdapter = new GroupAdapter(this);
        channelAdapter = new ChannelAdapter(this);
        groupList.setAdapter(groupAdapter);
        channelList.setAdapter(channelAdapter);
        channelList.setItemAnimator(null);
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (panelVisible) hidePanel();
                else showPanel();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                showToolbar();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 90) {
                    switchChannel(dy < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) > 120 && !panelVisible) {
                    showPanel();
                    return true;
                }
                return false;
            }
        });
        touchLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }
        });
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null || !"livetv".equals(data.getScheme())) return;
        String url = data.getQueryParameter("url");
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();
        // 深链来自浏览器/其他 App，禁止借此读取本机任意文件
        String lower = url.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("content://"))) {
            Toast.makeText(this, R.string.unsupported_source_url, Toast.LENGTH_LONG).show();
            return;
        }
        List<LiveSource> sources = Store.sources(this);
        for (int i = 0; i < sources.size(); i++) {
            LiveSource existing = sources.get(i);
            if (existing.url != null && existing.url.trim().equals(url)) {
                Store.setActive(this, i);
                Store.bumpReload(this);
                return;
            }
        }
        String name = data.getQueryParameter("name");
        String epg = data.getQueryParameter("epg");
        LiveSource source = new LiveSource(name == null || name.trim().isEmpty() ? Fmt.host(url) : name.trim(), url);
        if (epg != null) source.epg = epg.trim();
        String ua = data.getQueryParameter("ua");
        if (ua != null) source.ua = ua.trim();
        int index = Store.add(this, source);
        Store.setActive(this, index);
        Store.bumpReload(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        reload();
    }

    private void reload() {
        if (playerConfigToken != configToken()) setupPlayer();
        reloadSeq++;
        searchKeyword = "";
        LiveSource source = Store.activeSource(this);
        loadedSource = source;
        loadedToken = Store.reloadToken(this);
        restoreGroup = playing == null ? "" : playing.group;
        restoreName = playing == null ? "" : playing.name;
        if (playing == null && source != null) {
            String last = Store.lastChannel(this, source);
            int separator = last.indexOf('|');
            if (separator > 0) {
                restoreGroup = last.substring(0, separator);
                restoreName = last.substring(separator + 1);
            }
        }
        if (source == null) {
            loading = false;
            hideLoading();
            showEmpty();
            return;
        }
        loading = true;
        showLoading(getString(R.string.loading));
        final int seq = reloadSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Playlist parsed = LiveLoader.load(MainActivity.this, source);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != reloadSeq) return;
                            applyPlaylist(parsed);
                        }
                    });
                } catch (Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != reloadSeq) return;
                            fail(e);
                        }
                    });
                }
            }
        }).start();
    }

    private void applyPlaylist(Playlist parsed) {
        if (isFinishing() || isDestroyed()) return;
        loading = false;
        hideLoading();
        if (parsed == null || parsed.isEmpty()) {
            clearPlayback();
            Toast.makeText(this, R.string.empty_playlist, Toast.LENGTH_LONG).show();
            hint.setText(R.string.empty_playlist);
            hint.setVisibility(View.VISIBLE);
            return;
        }
        playlist = parsed;
        sourcePromptShown = false;
        buildGroups();
        if (!parsed.warnings.isEmpty()) {
            String message = getString(R.string.source_warning, parsed.warnings.size(), parsed.warnings.get(0));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.empty_playlist, Toast.LENGTH_LONG).show();
            return;
        }
        Channel target = find(restoreGroup, restoreName);
        if (target == null) target = firstChannel();
        if (target == null) return;
        int gi = indexOfGroup(target);
        groupAdapter.setItems(groups, gi);
        selectGroup(gi);
        int ci = currentGroup == null ? -1 : currentGroup.channels.indexOf(target);
        playChannel(ci < 0 ? 0 : ci);
        loadEpg();
        hint.setText(R.string.hint_bar);
        if (isTv) hint.setVisibility(View.GONE);
        root.requestFocus();
        handler.removeCallbacks(clockTask);
        handler.post(clockTask);
    }

    private void fail(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        loading = false;
        hideLoading();
        clearPlayback();
        String message = e == null || e.getMessage() == null ? getString(R.string.load_failed) : getString(R.string.load_failed) + "：" + e.getMessage();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        if (loadedSource == null) showEmpty();
    }

    /** 刷新失败时不要留着上一份频道列表和画面，避免误以为刷新成功。 */
    private void clearPlayback() {
        playlist = null;
        groups.clear();
        currentGroup = null;
        searchGroup = null;
        searchKeyword = "";
        playing = null;
        controller.stop();
        groupAdapter.setItems(groups, 0);
        channelAdapter.setItems(new ArrayList<Channel>(), -1);
        hidePanel();
        hideToolbar();
        infoBar.setVisibility(View.GONE);
    }

    private void showEmpty() {
        hint.setText(R.string.no_source);
        hint.setVisibility(View.VISIBLE);
        // 启动后首次发现没有直播源时，弹窗引导用户去配置，而不是默默跳到设置页。
        if (!sourcePromptShown && !isFinishing() && !isDestroyed()) {
            sourcePromptShown = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.no_source_title)
                    .setMessage(R.string.no_source_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.go_config, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openSettings();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void loadEpg() {
        if (playlist == null || playlist.epgUrl == null || playlist.epgUrl.trim().isEmpty()) return;
        final String epgUrl = playlist.epgUrl.trim();
        final List<Channel> channels = playlist.allChannels();
        final int gen = ++epgGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EpgRepository.load(MainActivity.this, epgUrl, channels);
                } catch (Exception e) {
                    Log.w("LiveTV", "EPG load failed", e);
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || gen != epgGen || playlist == null) return;
                        updateInfo();
                        if (panelVisible) {
                            boolean focused = channelList.hasFocus();
                            channelAdapter.refresh();
                            if (focused) channelList.requestFocus();
                        }
                    }
                });
            }
        }).start();
    }

    private void buildGroups() {
        groups = new ArrayList<>();
        searchGroup = null;
        if (playlist == null) return;
        Set<String> hidden = Store.hiddenGroups(this);
        boolean showHidden = Store.showHiddenGroups(this);
        List<Channel> favorites = new ArrayList<>();
        for (Channel channel : playlist.allChannels()) {
            if (Store.isFavorite(this, channel)) favorites.add(channel);
        }
        if (!favorites.isEmpty()) {
            Group favorite = new Group(getString(R.string.group_favorites));
            favorite.channels.addAll(favorites);
            groups.add(favorite);
        }
        LanServer.get().update(playlist.allChannels());
        LanServer.get().setEpgUrl(playlist.epgUrl);
        if (Store.lanServer(this)) LanServer.get().start(playlist.allChannels());
        else LanServer.get().stop();
        for (Group group : playlist.groups) {
            if (group.isEmpty()) continue;
            if (!showHidden && hidden.contains(group.name)) continue;
            groups.add(group);
        }
        // 搜索结果要能跨收藏/隐藏分组操作后保留
        if (!searchKeyword.isEmpty()) {
            Group result = createSearchGroup(searchKeyword);
            if (!result.channels.isEmpty()) {
                groups.add(0, result);
                searchGroup = result;
            }
        }
    }

    private Group createSearchGroup(String keyword) {
        Group result = new Group(getString(R.string.search) + "：" + keyword);
        if (playlist == null) return result;
        String key = keyword.toLowerCase();
        for (Channel channel : playlist.allChannels()) {
            String name = channel.name == null ? "" : channel.name.toLowerCase();
            String tvgName = channel.tvgName == null ? "" : channel.tvgName.toLowerCase();
            if (name.contains(key) || tvgName.contains(key) || channel.number.equals(keyword)) {
                result.channels.add(channel);
            }
        }
        return result;
    }

    private Channel firstChannel() {
        for (Group group : groups) {
            if (!group.channels.isEmpty()) return group.channels.get(0);
        }
        return null;
    }

    private Channel find(String groupName, String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        for (Group group : groups) {
            for (Channel channel : group.channels) {
                if (channel.name.equals(channelName) && (groupName.isEmpty() || channel.group.equals(groupName) || group.name.equals(groupName))) {
                    return channel;
                }
            }
        }
        return null;
    }

    private int indexOfGroup(Channel channel) {
        int fallback = -1;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (group.channels.contains(channel)) {
                if (fallback < 0) fallback = i;
                if (group.name.equals(channel.group)) return i;
            }
        }
        return fallback < 0 ? 0 : fallback;
    }

    private void selectGroup(int index) {
        if (groups.isEmpty()) return;
        if (index < 0) index = 0;
        if (index >= groups.size()) index = groups.size() - 1;
        groupIndex = index;
        currentGroup = groups.get(index);
        groupAdapter.setSelected(index);
        List<Channel> channels = currentGroup.channels;
        int selected = channels.indexOf(playing);
        if (selected < 0) selected = channels.isEmpty() ? -1 : 0;
        channelIndex = selected;
        channelAdapter.setItems(channels, selected);
        groupList.scrollToPosition(index);
    }

    private void playChannel(int index) {
        if (currentGroup == null) return;
        if (index < 0 || index >= currentGroup.channels.size()) return;
        Channel channel = currentGroup.channels.get(index);
        channelIndex = index;
        channelAdapter.setSelected(index);
        playing = channel;
        hideToolbar();
        showLoading(channel.name);
        controller.play(channel);
        loadDanmaku(channel);
        Store.saveLastChannel(this, loadedSource, channel);
        updateInfo();
    }

    private void startDanmakuTicker() {
        handler.removeCallbacks(danmakuTickTask);
        handler.postDelayed(danmakuTickTask, 250);
    }

    private void loadDanmaku(Channel channel) {
        final int generation = ++danmakuGen;
        if (danmakuView == null) return;
        danmakuView.clear();
        if (channel == null || channel.danmaku == null || channel.danmaku.trim().isEmpty() || !Store.danmaku(this)) {
            danmakuView.setActive(false);
            return;
        }
        danmakuView.setActive(true);
        final String url = channel.danmaku.trim();
        final String ua = channel.ua;
        final java.util.Map<String, String> headers = channel.headers;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.util.List<DanmakuItem> items = DanmakuRepository.load(MainActivity.this, url, ua, headers);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation == danmakuGen && playing != null && playing.danmaku != null && playing.danmaku.equals(url)) {
                                danmakuView.setItems(items);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private void syncDanmaku() {
        if (danmakuView == null) return;
        if (!Store.danmaku(this)) {
            danmakuGen++;
            danmakuView.clear();
            danmakuView.setActive(false);
        } else if (playing != null && !danmakuView.hasItems() && playing.danmaku != null && !playing.danmaku.isEmpty()) {
            loadDanmaku(playing);
        }
    }

    private void switchChannel(int step) {
        List<Channel> list;
        if (playlist == null) return;
        if (Store.crossGroup(this)) list = playlist.allChannels();
        else list = currentGroup == null ? new ArrayList<Channel>() : currentGroup.channels;
        if (list.isEmpty()) return;
        int index = playing == null ? -1 : list.indexOf(playing);
        if (index < 0) index = 0;
        else index = (index + step + list.size()) % list.size();
        Channel channel = list.get(index);
        selectGroup(indexOfGroup(channel));
        int position = currentGroup == null ? -1 : currentGroup.channels.indexOf(channel);
        playChannel(position < 0 ? 0 : position);
    }

    private void updateInfo() {
        Channel channel = playing;
        if (channel == null) {
            infoBar.setVisibility(View.GONE);
            return;
        }
        // 播放界面保持干净：信息条默认隐藏，仅在用户按 INFO 键时显示。
        // 这里只更新内容，不主动改变可见性，避免切台/定时刷新时把信息条弹出来。
        chNumber.setText(channel.number);
        chName.setText(channel.name);
        EpgProgram now = channel.now();
        EpgProgram next = channel.next();
        chProgram.setText(now == null ? "" : now.title);
        chNext.setText(next == null ? "" : getString(R.string.next) + " " + Fmt.time(next.startTime) + " " + next.title);
        chClock.setText(Fmt.clock(System.currentTimeMillis()));
        progress.setVisibility(now == null ? View.INVISIBLE : View.VISIBLE);
        progress.setProgress(channel.progress());
    }

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingPanel.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingPanel.setVisibility(View.GONE);
    }

    private void showPanel() {
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.no_source, Toast.LENGTH_SHORT).show();
            return;
        }
        panelVisible = true;
        panel.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.GONE);
        toolbarVisible = false;
        if (currentGroup != null) channelAdapter.setItems(currentGroup.channels, channelIndex);
        groupAdapter.setSelected(groupIndex);
        groupList.scrollToPosition(groupIndex);
        groupList.post(new Runnable() {
            @Override
            public void run() {
                LinearLayoutManager manager = (LinearLayoutManager) groupList.getLayoutManager();
                int first = manager == null ? 0 : manager.findFirstVisibleItemPosition();
                View child = groupList.getChildAt(groupIndex - (first < 0 ? 0 : first));
                if (child != null) child.requestFocus();
                else groupList.requestFocus();
            }
        });
        hint.setText(R.string.hint_bar);
        hint.setVisibility(View.VISIBLE);
    }

    private void hidePanel() {
        panelVisible = false;
        panel.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        root.requestFocus();
    }

    private void showToolbar() {
        toolbar.setVisibility(View.VISIBLE);
        toolbarVisible = true;
        if (controller.isSeekable()) {
            timeshiftBar.setVisibility(View.VISIBLE);
            updateSeekBar();
        } else {
            timeshiftBar.setVisibility(View.GONE);
        }
        handler.removeCallbacks(hideToolbarTask);
        handler.postDelayed(hideToolbarTask, 5000);
        final View first = findViewById(R.id.btn_channels);
        if (first != null) first.post(new Runnable() {
            @Override
            public void run() {
                first.requestFocus();
            }
        });
        handler.removeCallbacks(seekTask);
        handler.post(seekTask);
    }

    private void hideToolbar() {
        toolbar.setVisibility(View.GONE);
        timeshiftBar.setVisibility(View.GONE);
        toolbarVisible = false;
        toolbar.clearFocus();
        handler.removeCallbacks(seekTask);
    }

    /** 刷新时移进度：直播边缘显示「直播」，回拖后显示落后时长。 */
    private void updateSeekBar() {
        if (controller == null) return;
        boolean seekable = controller.isSeekable();
        if (!seekable) {
            timeshiftBar.setVisibility(View.GONE);
            return;
        }
        if (!toolbarVisible) return;
        timeshiftBar.setVisibility(View.VISIBLE);
        long duration = controller.duration();
        long position = controller.position();
        if (duration <= 0) return;
        if (!seekDragging) {
            int progress = (int) (position * 1000 / duration);
            tsSeek.setProgress(Math.max(0, Math.min(1000, progress)));
        }
        long shown = seekDragging ? duration * tsSeek.getProgress() / 1000 : position;
        tsClock.setText(Fmt.duration(shown) + " / " + Fmt.duration(duration));
        long behind = Math.max(0, duration - position);
        if (behind < 3000) tsState.setText(R.string.live_now);
        else tsState.setText(getString(R.string.behind_live, Fmt.duration(behind)));
    }

    private void openPrograms() {
        Dialogs.programs(this, playing, new Dialogs.ProgramListener() {
            @Override
            public void onCatchup(EpgProgram program) {
                if (playing == null) return;
                controller.playCatchup(playing, program);
                showLoading(program.title);
            }
        });
    }

    private void openLines() {
        Dialogs.lines(this, playing, new Dialogs.LineListener() {
            @Override
            public void onLine(int index) {
                if (playing == null) return;
                playing.lineIndex = index;
                controller.play(playing);
                showLoading(playing.name);
            }
        });
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openSearch() {
        hideToolbar();
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.search_hint);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.search_channel)
                .setView(input)
                .setPositiveButton(R.string.search, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applySearch(input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applySearch(String keyword) {
        if (playlist == null || keyword == null) return;
        searchKeyword = keyword.trim();
        if (searchKeyword.isEmpty()) {
            buildGroups();
            groupAdapter.setItems(groups, 0);
            return;
        }
        if (createSearchGroup(searchKeyword).channels.isEmpty()) {
            Toast.makeText(this, R.string.search_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        buildGroups();
        groupAdapter.setItems(groups, 0);
        selectGroup(0);
        showPanel();
    }

    private void toggleFavorite(Channel channel) {
        if (channel == null) return;
        Store.toggleFavorite(this, channel);
        String groupName = currentGroup == null ? "" : currentGroup.name;
        buildGroups();
        int index = 0;
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).name.equals(groupName)) index = i;
        groupAdapter.setItems(groups, index);
        selectGroup(index);
        channelAdapter.refresh();
        Toast.makeText(this, Store.isFavorite(this, channel) ? R.string.favorite_add : R.string.favorite_remove, Toast.LENGTH_SHORT).show();
    }

    private void appendNumber(int digit) {
        number += digit;
        if (number.length() > 3) number = number.substring(number.length() - 3);
        numberInput.setText(number);
        numberInput.setVisibility(View.VISIBLE);
        handler.removeCallbacks(numberJumpTask);
        handler.postDelayed(numberJumpTask, 1500);
    }

    private void jumpByNumber() {
        String input = number;
        number = "";
        numberInput.setVisibility(View.GONE);
        handler.removeCallbacks(numberJumpTask);
        if (input.isEmpty() || playlist == null) return;
        List<Channel> list = Store.crossGroup(this) ? playlist.allChannels() : (currentGroup == null ? new ArrayList<Channel>() : currentGroup.channels);
        Channel match = null;
        for (Channel channel : list) {
            if (channel.number.equals(input) || channel.number.equalsIgnoreCase("0" + input)) {
                match = channel;
                break;
            }
        }
        if (match == null) {
            for (Channel channel : list) {
                if (channel.name.startsWith(input)) {
                    match = channel;
                    break;
                }
            }
        }
        if (match == null) {
            Toast.makeText(this, "未找到频道 " + input, Toast.LENGTH_SHORT).show();
            return;
        }
        selectGroup(indexOfGroup(match));
        int position = currentGroup == null ? -1 : currentGroup.channels.indexOf(match);
        if (position >= 0) playChannel(position);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();

        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_UP) openSettings();
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && isDigit(code)) {
            appendNumber(digitOf(code));
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DEL && numberInput.getVisibility() == View.VISIBLE) {
            if (!number.isEmpty()) number = number.substring(0, number.length() - 1);
            if (number.isEmpty()) {
                numberInput.setVisibility(View.GONE);
                handler.removeCallbacks(numberJumpTask);
            } else {
                numberInput.setText(number);
            }
            return true;
        }
        // INFO 只管顶部信息条，且要在 panel/toolbar 的提前返回之前处理
        if (code == KeyEvent.KEYCODE_INFO && action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(hideInfoTask);
            if (infoBar.getVisibility() == View.VISIBLE) {
                infoBar.setVisibility(View.GONE);
            } else {
                infoBar.setVisibility(View.VISIBLE);
                handler.postDelayed(hideInfoTask, 6000);
            }
            return true;
        }
        if (panelVisible) return super.dispatchKeyEvent(event);

        if (action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (controller.isPlaying()) {
                    controller.pause();
                    showToolbar();
                } else {
                    controller.resume();
                    updateSeekBar();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (controller.isSeekable()) controller.seekBack(30000);
                showToolbar();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (controller.isSeekable()) controller.seekForward(30000);
                showToolbar();
                return true;
            }
        }

        if (toolbarVisible) return super.dispatchKeyEvent(event);

        if (action == KeyEvent.ACTION_DOWN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    switchChannel(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    switchChannel(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showPanel();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() == 0) {
                        okLong = false;
                    } else if (!okLong) {
                        okLong = true;
                        openPrograms();
                    }
                    return true;
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (!okLong) showPanel();
                okLong = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isDigit(int code) {
        return (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                || (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9);
    }

    private int digitOf(int code) {
        if (code >= KeyEvent.KEYCODE_NUMPAD_0) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return code - KeyEvent.KEYCODE_0;
    }

    @Override
    public void onBackPressed() {
        if (panelVisible) {
            hidePanel();
            return;
        }
        if (numberInput.getVisibility() == View.VISIBLE) {
            number = "";
            numberInput.setVisibility(View.GONE);
            handler.removeCallbacks(numberJumpTask);
            return;
        }
        if (toolbarVisible) {
            hideToolbar();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
            return;
        }
        lastBack = now;
        Toast.makeText(this, R.string.exit_confirm, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onGroupClick(int position) {
        selectGroup(position);
    }

    @Override
    public void onGroupLongClick(int position) {
        if (position < 0 || position >= groups.size()) return;
        final Group group = groups.get(position);
        if (group == searchGroup) return;
        if (group.name.equals(getString(R.string.group_favorites))) return;
        final boolean hidden = Store.hiddenGroups(this).contains(group.name);
        Dialogs.groupMenu(this, group.name, hidden, new Dialogs.GroupListener() {
            @Override
            public void onToggleHidden() {
                Store.setGroupHidden(MainActivity.this, group.name, !hidden);
                buildGroups();
                groupAdapter.setItems(groups, 0);
                selectGroup(0);
                Toast.makeText(MainActivity.this, hidden ? R.string.hidden_group : R.string.group_restored, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onGroupFocus(int position) {
        if (position == groupIndex) return;
        if (position < 0 || position >= groups.size()) return;
        groupIndex = position;
        currentGroup = groups.get(position);
        List<Channel> channels = currentGroup.channels;
        int selected = channels.indexOf(playing);
        if (selected < 0) selected = channels.isEmpty() ? -1 : 0;
        channelIndex = selected;
        channelAdapter.setItems(channels, selected);
    }

    @Override
    public void onChannelClick(int position) {
        playChannel(position);
        if (!isTv) hidePanel();
    }

    @Override
    public void onChannelLongClick(int position) {
        if (currentGroup == null) return;
        Channel channel = currentGroup.channels.get(position);
        Dialogs.channelMenu(this, channel, Store.isFavorite(this, channel), new Dialogs.MenuListener() {
            @Override
            public void onFavorite() {
                toggleFavorite(channel);
            }

            @Override
            public void onLines() {
                Dialogs.lines(MainActivity.this, channel, new Dialogs.LineListener() {
                    @Override
                    public void onLine(int index) {
                        channel.lineIndex = index;
                        if (channel == playing) {
                            controller.play(channel);
                            showLoading(channel.name);
                        }
                    }
                });
            }

            @Override
            public void onPrograms() {
                Dialogs.programs(MainActivity.this, channel, new Dialogs.ProgramListener() {
                    @Override
            public void onCatchup(EpgProgram program) {
                selectGroup(indexOfGroup(channel));
                int position = currentGroup == null ? -1 : currentGroup.channels.indexOf(channel);
                channelIndex = Math.max(0, position);
                channelAdapter.setSelected(channelIndex);
                playing = channel;
                Store.saveLastChannel(MainActivity.this, loadedSource, channel);
                updateInfo();
                controller.playCatchup(channel, program);
                showLoading(program.title);
            }
                });
            }
        });
    }

    @Override
    public void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) hideLoading();
        if (toolbarVisible) updateSeekBar();
        updateInfo();
    }

    @Override
    public void onError(String message) {
        hideLoading();
        Toast.makeText(this, getString(R.string.play_error) + (message == null || message.isEmpty() ? "" : "：" + message), Toast.LENGTH_LONG).show();
    }

    private void applyAspect() {
        if (controller == null) return;
        controller.applyAspect(Store.aspect(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        applyAspect();
        startDanmakuTicker();
        syncDanmaku();
        if (Store.activeSource(this) == null) {
            if (!loading) showEmpty();
            return;
        }
        if (!loading && (loadedSource == null || !sameSource(loadedSource) || loadedToken != Store.reloadToken(this) || playlist == null)) {
            reload();
        } else if (playing != null && !controller.isPlaying()) {
            controller.resume();
        }
        if (playing != null) {
            handler.removeCallbacks(clockTask);
            handler.post(clockTask);
        }
    }

    private boolean sameSource(LiveSource source) {
        LiveSource active = Store.activeSource(this);
        if (active == null) return false;
        return active.key().equals(source.key()) && active.url.equals(source.url);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(clockTask);
        if (playing != null) Store.saveLastChannel(this, loadedSource, playing);
        controller.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        controller.release();
        // 退出即停止服务，避免开关开启时残留 8899 端口监听
        LanServer.get().stop();
        super.onDestroy();
    }
}






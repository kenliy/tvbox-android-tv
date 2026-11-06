package com.tvbox.desktop.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.ResolvingDataSource;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.video.VideoSize;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final long BUFFER_SWITCH_MS = 2_000L;
    private static final long SEEK_STEP_MS = 15_000L;
    private static final long DEFAULT_INTRO_SKIP_MS = 90_000L;
    private static final long AD_SKIP_MS = 60_000L;
    private static final long MEDIA_CACHE_BYTES = 512L * 1024L * 1024L;
    private static final long INITIAL_PREBUFFER_MS = 8_000L;
    private static final long SWITCH_PREBUFFER_MS = 3_000L;
    private static final long PREBUFFER_TIMEOUT_MS = 10_000L;
    private static final long PLAYER_OVERLAY_HIDE_MS = 4_000L;

    private enum Screen { HOME, SEARCH, DETAIL, PLAYER }
    private enum QualityMode {
        AUTO("自动", Integer.MAX_VALUE, Integer.MAX_VALUE, false),
        P720("720P", 1280, 720, true),
        P1080("1080P", 1920, 1080, true),
        P4K("4K", 3840, 2160, true);

        final String label;
        final int maxWidth;
        final int maxHeight;
        final boolean highestWithinLimit;

        QualityMode(String label, int maxWidth, int maxHeight, boolean highestWithinLimit) {
            this.label = label;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.highestWithinLimit = highestWithinLimit;
        }
    }

    private final CmsRepository repository = new CmsRepository();
    private final ImageLoader images = new ImageLoader();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout root;
    private Screen screen = Screen.HOME;
    private Models.Detail currentDetail;
    private int selectedLine;
    private int selectedEpisode;
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private LinearLayout playerOverlay;
    private TextView playerStatus;
    private TextView playerTitle;
    private ProgressBar playerProgress;
    private TextView rewindButton;
    private TextView forwardButton;
    private TextView skipIntroButton;
    private TextView skipAdButton;
    private TextView qualityButton;
    private DefaultTrackSelector trackSelector;
    private SimpleCache mediaCache;
    private StandaloneDatabaseProvider cacheDatabase;
    private QualityMode qualityMode = QualityMode.AUTO;
    private String activeSiteName = "";
    private String activeLineName = "";
    private int activeVideoWidth;
    private int activeVideoHeight;
    private Models.Episode activeEpisode;
    private String activeMediaUrl = "";
    private long bufferingStartedAt;
    private long prebufferStartedAt;
    private long prebufferTargetMs = INITIAL_PREBUFFER_MS;
    private boolean playbackStarted;
    private boolean waitingForPrebuffer;
    private boolean manualLineSelected;
    private boolean autoSwitching;
    private boolean alternativesLoaded;
    private int consumedRevealKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private String requestedEpisodeName = "";
    private volatile String activeRefererApi = "";
    private final List<Models.Detail> playbackAlternatives = new ArrayList<>();
    private final Set<String> failedMediaUrls = new HashSet<>();

    private final Runnable hidePlayerOverlay = () -> {
        if (screen == Screen.PLAYER && playbackStarted && !waitingForPrebuffer
                && playerOverlay != null) {
            playerOverlay.setVisibility(View.GONE);
            if (playerView != null) playerView.requestFocus();
        }
    };

    private final Runnable prebufferStarter = new Runnable() {
        @Override public void run() {
            if (screen != Screen.PLAYER || player == null || !waitingForPrebuffer) return;
            long bufferedMs = player.getTotalBufferedDuration();
            long elapsedMs = System.currentTimeMillis() - prebufferStartedAt;
            if (bufferedMs >= prebufferTargetMs
                    || (player.getPlaybackState() == Player.STATE_READY
                    && elapsedMs >= PREBUFFER_TIMEOUT_MS && bufferedMs >= 1_000L)) {
                waitingForPrebuffer = false;
                playbackStarted = true;
                bufferingStartedAt = 0L;
                autoSwitching = false;
                player.setPlayWhenReady(true);
                if (playerProgress != null) playerProgress.setVisibility(View.GONE);
                updatePlayingStatus();
                showPlayerOverlay(true);
                return;
            }
            if (elapsedMs >= PREBUFFER_TIMEOUT_MS) {
                waitingForPrebuffer = false;
                autoSwitching = false;
                switchToNextLine("预缓存超时，已保持进度自动换线");
                return;
            }
            if (playerStatus != null) {
                playerStatus.setText(activeSiteName + " · " + activeLineName
                        + " · 正在预缓存 " + Math.min(prebufferTargetMs, bufferedMs) / 1_000L
                        + "/" + prebufferTargetMs / 1_000L + "秒");
            }
            handler.postDelayed(this, 200L);
        }
    };

    private final Runnable bufferWatchdog = new Runnable() {
        @Override public void run() {
            if (screen == Screen.PLAYER && player != null
                    && player.getPlaybackState() == Player.STATE_BUFFERING
                    && bufferingStartedAt > 0L) {
                long bufferingMs = System.currentTimeMillis() - bufferingStartedAt;
                if (waitingForPrebuffer && playerStatus != null) {
                    long bufferedSeconds = Math.min(prebufferTargetMs,
                            player.getTotalBufferedDuration()) / 1_000L;
                    playerStatus.setText(activeSiteName + " · " + activeLineName
                            + " · 正在预缓存 " + bufferedSeconds + "/"
                            + prebufferTargetMs / 1_000L + "秒");
                } else if (playbackStarted && bufferingMs >= BUFFER_SWITCH_MS) {
                    switchToNextLine("缓冲超过2秒，已保持进度自动换线");
                }
            }
            handler.postDelayed(this, 250L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0D1116"));
        setContentView(root);
        initializeMediaCache();
        handler.post(bufferWatchdog);
        showHome();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        repository.shutdown();
        images.shutdown();
        if (mediaCache != null) {
            try {
                mediaCache.release();
            } catch (Exception ignored) {
                // Cache release failure must not block Activity teardown.
            }
            mediaCache = null;
        }
        cacheDatabase = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (screen == Screen.PLAYER) {
            releasePlayer();
            if (currentDetail != null) showDetail(currentDetail);
            else showHome();
        } else if (screen == Screen.DETAIL || screen == Screen.SEARCH) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP
                && event.getKeyCode() == consumedRevealKeyCode) {
            consumedRevealKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (screen == Screen.PLAYER && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0 && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            boolean wasHidden = playerOverlay == null
                    || playerOverlay.getVisibility() != View.VISIBLE;
            showPlayerOverlay(true);
            if (wasHidden && consumesFirstRevealPress(event.getKeyCode())) {
                consumedRevealKeyCode = event.getKeyCode();
                return true;
            }
            if (!wasHidden && !isPlayerControlFocused()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                        || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND) {
                    seekBy(-SEEK_STEP_MS);
                    consumedRevealKeyCode = event.getKeyCode();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                        || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                    seekBy(SEEK_STEP_MS);
                    consumedRevealKeyCode = event.getKeyCode();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                    qualityButton.requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isPlayerControlFocused() {
        return qualityButton != null && (qualityButton.hasFocus()
                || rewindButton.hasFocus() || forwardButton.hasFocus()
                || skipIntroButton.hasFocus() || skipAdButton.hasFocus());
    }

    private void showHome() {
        screen = Screen.HOME;
        currentDetail = null;
        root.removeAllViews();
        LinearLayout page = page();
        page.addView(topBar("TVBox 电视版", "Android TV · 六源聚合 · 荐片15线路 · 自动换线", true));

        TextView loading = status("正在读取影视源…", false);
        page.addView(loading);
        repository.loadHome((sections, error) -> {
            if (screen != Screen.HOME) return;
            page.removeView(loading);
            if (sections == null || sections.isEmpty()) {
                page.addView(status(error == null ? "影视源暂时没有返回内容" : error, true));
                return;
            }
            View first = null;
            for (Models.HomeSection section : sections) {
                View item = addVodSection(page, section.title, section.items, section.error);
                if (first == null && item != null) first = item;
            }
            if (first != null) first.requestFocus();
        });
        root.addView(wrap(page));
    }

    private void showSearch(String initial) {
        screen = Screen.SEARCH;
        root.removeAllViews();
        LinearLayout page = page();
        page.addView(topBar("搜索", "六个影视源同时搜索，单个源失败不会影响其它结果", false));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setGravity(Gravity.CENTER_VERTICAL);
        form.setPadding(0, 0, 0, TvUi.dp(this, 18));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial == null ? "" : initial);
        input.setTextSize(20);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#76838E"));
        input.setHint("输入片名，按确认搜索");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(TvUi.selector(this, 8, "#171E26", "#25313B", "#4ED7BA", 2));
        input.setPadding(TvUi.dp(this, 18), 0, TvUi.dp(this, 18), 0);
        form.addView(input, new LinearLayout.LayoutParams(0, TvUi.dp(this, 62), 1f));
        TextView button = TvUi.button(this, "搜索");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                TvUi.dp(this, 150), TvUi.dp(this, 62));
        buttonParams.leftMargin = TvUi.dp(this, 14);
        form.addView(button, buttonParams);
        page.addView(form);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View.OnClickListener run = view -> runSearch(input.getText().toString(), results, input);
        button.setOnClickListener(run);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                run.onClick(view);
                return true;
            }
            return false;
        });
        root.addView(wrap(page));
        input.requestFocus();
    }

    private void runSearch(String query, LinearLayout results, EditText input) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            input.setError("请输入片名");
            return;
        }
        results.removeAllViews();
        TextView loading = status("正在搜索“" + keyword + "”…", false);
        results.addView(loading);
        repository.search(keyword, (items, error) -> {
            if (screen != Screen.SEARCH) return;
            results.removeAllViews();
            if (items == null || items.isEmpty()) {
                results.addView(status(error == null ? "没有找到结果" : error, true));
                return;
            }
            addVodSection(results, "搜索结果 · " + items.size() + " 条", items, null);
        });
    }

    private void loadDetail(Models.Vod vod) {
        screen = Screen.DETAIL;
        root.removeAllViews();
        LinearLayout page = page();
        page.addView(topBar(vod.name, vod.site.name + " · 正在读取详情", false));
        page.addView(status("正在加载线路与选集…", false));
        root.addView(wrap(page));
        repository.loadDetail(vod, (detail, error) -> {
            if (screen != Screen.DETAIL) return;
            if (detail == null) {
                root.removeAllViews();
                LinearLayout failed = page();
                failed.addView(topBar(vod.name, vod.site.name, false));
                failed.addView(status("详情加载失败：" + error, true));
                root.addView(wrap(failed));
                return;
            }
            currentDetail = detail;
            selectedEpisode = 0;
            repository.rankPlaybackLines(detail, null, 0, (ranked, probeError) -> {
                if (screen != Screen.DETAIL || ranked == null) return;
                currentDetail = ranked;
                selectedLine = firstHealthyLine(ranked, null, 0);
                manualLineSelected = false;
                showDetail(ranked);
            });
        });
    }

    private void showDetail(Models.Detail detail) {
        screen = Screen.DETAIL;
        currentDetail = detail;
        root.removeAllViews();
        LinearLayout page = page();
        page.addView(topBar(detail.name, detail.site.name + detailLine(" · ", detail.remarks), false));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.TOP);
        hero.setPadding(0, 0, 0, TvUi.dp(this, 22));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(TvUi.shape(this, 6, "#171E26", "#25313B", 1));
        hero.addView(poster, new LinearLayout.LayoutParams(TvUi.dp(this, 230), TvUi.dp(this, 322)));
        images.load(detail.pic, poster);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(TvUi.dp(this, 26), 0, 0, 0);
        TextView title = TvUi.text(this, detail.name, 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        meta.addView(title);
        meta.addView(TvUi.text(this, join(" · ", detail.typeName, detail.year, detail.area, detail.score),
                17, Color.parseColor("#4ED7BA")), marginTop(48, 17, 8));
        if (!detail.actor.isEmpty()) {
            TextView actor = TvUi.text(this, "主演：" + detail.actor, 16, Color.parseColor("#AAB5BF"));
            actor.setMaxLines(2);
            meta.addView(actor, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 54, 10));
        }
        TextView description = TvUi.text(this, detail.content.isEmpty() ? "暂无剧情简介" : detail.content,
                16, Color.parseColor("#AAB5BF"));
        description.setMaxLines(5);
        description.setLineSpacing(0, 1.18f);
        meta.addView(description, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 116, 10));
        hero.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(hero);

        if (detail.lines.isEmpty()) {
            page.addView(status("这个影视源没有返回可播放线路，请返回换一个片源。", true));
            root.addView(wrap(page));
            return;
        }

        TextView lineTitle = sectionTitle("播放线路");
        page.addView(lineTitle);
        LinearLayout lineRow = horizontalRow();
        for (int index = 0; index < detail.lines.size(); index++) {
            final int lineIndex = index;
            Models.Line line = detail.lines.get(index);
            String health = line.probeFailed ? " · 待验证"
                    : line.probeLatencyMs >= 0 ? " · " + line.probeLatencyMs + "ms" : "";
            if (index == 0 && !line.probeFailed) health = " · 推荐" + health;
            TextView choice = TvUi.button(this, line.name + "  " + line.episodes.size() + "集" + health);
            if (index == selectedLine) choice.setText("✓ " + choice.getText());
            choice.setOnClickListener(view -> {
                selectedLine = lineIndex;
                selectedEpisode = 0;
                manualLineSelected = true;
                showDetail(detail);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 56));
            lp.rightMargin = TvUi.dp(this, 12);
            lineRow.addView(choice, lp);
        }
        page.addView(horizontalScroll(lineRow));

        TextView episodeTitle = sectionTitle("选集 · " + detail.lines.get(selectedLine).name);
        page.addView(episodeTitle, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 44, 18));
        LinearLayout episodes = new LinearLayout(this);
        episodes.setOrientation(LinearLayout.HORIZONTAL);
        List<Models.Episode> list = detail.lines.get(selectedLine).episodes;
        View firstEpisode = null;
        for (int index = 0; index < list.size(); index++) {
            final int episodeIndex = index;
            TextView item = TvUi.button(this, list.get(index).name);
            item.setOnClickListener(view -> {
                if (manualLineSelected) {
                    play(detail, selectedLine, episodeIndex, null);
                    return;
                }
                String episodeName = list.get(episodeIndex).name;
                item.setText("正在测速…");
                repository.rankPlaybackLines(detail, episodeName, episodeIndex, (ranked, error) -> {
                    if (screen != Screen.DETAIL || ranked == null) return;
                    int bestLine = firstHealthyLine(ranked, episodeName, episodeIndex);
                    int bestEpisode = findEpisode(ranked.lines.get(bestLine), episodeName, episodeIndex);
                    if (bestEpisode < 0) {
                        item.setText(episodeName);
                        return;
                    }
                    selectedLine = bestLine;
                    play(ranked, bestLine, bestEpisode, null);
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(TvUi.dp(this, 150), TvUi.dp(this, 56));
            lp.rightMargin = TvUi.dp(this, 10);
            episodes.addView(item, lp);
            if (firstEpisode == null) firstEpisode = item;
        }
        page.addView(horizontalScroll(episodes));
        root.addView(wrap(page));
        if (firstEpisode != null) firstEpisode.requestFocus();
    }

    private void play(Models.Detail detail, int lineIndex, int episodeIndex, String message) {
        if (message == null) {
            failedMediaUrls.clear();
            playbackAlternatives.clear();
            alternativesLoaded = false;
            requestedEpisodeName = detail.lines.get(lineIndex).episodes
                    .get(Math.min(episodeIndex, detail.lines.get(lineIndex).episodes.size() - 1)).name;
        }
        releasePlayer();
        screen = Screen.PLAYER;
        currentDetail = detail;
        selectedLine = lineIndex;
        selectedEpisode = episodeIndex;
        bufferingStartedAt = 0L;
        playbackStarted = false;
        consumedRevealKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        waitingForPrebuffer = false;
        root.removeAllViews();

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        playerView = new StyledPlayerView(this);
        playerView.setId(View.generateViewId());
        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setFocusable(true);
        stage.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        playerOverlay = new LinearLayout(this);
        playerOverlay.setOrientation(LinearLayout.VERTICAL);
        playerOverlay.setGravity(Gravity.BOTTOM);
        playerOverlay.setPadding(TvUi.dp(this, 46), TvUi.dp(this, 30), TvUi.dp(this, 46), TvUi.dp(this, 34));
        playerOverlay.setBackground(TvUi.shape(this, 0, "#3D000000", "#00000000", 0));
        playerTitle = TvUi.text(this, "", 24, Color.WHITE);
        playerTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playerStatus = TvUi.text(this, "", 16, Color.parseColor("#F5C451"));
        playerProgress = new ProgressBar(this);
        playerProgress.setIndeterminate(true);
        LinearLayout controls = horizontalRow();
        rewindButton = playerControl("后退15秒", view -> seekBy(-SEEK_STEP_MS));
        forwardButton = playerControl("快进15秒", view -> seekBy(SEEK_STEP_MS));
        skipIntroButton = playerControl("跳过片头", view -> skipIntro());
        skipAdButton = playerControl("跳过广告", view -> seekBy(AD_SKIP_MS));
        qualityButton = TvUi.button(this, "清晰度：" + qualityMode.label);
        qualityButton.setId(View.generateViewId());
        qualityButton.setContentDescription("清晰度 " + qualityMode.label);
        qualityButton.setOnClickListener(view -> cycleQualityMode());
        playerView.setNextFocusDownId(qualityButton.getId());
        qualityButton.setNextFocusUpId(playerView.getId());
        playerView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                qualityButton.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekBy(-SEEK_STEP_MS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekBy(SEEK_STEP_MS);
                return true;
            }
            return false;
        });
        playerOverlay.addView(playerTitle);
        playerOverlay.addView(playerStatus, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 42, 8));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(TvUi.dp(this, 42), TvUi.dp(this, 42));
        progressParams.topMargin = TvUi.dp(this, 8);
        playerOverlay.addView(playerProgress, progressParams);
        addPlayerControl(controls, qualityButton, 190);
        addPlayerControl(controls, rewindButton, 165);
        addPlayerControl(controls, forwardButton, 165);
        addPlayerControl(controls, skipIntroButton, 165);
        addPlayerControl(controls, skipAdButton, 165);
        playerOverlay.addView(controls, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 52, 8));
        stage.addView(playerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage);

        Models.Line line = detail.lines.get(lineIndex);
        Models.Episode episode = line.episodes.get(Math.min(episodeIndex, line.episodes.size() - 1));
        activeEpisode = episode;
        activeMediaUrl = episode.url;
        activeSiteName = detail.site.name;
        activeLineName = line.name;
        activeRefererApi = detail.site.api;
        activeVideoWidth = 0;
        activeVideoHeight = 0;
        playerTitle.setText(detail.name + " · " + episode.name);
        playerStatus.setText(message == null
                ? detail.site.name + " · " + line.name + " · 正在连接" : message);

        if (!isDirectMedia(episode.url)) {
            switchToNextLine("当前线路不是媒体直链，正在换线");
            return;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 8_000, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        trackSelector = new DefaultTrackSelector(this);
        applyQualityMode();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 8.0; Android TV) TVBoxAndroid/0.1")
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory baseDataSource = new DefaultDataSource.Factory(this, http);
        DataSource.Factory upstream = new ResolvingDataSource.Factory(baseDataSource, dataSpec ->
                dataSpec.withAdditionalHeaders(defaultHeaders(activeRefererApi)));
        DataSource.Factory data = upstream;
        if (mediaCache != null) {
            data = new CacheDataSource.Factory()
                    .setCache(mediaCache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(SEEK_STEP_MS)
                .setSeekForwardIncrementMs(SEEK_STEP_MS)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingStartedAt == 0L) bufferingStartedAt = System.currentTimeMillis();
                    playerProgress.setVisibility(View.VISIBLE);
                    showPlayerOverlay(false);
                    if (playbackStarted && !waitingForPrebuffer) {
                        playerStatus.setText(activeSiteName + " · " + activeLineName + " · 正在缓冲");
                    }
                } else if (state == Player.STATE_READY) {
                    bufferingStartedAt = 0L;
                    if (!waitingForPrebuffer) autoSwitching = false;
                    if (playbackStarted && !waitingForPrebuffer) {
                        playerProgress.setVisibility(View.GONE);
                        updatePlayingStatus();
                        showPlayerOverlay(true);
                    }
                } else if (state == Player.STATE_ENDED) {
                    playerProgress.setVisibility(View.GONE);
                    playerStatus.setText("本集播放完成");
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                waitingForPrebuffer = false;
                autoSwitching = false;
                switchToNextLine("线路播放失败，已自动换下一条");
            }

            @Override public void onVideoSizeChanged(VideoSize videoSize) {
                activeVideoWidth = videoSize.width;
                activeVideoHeight = videoSize.height;
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    updatePlayingStatus();
                }
            }
        });
        player.setMediaItem(MediaItem.fromUri(episode.url));
        startPrebuffer(INITIAL_PREBUFFER_MS);
        playerView.requestFocus();
    }

    private void switchToNextLine(String reason) {
        if (autoSwitching || currentDetail == null || currentDetail.lines.isEmpty()) return;
        autoSwitching = true;
        long resumePositionMs = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
        if (!activeMediaUrl.isEmpty()) failedMediaUrls.add(activeMediaUrl);
        int count = currentDetail.lines.size();
        for (int step = 1; step <= count; step++) {
            int next = (selectedLine + step) % count;
            Models.Line candidate = currentDetail.lines.get(next);
            if (candidate.episodes.isEmpty()) continue;
            int episodeIndex = findEpisode(candidate, requestedEpisodeName, selectedEpisode);
            if (episodeIndex < 0) continue;
            String url = candidate.episodes.get(episodeIndex).url;
            if (!isDirectMedia(url) || failedMediaUrls.contains(url)) continue;
            if (player == null) {
                autoSwitching = false;
                play(currentDetail, next, episodeIndex, reason + "：" + candidate.name);
            } else {
                switchMediaInPlayer(currentDetail, next, episodeIndex, reason, resumePositionMs);
            }
            return;
        }
        if (tryPlaybackAlternatives(reason)) return;
        if (!alternativesLoaded) {
            alternativesLoaded = true;
            if (playerStatus != null) playerStatus.setText(reason + "，正在查找其它影视源…");
            final String title = currentDetail.name;
            repository.loadPlaybackAlternatives(title, (details, error) -> {
                if (screen != Screen.PLAYER || currentDetail == null
                        || !title.equals(currentDetail.name)) return;
                if (details != null) playbackAlternatives.addAll(details);
                autoSwitching = false;
                if (tryPlaybackAlternatives("当前源不可用，已自动切换影视源")) return;
                if (playerProgress != null) playerProgress.setVisibility(View.GONE);
                if (playerStatus != null) playerStatus.setText(error == null
                        ? "所有影视源的这一集暂时都不可用" : error);
            });
            return;
        }
        autoSwitching = false;
        if (playerProgress != null) playerProgress.setVisibility(View.GONE);
        if (playerStatus != null) playerStatus.setText("所有影视源的这一集暂时都不可用");
    }

    private boolean tryPlaybackAlternatives(String reason) {
        long resumePositionMs = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
        for (Models.Detail detail : playbackAlternatives) {
            if (detail == null || detail.lines.isEmpty()) continue;
            for (int lineIndex = 0; lineIndex < detail.lines.size(); lineIndex++) {
                Models.Line line = detail.lines.get(lineIndex);
                if (line.episodes.isEmpty()) continue;
                int episodeIndex = findEpisode(line, requestedEpisodeName, selectedEpisode);
                if (episodeIndex < 0) continue;
                String url = line.episodes.get(episodeIndex).url;
                if (!isDirectMedia(url) || failedMediaUrls.contains(url)) continue;
                if (player == null) {
                    autoSwitching = false;
                    play(detail, lineIndex, episodeIndex, reason + "：" + detail.site.name);
                } else {
                    switchMediaInPlayer(detail, lineIndex, episodeIndex, reason, resumePositionMs);
                }
                return true;
            }
        }
        return false;
    }

    private void switchMediaInPlayer(Models.Detail detail, int lineIndex, int episodeIndex,
                                     String reason, long resumePositionMs) {
        Models.Line line = detail.lines.get(lineIndex);
        Models.Episode episode = line.episodes.get(episodeIndex);
        currentDetail = detail;
        selectedLine = lineIndex;
        selectedEpisode = episodeIndex;
        activeEpisode = episode;
        activeMediaUrl = episode.url;
        activeSiteName = detail.site.name;
        activeLineName = line.name;
        activeRefererApi = detail.site.api;
        activeVideoWidth = 0;
        activeVideoHeight = 0;
        bufferingStartedAt = 0L;
        if (playerTitle != null) playerTitle.setText(detail.name + " · " + episode.name);
        if (playerStatus != null) {
            playerStatus.setText(reason + "：" + detail.site.name + " · " + line.name);
        }
        showPlayerOverlay(false);
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(episode.url), resumePositionMs);
        startPrebuffer(SWITCH_PREBUFFER_MS);
    }

    private void startPrebuffer(long targetMs) {
        if (player == null) return;
        prebufferTargetMs = targetMs;
        prebufferStartedAt = System.currentTimeMillis();
        waitingForPrebuffer = true;
        bufferingStartedAt = 0L;
        player.setPlayWhenReady(false);
        player.prepare();
        if (playerProgress != null) playerProgress.setVisibility(View.VISIBLE);
        showPlayerOverlay(false);
        handler.removeCallbacks(prebufferStarter);
        handler.post(prebufferStarter);
    }

    private void initializeMediaCache() {
        File cacheDirectory = new File(getCacheDir(), "media");
        try {
            if (SimpleCache.isCacheFolderLocked(cacheDirectory)) return;
            cacheDatabase = new StandaloneDatabaseProvider(this);
            mediaCache = new SimpleCache(cacheDirectory,
                    new LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES), cacheDatabase);
        } catch (Exception ignored) {
            mediaCache = null;
            cacheDatabase = null;
        }
    }

    private static int firstHealthyLine(Models.Detail detail, String episodeName, int fallback) {
        int directFallback = -1;
        for (int index = 0; index < detail.lines.size(); index++) {
            Models.Line line = detail.lines.get(index);
            int episodeIndex = findEpisode(line, episodeName, fallback);
            if (episodeIndex < 0) continue;
            String url = line.episodes.get(episodeIndex).url;
            if (!isDirectMedia(url)) continue;
            if (directFallback < 0) directFallback = index;
            if (!line.probeFailed) return index;
        }
        return directFallback >= 0 ? directFallback : firstDirectLine(detail);
    }

    private TextView playerControl(String label, View.OnClickListener listener) {
        TextView control = TvUi.button(this, label);
        control.setId(View.generateViewId());
        control.setContentDescription(label);
        control.setOnClickListener(listener);
        control.setNextFocusUpId(playerView.getId());
        return control;
    }

    private void addPlayerControl(LinearLayout controls, TextView control, int widthDp) {
        if (controls.getChildCount() > 0) {
            View previous = controls.getChildAt(controls.getChildCount() - 1);
            previous.setNextFocusRightId(control.getId());
            control.setNextFocusLeftId(previous.getId());
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                TvUi.dp(this, widthDp), TvUi.dp(this, 52));
        params.rightMargin = TvUi.dp(this, 12);
        controls.addView(control, params);
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long current = Math.max(0L, player.getCurrentPosition());
        long target = Math.max(0L, current + deltaMs);
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET && duration >= 0L) target = Math.min(target, duration);
        player.seekTo(target);
        if (playerStatus != null) {
            playerStatus.setText(deltaMs >= 0L ? "已快进到 " + formatTime(target)
                    : "已后退到 " + formatTime(target));
            playerStatus.setContentDescription("播放位置 " + target + "毫秒");
        }
    }

    private void skipIntro() {
        if (player == null) return;
        long introMs = activeEpisode != null && activeEpisode.introSkipMs > 0L
                ? activeEpisode.introSkipMs : DEFAULT_INTRO_SKIP_MS;
        long current = Math.max(0L, player.getCurrentPosition());
        if (current >= introMs) {
            if (playerStatus != null) playerStatus.setText("已超过片头结束位置");
            return;
        }
        long duration = player.getDuration();
        long target = duration != C.TIME_UNSET && duration >= 0L
                ? Math.min(introMs, duration) : introMs;
        player.seekTo(target);
        if (playerStatus != null) {
            playerStatus.setText("已跳过片头 · " + formatTime(target));
            playerStatus.setContentDescription("播放位置 " + target + "毫秒");
        }
    }

    private static String formatTime(long valueMs) {
        long totalSeconds = Math.max(0L, valueMs) / 1_000L;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private void showPlayerOverlay(boolean autoHide) {
        handler.removeCallbacks(hidePlayerOverlay);
        if (playerOverlay == null) return;
        playerOverlay.setVisibility(View.VISIBLE);
        if (autoHide && playbackStarted && !waitingForPrebuffer) {
            handler.postDelayed(hidePlayerOverlay, PLAYER_OVERLAY_HIDE_MS);
        }
    }

    private static boolean consumesFirstRevealPress(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return false;
        }
    }

    private static int findEpisode(Models.Line line, String name, int fallback) {
        String targetKey = episodeKey(name);
        if (name != null && !name.isEmpty()) {
            for (int index = 0; index < line.episodes.size(); index++) {
                String candidate = line.episodes.get(index).name;
                if (name.equals(candidate) || (!targetKey.isEmpty()
                        && targetKey.equals(episodeKey(candidate)))) return index;
            }
            return -1;
        }
        return Math.min(Math.max(fallback, 0), line.episodes.size() - 1);
    }

    private static String episodeKey(String name) {
        if (name == null) return "";
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        if (number.find()) {
            try {
                return "episode:" + Integer.parseInt(number.group(1));
            } catch (NumberFormatException ignored) {
                // Fall back to normalized text for unusually long numeric labels.
            }
        }
        return name.trim().toLowerCase(Locale.US).replaceAll("\\s+", "");
    }

    private void cycleQualityMode() {
        QualityMode[] modes = QualityMode.values();
        qualityMode = modes[(qualityMode.ordinal() + 1) % modes.length];
        applyQualityMode();
        if (qualityButton != null) {
            qualityButton.setText("清晰度：" + qualityMode.label);
            qualityButton.setContentDescription("清晰度 " + qualityMode.label);
        }
        updatePlayingStatus();
    }

    private void applyQualityMode() {
        if (trackSelector == null) return;
        DefaultTrackSelector.Parameters.Builder parameters = trackSelector.buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setForceLowestBitrate(false)
                .setForceHighestSupportedBitrate(qualityMode.highestWithinLimit);
        if (qualityMode == QualityMode.AUTO) {
            parameters.clearVideoSizeConstraints();
        } else {
            parameters.setMaxVideoSize(qualityMode.maxWidth, qualityMode.maxHeight);
        }
        trackSelector.setParameters(parameters);
    }

    private void updatePlayingStatus() {
        if (playerStatus == null) return;
        String resolution = activeVideoWidth > 0 && activeVideoHeight > 0
                ? activeVideoWidth + "×" + activeVideoHeight : "检测中";
        playerStatus.setText(activeSiteName + " · " + activeLineName + " · 播放中 · "
                + qualityMode.label + " " + resolution);
    }

    private void releasePlayer() {
        handler.removeCallbacks(prebufferStarter);
        handler.removeCallbacks(hidePlayerOverlay);
        bufferingStartedAt = 0L;
        autoSwitching = false;
        waitingForPrebuffer = false;
        playbackStarted = false;
        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.stop();
            player.release();
        }
        player = null;
        playerView = null;
        playerOverlay = null;
        trackSelector = null;
        rewindButton = null;
        forwardButton = null;
        skipIntroButton = null;
        skipAdButton = null;
        qualityButton = null;
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(TvUi.dp(this, 54), TvUi.dp(this, 34), TvUi.dp(this, 54), TvUi.dp(this, 44));
        return page;
    }

    private ScrollView wrap(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View topBar(String title, String subtitle, boolean searchAction) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, TvUi.dp(this, 28));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = TvUi.text(this, title, 32, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(heading);
        labels.addView(TvUi.text(this, subtitle, 15, Color.parseColor("#AAB5BF")),
                marginTop(ViewGroup.LayoutParams.WRAP_CONTENT, 32, 5));
        bar.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (searchAction) {
            TextView search = TvUi.button(this, "搜索影片");
            search.setOnClickListener(view -> showSearch(""));
            bar.addView(search, new LinearLayout.LayoutParams(TvUi.dp(this, 170), TvUi.dp(this, 58)));
        } else {
            TextView back = TvUi.button(this, "返回");
            back.setOnClickListener(view -> onBackPressed());
            bar.addView(back, new LinearLayout.LayoutParams(TvUi.dp(this, 130), TvUi.dp(this, 58)));
        }
        return bar;
    }

    private View addVodSection(LinearLayout parent, String title, List<Models.Vod> items, String error) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.bottomMargin = TvUi.dp(this, 28);
        parent.addView(block, blockParams);
        block.addView(sectionTitle(title));
        if (items == null || items.isEmpty()) {
            block.addView(status(error == null ? "暂无内容" : error, true),
                    marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 64, 10));
            return null;
        }
        LinearLayout row = horizontalRow();
        View first = null;
        for (Models.Vod vod : items) {
            View card = vodCard(vod);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    TvUi.dp(this, 190), TvUi.dp(this, 334));
            cardParams.rightMargin = TvUi.dp(this, 15);
            row.addView(card, cardParams);
            if (first == null) first = card;
        }
        block.addView(horizontalScroll(row), marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 348, 12));
        return first;
    }

    private View vodCard(Models.Vod vod) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(vod.name);
        card.setPadding(TvUi.dp(this, 6), TvUi.dp(this, 6), TvUi.dp(this, 6), TvUi.dp(this, 8));
        card.setBackground(TvUi.focusBorderSelector(this, 8, "#171E26", "#F5C451", 5));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(TvUi.shape(this, 5, "#25313B", "#25313B", 0));
        card.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, TvUi.dp(this, 252)));
        images.load(vod.pic, poster);
        TextView name = TvUi.text(this, vod.name, 16, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(1);
        card.addView(name, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 30, 9));
        TextView meta = TvUi.text(this, detailLine(vod.site.name, vod.remarks),
                13, Color.parseColor("#AAB5BF"));
        meta.setMaxLines(1);
        card.addView(meta, marginTop(ViewGroup.LayoutParams.MATCH_PARENT, 25, 3));
        card.setOnClickListener(view -> loadDetail(vod));
        return card;
    }

    private TextView sectionTitle(String title) {
        TextView view = TvUi.text(this, title, 22, Color.WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView status(String text, boolean error) {
        TextView view = TvUi.text(this, text, 17, Color.parseColor(error ? "#FF7373" : "#AAB5BF"));
        view.setGravity(Gravity.CENTER);
        view.setBackground(TvUi.shape(this, 8, "#171E26", "#25313B", 1));
        view.setPadding(TvUi.dp(this, 20), 0, TvUi.dp(this, 20), 0);
        view.setMinHeight(TvUi.dp(this, 72));
        return view;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private HorizontalScrollView horizontalScroll(View content) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private LinearLayout.LayoutParams marginTop(int width, int heightDp, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width, heightDp > 0 ? TvUi.dp(this, heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = TvUi.dp(this, marginDp);
        return params;
    }

    private static String detailLine(String prefix, String value) {
        return value == null || value.trim().isEmpty() ? prefix : prefix + value.trim();
    }

    private static String join(String separator, String... values) {
        List<String> items = new ArrayList<>();
        for (String value : values) if (value != null && !value.trim().isEmpty()) items.add(value.trim());
        return android.text.TextUtils.join(separator, items);
    }

    private static boolean isDirectMedia(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(Locale.US);
        return value.startsWith("rtmp://") || value.startsWith("rtsp://")
                || value.matches("^https?://.+\\.(m3u8|mp4|flv|mkv|ts|mov|avi|webm|m4v|mpd)([?#].*)?$");
    }

    private static int firstDirectLine(Models.Detail detail) {
        for (int index = 0; index < detail.lines.size(); index++) {
            Models.Line line = detail.lines.get(index);
            if (!line.episodes.isEmpty() && isDirectMedia(line.episodes.get(0).url)) return index;
        }
        return 0;
    }

    private static java.util.Map<String, String> defaultHeaders(String api) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        try {
            URI uri = URI.create(api);
            headers.put("Referer", uri.getScheme() + "://" + uri.getHost() + "/");
        } catch (Exception ignored) {
            // Some streams do not require a Referer.
        }
        return headers;
    }
}

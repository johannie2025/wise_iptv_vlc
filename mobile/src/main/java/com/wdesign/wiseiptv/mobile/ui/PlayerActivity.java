package com.wdesign.wiseiptv.mobile.ui;

import android.content.Context;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

// REMPLACEMENT : Import de VLC à la place de l'ancienne PlayerView ExoPlayer
import org.videolan.libvlc.util.VLCVideoLayout;

import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.player.PlayerEngine;
import com.wdesign.wiseiptv.mobile.R;
import java.util.concurrent.Executors;

public class PlayerActivity extends AppCompatActivity implements PlayerEngine.Listener {
    public static final String EXTRA_ID="ch_id", EXTRA_NAME="ch_name", EXTRA_URL="ch_url", EXTRA_TYPE="ch_type";
    public static final String EXTRA_GROUP = "extra_group";
    public static final String EXTRA_ORDER = "extra_order";
    
    // REMPLACEMENT : Type mis à jour vers VLCVideoLayout
    private VLCVideoLayout playerView;
    private PlayerEngine engine;
    private ProgressBar progressBar;
    private TextView tvChannelName, tvQuality, tvError;
    private ImageButton btnFav, btnQuality, btnBack;
    private View osdTop;
    private AppDatabase db;
    private long channelId;
    private String channelName, streamUrl;
    private int contentType;
    private boolean isFav;
    private AudioManager audioManager;
    private GestureDetector gestureDetector;
    private final Handler osdHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsd = () -> osdTop.animate().alpha(0).setDuration(300).start();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_player);
        db = AppDatabase.get(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        channelId   = getIntent().getLongExtra(EXTRA_ID, -1);
        channelName = getIntent().getStringExtra(EXTRA_NAME);
        streamUrl   = getIntent().getStringExtra(EXTRA_URL);
        contentType = getIntent().getIntExtra(EXTRA_TYPE, 0);
        
        playerView    = findViewById(R.id.player_view);
        progressBar   = findViewById(R.id.progress_bar);
        tvChannelName = findViewById(R.id.tv_channel_name);
        tvQuality     = findViewById(R.id.tv_quality);
        tvError       = findViewById(R.id.tv_error);
        btnFav        = findViewById(R.id.btn_fav);
        btnQuality    = findViewById(R.id.btn_quality);
        btnBack       = findViewById(R.id.btn_back);
        osdTop        = findViewById(R.id.osd_top);
        
        tvChannelName.setText(channelName);
        btnBack.setOnClickListener(v -> finish());
        btnQuality.setOnClickListener(v -> showQualityPicker());
        btnFav.setOnClickListener(v -> toggleFav());
        
        engine = new PlayerEngine(this, this);
        engine.attachView(playerView);
        engine.play(streamUrl);
        
        loadFavState(); 
        setupGestures(); 
        showOsd();
    }

    @Override public void onBuffering(boolean b) { runOnUiThread(() -> progressBar.setVisibility(b ? View.VISIBLE : View.GONE)); }
    @Override public void onPlaying()           { runOnUiThread(() -> { tvError.setVisibility(View.GONE); progressBar.setVisibility(View.GONE); }); }
    @Override public void onError(String msg)   { runOnUiThread(() -> { tvError.setText("⚠ " + msg); tvError.setVisibility(View.VISIBLE); progressBar.setVisibility(View.GONE); }); }
    
    private void showQualityPicker() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Qualité vidéo")
            .setSingleChoiceItems(PlayerEngine.QUALITY_LABELS, engine.getQuality(), (d, which) -> {
                engine.setQuality(which); tvQuality.setText(PlayerEngine.QUALITY_LABELS[which]); d.dismiss();
            }).show();
    }
    
    private void showOsd() {
        osdTop.animate().alpha(1).setDuration(200).start();
        osdHandler.removeCallbacks(hideOsd); osdHandler.postDelayed(hideOsd, 4000);
    }
    
    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                showOsd();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 == null) return false;
                float w = playerView.getWidth(); float absY = Math.abs(dy);
                if (absY > 30) {
                    if (e1.getX() < w / 2) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = Math.max(0.1f, Math.min(1f,
                            (lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness) + (dy > 0 ? 0.04f : -0.04f)));
                        getWindow().setAttributes(lp);
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            dy > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    }
                    return true;
                }
                return false;
            }
        });
        // On applique l'écouteur sur playerView pour intercepter les glissements et les clics
        playerView.setOnTouchListener((v, e) -> {
            gestureDetector.onTouchEvent(e);
            return true;
        });
    }
    
    private void loadFavState() {
        if (channelId < 0) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            ChannelEntity ch = db.channelDao().findById(channelId);
            if (ch != null) { isFav = ch.isFavorite; runOnUiThread(this::updateFavIcon); }
        });
    }
    
    private void toggleFav() {
        isFav = !isFav; updateFavIcon();
        if (channelId < 0) return;
        Executors.newSingleThreadExecutor().execute(() -> db.channelDao().setFavorite(channelId, isFav));
    }
    
    private void updateFavIcon() {
        btnFav.setImageResource(isFav ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
    }
    
    @Override protected void onStop() { super.onStop(); if (engine != null) engine.pause(); }
    @Override protected void onDestroy() {
        super.onDestroy(); osdHandler.removeCallbacksAndMessages(null);
        if (engine != null) { engine.release(); engine = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
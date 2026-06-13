package com.wdesign.wiseiptv.core.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class PlayerEngine {
    private static final String TAG = "PlayerEngine";

    public interface Listener {
        void onBuffering(boolean buffering);
        void onPlaying();
        void onError(String message);
    }

    public static final int[] QUALITY_BITRATES = {0, 200_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 15_000_000};
    public static final String[] QUALITY_LABELS = {"Auto","240p","360p","480p","720p","1080p","4K"};

    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private final Context context;
    private final Listener listener;
    private int currentQuality = 0;
    private boolean isReleasing = false;

    public PlayerEngine(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
        buildPlayer();
    }

    private void buildPlayer() {
        ArrayList<String> options = new ArrayList<>();
        // OPTIMISATIONS IPTV POUR FLUIDITÉ MAXIMUM
        options.add("--http-reconnect");
        options.add("--network-caching=5000"); // Augmenté à 5 secondes pour amortir les variations de connexion
        options.add("--live-caching=2000");    // Cache pour le streaming direct
        options.add("--clock-jitter=500");     // Tolérance aux instabilités d'horloge des flux TS
        options.add("--clock-synchro=0");      // Désactive la resynchronisation stricte qui cause des micro-coupures
        options.add("--drop-late-frames");     // Autorise le saut d'images en retard pour garder la fluidité audio
        options.add("--skip-frames");          // fluidifie sur les appareils moins puissants
        options.add("--audio-time-stretch");   // Permet d'accélérer/ralentir l'audio sans coupure si le réseau fluctue

        libVLC = new LibVLC(context, options);
        vlcPlayer = new MediaPlayer(libVLC);

        vlcPlayer.setEventListener(event -> {
            if (isReleasing) return; // Ignore les événements pendant la fermeture
            
            switch (event.type) {
                case MediaPlayer.Event.Buffering:
                    if (event.getBuffering() < 100) {
                        listener.onBuffering(true);
                    } else {
                        listener.onBuffering(false);
                    }
                    break;
                case MediaPlayer.Event.Playing:
                    listener.onBuffering(false);
                    listener.onPlaying();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    Log.e(TAG, "VLC Error");
                    listener.onError("Erreur de lecture ou flux indisponible");
                    break;
            }
        });
    }

    public void attachView(VLCVideoLayout videoLayout) {
        if (vlcPlayer != null && videoLayout != null && !isReleasing) {
            vlcPlayer.attachViews(videoLayout, null, true, false);
        }
    }

    public void play(String url) {
        if (url == null || url.isEmpty() || isReleasing) return;

        try {
            if (vlcPlayer.isPlaying()) {
                vlcPlayer.stop();
            }

            Media media = new Media(libVLC, Uri.parse(url));
            
            // Forcer le décodage matériel automatique
            media.setHWDecoderEnabled(true, true);
            // Option spécifique au média pour le cache
            media.addOption(":network-caching=5000");

            vlcPlayer.setMedia(media);
            media.release();

            vlcPlayer.play();
        } catch (Exception e) {
            Log.e(TAG, "Error playing stream: " + e.getMessage());
            listener.onError("Impossible d'ouvrir ce flux");
        }
    }

    public void stop() {
        if (vlcPlayer != null && !isReleasing) {
            vlcPlayer.stop();
        }
    }

    public void pause() {
        if (vlcPlayer != null && !isReleasing) {
            vlcPlayer.pause();
        }
    }

    public void resume() {
        if (vlcPlayer != null && !isReleasing) {
            vlcPlayer.play();
        }
    }

    public boolean isPlaying() {
        return vlcPlayer != null && !isReleasing && vlcPlayer.isPlaying();
    }

    public long getPosition() {
        return vlcPlayer != null && !isReleasing ? vlcPlayer.getTime() : 0;
    }

    public void seekTo(long ms) {
        if (vlcPlayer != null && !isReleasing) {
            vlcPlayer.setTime(ms);
        }
    }

    public void setQuality(int index) {
        this.currentQuality = index;
    }

    public int getQuality() {
        return currentQuality;
    }

    /**
     * LIBÉRATION SÉCURISÉE EN ARRIÈRE-PLAN (Évite le crash / ANR au retour)
     */
    public void release() {
        if (isReleasing) return;
        isReleasing = true;

        // On coupe l'affichage immédiatement pour libérer l'activité
        if (vlcPlayer != null) {
            try {
                vlcPlayer.getVLCVout().detachViews();
            } catch (Exception ignored) {}
        }

        // On effectue la lourde destruction de LibVLC dans un thread séparé
        new Thread(() -> {
            try {
                if (vlcPlayer != null) {
                    if (vlcPlayer.isPlaying()) {
                        vlcPlayer.stop();
                    }
                    vlcPlayer.release();
                    vlcPlayer = null;
                }
                if (libVLC != null) {
                    libVLC.release();
                    libVLC = null;
                }
                Log.d(TAG, "LibVLC détruit avec succès en tâche de fond.");
            } catch (Exception e) {
                Log.e(TAG, "Erreur pendant le release de VLC: " + e.getMessage());
            }
        }).start();
    }
}
package com.wdesign.wiseiptv.core.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class PlayerEngine {
    private static final String TAG = "PlayerEngine";

    public interface Listener {
        void onBuffering(boolean buffering);
        void onPlaying();
        void onError(String message);
    }

    // Note : VLC gère le multi-débit (ABR) nativement ou via des options réseau.
    // Les limitations strictes de bitrate par index matériel se gèrent différemment en VLC,
    // mais nous gardons la structure pour ne pas casser votre logique métier.
    public static final int[] QUALITY_BITRATES = {0, 200_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 15_000_000};
    public static final String[] QUALITY_LABELS = {"Auto","240p","360p","480p","720p","1080p","4K"};

    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private final Context context;
    private final Listener listener;
    private int currentQuality = 0;

    public PlayerEngine(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
        buildPlayer();
    }

    private void buildPlayer() {
        // Configuration des options VLC optimisées pour l'IPTV (buffering, réseau)
        ArrayList<String> options = new ArrayList<>();
        options.add("-vvv"); // Verbosité des logs (à retirer en production si trop lourd)
        options.add("--http-reconnect");
        options.add("--network-caching=3000"); // Cache réseau de 3 secondes pour stabiliser l'IPTV

        libVLC = new LibVLC(context, options);
        vlcPlayer = new MediaPlayer(libVLC);

        // Gestion des événements VLC (Équivalent de Player.Listener d'ExoPlayer)
        vlcPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Buffering:
                    // event.getBuffering() donne le pourcentage de mise en cache
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
                    Log.e(TAG, "VLC a rencontré une erreur de lecture");
                    listener.onError("Erreur de lecture (VLC)");
                    break;
                case MediaPlayer.Event.EndReached:
                    Log.i(TAG, "Fin du flux atteinte");
                    break;
            }
        });
    }

    // IMPORTANT : Remplacer PlayerView par VLCVideoLayout dans votre layout XML
  public void attachView(VLCVideoLayout videoLayout) {
    if (vlcPlayer != null && videoLayout != null) {
        // LibVLC moderne : on attache directement le layout au MediaPlayer
        vlcPlayer.attachViews(videoLayout);
    }
}

    public void play(String url) {
        if (url == null || url.isEmpty()) return;

        try {
            stop(); // Sécurité : Arrêter la lecture en cours si existante

            // Création du média VLC à partir de l'URL du flux IPTV
            Media media = new Media(libVLC, Uri.parse(url));
            
            // Options spécifiques au média (Exemple : forcer le décodage matériel)
            media.setHWDecoderEnabled(true, true);
            
            vlcPlayer.setMedia(media);
            media.release(); // Libération de la référence locale après attribution au player

            vlcPlayer.play();
            applyQuality(currentQuality);
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du lancement de la lecture: " + e.getMessage());
            listener.onError("Impossible d'ouvrir le flux");
        }
    }

    public void stop() {
        if (vlcPlayer != null && vlcPlayer.isPlaying()) {
            vlcPlayer.stop();
        }
    }

    public void pause() {
        if (vlcPlayer != null) {
            vlcPlayer.pause();
        }
    }

    public void resume() {
        if (vlcPlayer != null) {
            vlcPlayer.play();
        }
    }

    public boolean isPlaying() {
        return vlcPlayer != null && vlcPlayer.isPlaying();
    }

    public long getPosition() {
        return vlcPlayer != null ? vlcPlayer.getTime() : 0; // En VLC, getCurrentPosition() est un float entre 0 et 1, getTime() est en ms.
    }

    public void seekTo(long ms) {
        if (vlcPlayer != null) {
            vlcPlayer.setTime(ms);
        }
    }

    public void setQuality(int index) {
        this.currentQuality = index;
        applyQuality(index);
    }

    public int getQuality() {
        return currentQuality;
    }

    private void applyQuality(int i) {
        if (vlcPlayer == null || vlcPlayer.getMedia() == null) return;
        
        // Note sur la qualité avec LibVLC : 
        // Contrairement à ExoPlayer qui permet de brider dynamiquement le bitrate via TrackSelection,
        // VLC s'appuie nativement sur le choix des pistes (pistes vidéo disponibles dans le flux HLS/M3U8).
        // Pour changer de qualité à la volée sur un flux adaptatif en VLC, on utilise plutôt :
        // vlcPlayer.setVideoTrack(trackId);
        Log.d(TAG, "Changement de qualité demandé (Index): " + i + " - Bitrate théorique cible: " + QUALITY_BITRATES[i]);
    }

    public void release() {
        if (vlcPlayer != null) {
            vlcPlayer.getVLCVout().detachViews();
            vlcPlayer.stop();
            vlcPlayer.release();
            vlcPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }
}
package com.wdesign.wiseiptv.tv.ui;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import android.view.View;
import androidx.activity.result.*;
import androidx.activity.result.contract.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.*;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import com.wdesign.wiseiptv.tv.R;
import com.wdesign.wiseiptv.tv.util.PlaylistLoader; // Vérifier si .mobile.util.PlaylistLoader n'est pas requis
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Gérer les playlists : ajouter (URL M3U, fichier local, Xtream), supprimer, rafraîchir.
 * Version optimisée pour le D-Pad Android TV.
 */
public class PlaylistManagerActivity extends AppCompatActivity {

    private AppDatabase db;
    private RecyclerView rv;
    private PlaylistAdapter adapter;
    private Uri pickedFileUri;
    private ActivityResultLauncher<String[]> filePicker;
    
    // 🔍 CORRECTION : Référence persistante pour mettre à jour le label du fichier dans le dialogue actif
    private TextView tvCurrentFileLabel; 

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_manager);
        db = AppDatabase.get(this);

        rv = findViewById(R.id.rv_playlists);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PlaylistAdapter();
        rv.setAdapter(adapter);

        // File picker (M3U local)
        filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { 
                if (uri != null) { 
                    pickedFileUri = uri; 
                    updateFileLabel(uri); 
                }
            }
        );

        db.playlistDao().getAll().observe(this, adapter::setData);
        findViewById(R.id.btn_add_playlist).setOnClickListener(v -> showAddDialog());
    }

    private void showAddDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_add_playlist, null);
        EditText etName   = v.findViewById(R.id.et_playlist_name);
        EditText etUrl    = v.findViewById(R.id.et_playlist_url); 
        EditText etServer = v.findViewById(R.id.et_xtream_server);
        EditText etUser   = v.findViewById(R.id.et_xtream_user);
        EditText etPass   = v.findViewById(R.id.et_xtream_pass);
        EditText etEpg    = v.findViewById(R.id.et_epg_url);
        RadioGroup rgType = v.findViewById(R.id.rg_type);
        View layoutXtream = v.findViewById(R.id.layout_xtream);
        View layoutFile   = v.findViewById(R.id.layout_file); 
        
        // Asseveration de la variable globale pour éviter le findViewWithTag défaillant
        tvCurrentFileLabel = v.findViewById(R.id.tv_file_path); 

        v.findViewById(R.id.btn_pick_file).setOnClickListener(b ->
            filePicker.launch(new String[]{"audio/x-mpegurl","application/x-mpegurl","text/plain","*/*"})
        );

        rgType.setOnCheckedChangeListener((g, id) -> {
            boolean isXtream = id == R.id.rb_xtream;
            boolean isFile   = id == R.id.rb_m3u_file;
            layoutXtream.setVisibility(isXtream ? View.VISIBLE : View.GONE);
            layoutFile.setVisibility(isFile   ? View.VISIBLE : View.GONE);
            etUrl.setVisibility(!isXtream && !isFile ? View.VISIBLE : View.GONE);
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Ajouter une playlist").setView(v);
        b.setPositiveButton("Charger", (d, w) -> {
            String name = etName.getText().toString().trim();
            int cid = rgType.getCheckedRadioButtonId();
            PlaylistEntity pl = new PlaylistEntity();
            pl.name = name.isEmpty() ? "Playlist" : name;
            pl.lastUpdated = 0;

            if (cid == R.id.rb_xtream) {
                pl.type = PlaylistEntity.TYPE_XTREAM;
                pl.url  = etServer.getText().toString().trim();
                pl.username = etUser.getText().toString().trim();
                pl.password = etPass.getText().toString().trim();
                if (pl.url.isEmpty() || pl.username.isEmpty()) return;
            } else if (cid == R.id.rb_m3u_file) {
                if (pickedFileUri == null) { 
                    Toast.makeText(this,"Sélectionner un fichier",Toast.LENGTH_SHORT).show(); 
                    return; 
                }
                getContentResolver().takePersistableUriPermission(pickedFileUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pl.type = PlaylistEntity.TYPE_M3U_FILE;
                pl.url  = pickedFileUri.toString();
            } else {
                pl.type = PlaylistEntity.TYPE_M3U_URL;
                pl.url  = etUrl.getText().toString().trim();
                if (pl.url.isEmpty()) return;
            }
            // Conserver la valeur EPG si votre entité possède le champ associé
            String epg = etEpg.getText().toString().trim();

            showProgress(true);
            Executors.newSingleThreadExecutor().execute(() -> {
                pl.id = db.playlistDao().insert(pl);
                PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
                    @Override public void onDone(int count) {
                        runOnUiThread(() -> { 
                            showProgress(false);
                            Toast.makeText(PlaylistManagerActivity.this, count + " chaînes chargées", Toast.LENGTH_SHORT).show(); 
                        });
                    }
                    @Override public void onError(String msg) {
                        runOnUiThread(() -> { 
                            showProgress(false);
                            Toast.makeText(PlaylistManagerActivity.this, "Erreur: " + msg, Toast.LENGTH_LONG).show(); 
                        });
                    }
                });
            });
        });
        b.setNegativeButton("Annuler", null);
        
        AlertDialog dialog = b.create();
        dialog.show();
        
        // Ajustement de redimensionnement dynamique spécial Clavier TV
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void updateFileLabel(Uri uri) {
        // ✅ CORRECTION : Utilisation directe du pointeur UI vérifié au lieu du findViewWithTag instable
        if (tvCurrentFileLabel != null && uri != null) { 
            tvCurrentFileLabel.setText(uri.getLastPathSegment()); 
        }
    }

    private void showProgress(boolean show) {
        View pb = findViewById(R.id.progress_bar);
        if (pb != null) pb.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Adapter inline ────────────────────────────────────────────
    class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {
        List<PlaylistEntity> data = new java.util.ArrayList<>();
        
        void setData(List<PlaylistEntity> d) { data = d; notifyDataSetChanged(); }
        
        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            android.view.View v = getLayoutInflater().inflate(R.layout.item_playlist, p, false);
            return new VH(v);
        }
        
        @Override public void onBindViewHolder(VH h, int pos) { h.bind(data.get(pos)); }
        
        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvType, tvStatus;
            
            VH(android.view.View v) {
                super(v);
                tvName   = v.findViewById(R.id.tv_pl_name);
                tvType   = v.findViewById(R.id.tv_pl_type);
                tvStatus = v.findViewById(R.id.tv_pl_status);
                
                // Navigation D-Pad : clic sur les boutons d'action individuels
                v.findViewById(R.id.btn_pl_refresh).setOnClickListener(b -> refresh(getAdapterPosition()));
                v.findViewById(R.id.btn_pl_delete).setOnClickListener(b  -> confirmDelete(getAdapterPosition()));
                
                // 📺 OPTIMISATION TV : Rendre toute la ligne réactive au clic de la télécommande
                v.setFocusable(true);
                v.setOnClickListener(b -> refresh(getAdapterPosition()));
            }
            
            void bind(PlaylistEntity pl) {
                tvName.setText(pl.name);
                tvType.setText(new String[]{"M3U URL","Fichier","Xtream"}[pl.type]);
                long age = System.currentTimeMillis() - pl.lastUpdated;
                tvStatus.setText(pl.lastUpdated == 0 ? "Jamais chargé" : "Mis à jour il y a " + (age / 3600000) + "h");
            }
        }
    }

    private void refresh(int pos) {
        if (pos < 0 || pos >= adapter.data.size()) return;
        PlaylistEntity pl = adapter.data.get(pos);
        showProgress(true);
        PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
            @Override public void onDone(int c) { 
                runOnUiThread(() -> { 
                    showProgress(false);
                    Toast.makeText(PlaylistManagerActivity.this, c + " chaînes", Toast.LENGTH_SHORT).show(); 
                }); 
            }
            @Override public void onError(String m) { 
                runOnUiThread(() -> { 
                    showProgress(false);
                    Toast.makeText(PlaylistManagerActivity.this, "Erreur: " + m, Toast.LENGTH_LONG).show(); 
                }); 
            }
        });
    }

    private void confirmDelete(int pos) {
        if (pos < 0 || pos >= adapter.data.size()) return;
        PlaylistEntity pl = adapter.data.get(pos);
        new AlertDialog.Builder(this)
            .setTitle("Supprimer \"" + pl.name + "\" ?")
            .setMessage("Toutes les chaînes de cette playlist seront supprimées.")
            .setPositiveButton("Supprimer", (d, w) -> Executors.newSingleThreadExecutor().execute(() -> {
                db.channelDao().deleteByPlaylist(pl.id);
                db.playlistDao().delete(pl);
            }))
            .setNegativeButton("Annuler", null).show();
    }
}
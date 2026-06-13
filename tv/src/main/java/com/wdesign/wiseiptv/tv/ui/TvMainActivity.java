package com.wdesign.wiseiptv.tv.ui;

import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.*;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import com.wdesign.wiseiptv.core.security.DeviceSecurity;
import com.wdesign.wiseiptv.tv.R;
import com.wdesign.wiseiptv.tv.adapter.TvCardPresenter;
import com.wdesign.wiseiptv.tv.util.ActivationManager;
import com.wdesign.wiseiptv.tv.util.PlaylistLoader;
import java.util.*;
import java.util.concurrent.Executors;

public class TvMainActivity extends FragmentActivity {

    public static final String EXTRA_ACT_LOGIN        = "act_login";
    public static final String EXTRA_ACT_PASSWORD     = "act_password";
    public static final String EXTRA_ACT_EXPIRES      = "act_expires";
    public static final String EXTRA_ACT_DNS_URLS     = "act_dns_urls";
    public static final String EXTRA_ACT_DNS_EPG_URLS = "act_dns_epg_urls";

    private static final String TAG           = "TvMainActivity";
    private static final String PREFS_RECENTS = "wise_tv_recents";
    private static final int    MAX_RECENTS   = 30;

    private BrowseSupportFragment browseFragment;
    private ArrayObjectAdapter    rowsAdapter;
    private AppDatabase           db;

    // Barre supérieure custom
    private TextView    btnSearch, btnAdd, btnSync, btnRecents, tvSyncStatus;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_main);

        db = AppDatabase.get(this);

        // Références barre top
        btnSearch    = findViewById(R.id.btn_search);
        btnAdd       = findViewById(R.id.btn_add);
        btnSync      = findViewById(R.id.btn_sync);
        btnRecents   = findViewById(R.id.btn_recents);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        progressBar  = findViewById(R.id.progress_bar);

        // Boutons top bar
        btnSearch.setOnClickListener(v -> showSearchDialog());
        btnAdd.setOnClickListener(v -> showAddPlaylistDialog());
        btnSync.setOnClickListener(v -> syncNow());
        btnRecents.setOnClickListener(v -> scrollToRecents());

        setupBrowseFragment();
        observeAllRows();
        startBackgroundSync();
		btnSearch.post(btnSearch::requestFocus);
		
    }

@Override
protected void onResume() {
    super.onResume();
    
    // Force le focus sur le bouton de recherche à CHAQUE FOIS que l'écran redevient visible
    if (btnSearch != null) {
        btnSearch.post(() -> {
            btnSearch.requestFocus();
            Log.d(TAG, "Focus forcé sur btnSearch dans onResume");
        });
    }
}

// ── BrowseFragment — Version Nettoyée et Sans Loupe Native ─────────────────
    private void setupBrowseFragment() {
        browseFragment = (BrowseSupportFragment)
            getSupportFragmentManager().findFragmentById(R.id.browse_fragment);
        if (browseFragment == null) {
            browseFragment = new BrowseSupportFragment();
            getSupportFragmentManager().beginTransaction()
                .add(R.id.browse_fragment, browseFragment).commit();
        }

        // Masquer complètement l'en-tête vertical gauche natif
        browseFragment.setHeadersState(BrowseSupportFragment.HEADERS_DISABLED);
        browseFragment.setHeadersTransitionOnBackEnabled(false);

        browseFragment.setTitle("");
        browseFragment.setBrandColor(getResources().getColor(R.color.wise_brand, getTheme()));

        // ⚠️ ENLEVÉ : Ne PAS appeler setOnSearchClickedListener(...) !
        // Cela supprime définitivement le bouton de recherche natif Leanback du focus D-Pad.

        rowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        browseFragment.setAdapter(rowsAdapter);

        browseFragment.setOnItemViewClickedListener((ivh, item, rvh, row) -> {
            if (item instanceof ChannelEntity) openPlayer((ChannelEntity) item);
        });
    }

    // ── 🔍 Recherche — dialog avec saisie + résultats live ────────

    private void showSearchDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        b.setTitle("🔍 Rechercher une chaîne");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);

        // Champ de saisie
        EditText etSearch = new EditText(this);
        etSearch.setHint("Tapez le nom de la chaîne…");
        etSearch.setSingleLine(true);
        etSearch.setTextSize(16f);
        layout.addView(etSearch);

        // Compteur résultats
        TextView tvCount = new TextView(this);
        tvCount.setTextSize(12f);
        tvCount.setPadding(0, 6, 0, 6);
        layout.addView(tvCount);

        // Liste résultats (focusable D-Pad)
        ListView lv = new ListView(this);
        lv.setMinimumHeight(400);
        lv.setDividerHeight(1);
        layout.addView(lv);

        final List<ChannelEntity> found = new ArrayList<>();
        ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, new ArrayList<>());
        lv.setAdapter(listAdapter);

        final Handler debounce = new Handler(Looper.getMainLooper());
        final Runnable[] searchRunnable = {null};

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // Debounce 300ms pour éviter trop de requêtes
                if (searchRunnable[0] != null) debounce.removeCallbacks(searchRunnable[0]);
                searchRunnable[0] = () -> {
                    String q = s.toString().trim();
                    if (q.isEmpty()) {
                        listAdapter.clear(); found.clear();
                        tvCount.setText("");
                        return;
                    }
                    Executors.newSingleThreadExecutor().execute(() -> {
                        List<ChannelEntity> results = db.channelDao().searchSync(q);
                        runOnUiThread(() -> {
                            listAdapter.clear(); found.clear();
                            if (results.isEmpty()) {
                                tvCount.setText("Aucun résultat");
                            } else {
                                tvCount.setText(results.size() + " résultat(s)");
                                for (ChannelEntity ch : results) {
                                    String label = ch.name;
                                    if (ch.groupTitle != null && !ch.groupTitle.isEmpty())
                                        label += "  [" + ch.groupTitle + "]";
                                    listAdapter.add(label);
                                    found.add(ch);
                                }
                            }
                            listAdapter.notifyDataSetChanged();
                        });
                    });
                };
                debounce.postDelayed(searchRunnable[0], 300);
            }
        });

        AlertDialog dialog = b.create();
        dialog.setView(layout);

        lv.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < found.size()) {
                dialog.dismiss();
                openPlayer(found.get(pos));
            }
        });

        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Fermer",
            (d, w) -> d.dismiss());

        dialog.show();
        // Focus immédiat sur le champ de saisie
        etSearch.post(etSearch::requestFocus);
    }

    // ── Rows LiveData ─────────────────────────────────────────────

    private void observeAllRows() {
        TvCardPresenter p = new TvCardPresenter();

        // Récents EN PREMIER
        loadRecentsRow();

        // Live par groupes
        db.channelDao().getLiveGroups().observe(this, groups -> {
            if (groups == null) return;
            removeRowsWithIdRange(1000, 1999);
            int rowId = 1000;
            for (String grp : groups) {
                final int fId = rowId++;
                db.channelDao().getLiveByGroup(grp).observe(this, list -> {
                    if (list != null && !list.isEmpty()) upsertRow("📺 " + grp, fId, p, list);
                    else removeRow(fId);
                });
            }
        });

        db.channelDao().getFilmGroups().observe(this, groups -> {
            if (groups == null) return;
            removeRowsWithIdRange(2000, 2999);
            int rowId = 2000;
            for (String grp : groups) {
                final int fId = rowId++;
                db.channelDao().getFilmsByGroup(grp).observe(this, list -> {
                    if (list != null && !list.isEmpty()) upsertRow("🎬 " + grp, fId, p, list);
                    else removeRow(fId);
                });
            }
        });

        db.channelDao().getSeriesGroups().observe(this, groups -> {
            if (groups == null) return;
            removeRowsWithIdRange(3000, 3999);
            int rowId = 3000;
            for (String grp : groups) {
                final int fId = rowId++;
                db.channelDao().getSeriesByGroup(grp).observe(this, list -> {
                    if (list != null && !list.isEmpty()) upsertRow("📺 " + grp, fId, p, list);
                    else removeRow(fId);
                });
            }
        });

        db.channelDao().getFavorites().observe(this, list -> {
            if (list != null && !list.isEmpty()) upsertRow("❤️ Favoris", 9001, p, list);
            else removeRow(9001);
        });
    }

    // ── Gestion des lignes ────────────────────────────────────────

    private void upsertRow(String title, int id, TvCardPresenter p, List<ChannelEntity> list) {
        ArrayObjectAdapter rowAdapter = new ArrayObjectAdapter(p);
        for (ChannelEntity ch : list) rowAdapter.add(ch);
        ListRow newRow = new ListRow(new HeaderItem(id, title), rowAdapter);
        for (int i = 0; i < rowsAdapter.size(); i++) {
            if (((ListRow) rowsAdapter.get(i)).getHeaderItem().getId() == id) {
                rowsAdapter.replace(i, newRow); return;
            }
        }
        int insertAt = rowsAdapter.size();
        for (int i = 0; i < rowsAdapter.size(); i++) {
            long rid = ((ListRow) rowsAdapter.get(i)).getHeaderItem().getId();
            if (rid > id && rid != 9002) { insertAt = i; break; }
        }
        rowsAdapter.add(insertAt, newRow);
    }

    private void removeRow(int id) {
        for (int i = 0; i < rowsAdapter.size(); i++) {
            if (((ListRow) rowsAdapter.get(i)).getHeaderItem().getId() == id) {
                rowsAdapter.remove(rowsAdapter.get(i)); return;
            }
        }
    }

    private void removeRowsWithIdRange(int from, int to) {
        for (int i = rowsAdapter.size() - 1; i >= 0; i--) {
            long rid = ((ListRow) rowsAdapter.get(i)).getHeaderItem().getId();
            if (rid >= from && rid <= to) rowsAdapter.remove(rowsAdapter.get(i));
        }
    }

    // ── Récents ───────────────────────────────────────────────────

    private void scrollToRecents() {
        for (int i = 0; i < rowsAdapter.size(); i++) {
            if (((ListRow) rowsAdapter.get(i)).getHeaderItem().getId() == 9002) {
                browseFragment.setSelectedPosition(i);
                return;
            }
        }
        Toast.makeText(this, "Aucun récent", Toast.LENGTH_SHORT).show();
    }

    private void saveRecent(long channelId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_RECENTS, Context.MODE_PRIVATE);
        String raw = prefs.getString("ids", "");
        List<String> ids = new ArrayList<>(
            Arrays.asList(raw.isEmpty() ? new String[0] : raw.split(",")));
        String sid = String.valueOf(channelId);
        ids.remove(sid); ids.add(0, sid);
        if (ids.size() > MAX_RECENTS) ids = ids.subList(0, MAX_RECENTS);
        prefs.edit().putString("ids", TextUtils.join(",", ids)).apply();
        loadRecentsRow();
    }

    private void loadRecentsRow() {
        Executors.newSingleThreadExecutor().execute(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_RECENTS, Context.MODE_PRIVATE);
            String raw = prefs.getString("ids", "");
            if (raw.isEmpty()) { runOnUiThread(() -> removeRow(9002)); return; }
            List<ChannelEntity> recents = new ArrayList<>();
            for (String sid : raw.split(",")) {
                try {
                    ChannelEntity ch = db.channelDao().findById(Long.parseLong(sid.trim()));
                    if (ch != null) recents.add(ch);
                } catch (NumberFormatException ignored) {}
            }
            final List<ChannelEntity> fr = recents;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!fr.isEmpty()) upsertRow("🕐 Récents", 9002, new TvCardPresenter(), fr);
                else removeRow(9002);
            });
        });
    }

    // ── Sync background ───────────────────────────────────────────
    //
    // APPROCHE : même logique que "Ajouter une playlist" + "Charger",
    // mais automatique, DNS par DNS, depuis les prefs ou les extras.
    // Chaque DNS → PlaylistEntity en DB → PlaylistLoader.load() séquentiel.
    // Les LiveData observers affichent les chaînes dès que chaque DNS est chargé.

   // ── Sync background — Limité à 1 fois par semaine ──────────────────────────
    private void startBackgroundSync() {
        SharedPreferences prefs = getSharedPreferences("wise_activation_tv", Context.MODE_PRIVATE);
        
        // Vérification de la date de dernière synchronisation réussie
        long lastSync = prefs.getLong("last_weekly_sync_timestamp", 0);
        long currentTime = System.currentTimeMillis();
        long oneWeekInMs = 7L * 24 * 60 * 60 * 1000; // 7 jours

        if (currentTime - lastSync < oneWeekInMs) {
            Log.d(TAG, "Sync ignoré : Dernière synchronisation datant de moins d'une semaine.");
            return; // L'application s'ouvre instantanément sans retélécharger
        }

        Intent intent = getIntent();
        String login    = intent.getStringExtra(EXTRA_ACT_LOGIN);
        String password = intent.getStringExtra(EXTRA_ACT_PASSWORD);
        String expires  = intent.getStringExtra(EXTRA_ACT_EXPIRES);
        String[] dnsUrls    = intent.getStringArrayExtra(EXTRA_ACT_DNS_URLS);
        String[] dnsEpgUrls = intent.getStringArrayExtra(EXTRA_ACT_DNS_EPG_URLS);

        if (login != null && dnsUrls != null && dnsUrls.length > 0) {
            loadDnsSequentially(login, password != null ? password : "",
                expires != null ? expires : "", dnsUrls,
                dnsEpgUrls != null ? dnsEpgUrls : new String[0]);
            return;
        }

        String savedStatus = prefs.getString("status",     "UNKNOWN");
        String savedLogin  = prefs.getString("login",      null);
        String savedPass   = prefs.getString("password",   "");
        String savedExp    = prefs.getString("expires_at", "");
        String savedDnsRaw = prefs.getString("dns_urls",   "");

        if ("ACTIVE".equals(savedStatus) && savedLogin != null && !savedDnsRaw.isEmpty()) {
            String[] urls = savedDnsRaw.split(",");
            String[] epgs = prefs.getString("dns_epg_urls", "").split(",");
            loadDnsSequentially(savedLogin, savedPass, savedExp, urls, epgs);
        }
    }

    /**
     * Charge chaque DNS comme si l'utilisateur avait cliqué "Ajouter + Charger".
     * Traitement SÉQUENTIEL : DNS 1 → DNS 2 → … → DNS N.
     * Les chaînes apparaissent dans l'interface dès que chaque DNS est chargé
     * (via LiveData observers dans observeAllRows).
     */
// ── Chargement séquentiel corrigé ─────────────────────────────────────────
private void loadDnsSequentially(String login, String password, String expires,
                                  String[] dnsUrls, String[] epgUrls) {
    showSyncStatus("Chargement de vos chaînes…");
    
    Executors.newSingleThreadExecutor().execute(() -> {
        // On commence à l'index 0 avec un compteur de chaînes à 0
        processNextDns(0, dnsUrls, login, password, 0);
    });
}

private void processNextDns(int index, String[] dnsUrls, String login, String password, final int totalLoadedAccumulated) {
    if (index >= dnsUrls.length) {
        // ── TOUS LES DNS SONT CHARGÉS ──
        runOnUiThread(() -> {
            hideSyncStatus();
            
            if (totalLoadedAccumulated > 0) {
                Toast.makeText(this, "✅ Synchronisation terminée : " + totalLoadedAccumulated + " chaînes chargées", Toast.LENGTH_LONG).show();
                
                // FORCE LE RAFRAÎCHISSEMENT VISUEL : Ré-observe ou recharge les lignes
                observeAllRows(); 
            } else {
                Toast.makeText(this, "⚠️ Synchronisation terminée, mais aucune chaîne trouvée.", Toast.LENGTH_LONG).show();
            }
            
            // Verrouiller la mise à jour hebdomadaire pendant 7 jours
            getSharedPreferences("wise_activation_tv", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_weekly_sync_timestamp", System.currentTimeMillis())
                .apply();
        });
        return;
    }

    String url = dnsUrls[index].trim();
    if (url.isEmpty()) {
        processNextDns(index + 1, dnsUrls, login, password, totalLoadedAccumulated);
        return;
    }

    int idx = index + 1;
    int total = dnsUrls.length;

    // Récupérer ou créer l'entité playlist dans la DB
    PlaylistEntity pl = findOrCreatePlaylist(login, password, url, idx);

    runOnUiThread(() -> showSyncStatus("📥 Téléchargement du serveur " + idx + "/" + total + "…"));

    // Utilisation du chargeur robuste (identique au mode manuel)
    PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
        @Override
        public void onDone(int count) {
            Log.d(TAG, "DNS " + idx + " chargé : " + count + " chaînes.");
            
            // On passe au DNS suivant en ajoutant les chaînes trouvées au compteur global
            processNextDns(index + 1, dnsUrls, login, password, totalLoadedAccumulated + count);
        }

        @Override
        public void onError(String msg) {
            Log.w(TAG, "Échec du DNS " + idx + " : " + msg);
            // En cas d'erreur sur un serveur, on passe quand même au suivant sans perdre le compte
            processNextDns(index + 1, dnsUrls, login, password, totalLoadedAccumulated);
        }
    });
}

private void processNextDns(int index, String[] dnsUrls, String login, String password) {
    if (index >= dnsUrls.length) {
        // Synchronisation de tous les DNS terminée !
        runOnUiThread(() -> {
            hideSyncStatus();
            Toast.makeText(this, "✅ Synchronisation automatique terminée", Toast.LENGTH_SHORT).show();
            
            // Verrouiller la mise à jour hebdo pendant 7 jours
            getSharedPreferences("wise_activation_tv", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_weekly_sync_timestamp", System.currentTimeMillis())
                .apply();
        });
        return;
    }

    String url = dnsUrls[index].trim();
    if (url.isEmpty()) {
        processNextDns(index + 1, dnsUrls, login, password);
        return;
    }

    int idx = index + 1;
    int total = dnsUrls.length;

    // Récupérer ou créer l'entité
    PlaylistEntity pl = findOrCreatePlaylist(login, password, url, idx);

    runOnUiThread(() -> showSyncStatus("📥 Chargement du serveur " + idx + "/" + total));

    // Utilisation de la méthode native "PlaylistLoader.load" identique à celle du mode manuel
    PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
        @Override
        public void onDone(int count) {
            Log.d(TAG, "DNS " + idx + " chargé avec succès : " + count + " chaînes.");
            // DNS suivant
            processNextDns(index + 1, dnsUrls, login, password);
        }

        @Override
        public void onError(String msg) {
            Log.w(TAG, "Échec du chargement automatique du DNS " + idx + " : " + msg);
            // On passe quand même au suivant en cas d'erreur
            processNextDns(index + 1, dnsUrls, login, password);
        }
    });
}

    /** Retrouve ou crée la PlaylistEntity pour un DNS donné */
   /** Retrouve ou crée la PlaylistEntity avec détection automatique du type */
private PlaylistEntity findOrCreatePlaylist(String login, String password,
                                              String dnsUrl, int idx) {
    // Chercher par URL + login
    PlaylistEntity existing = db.playlistDao().findByUrlAndLogin(dnsUrl, login);
    if (existing != null) {
        existing.password = password;
        existing.isActive = true;
        db.playlistDao().update(existing);
        return existing;
    }

    PlaylistEntity pl = new PlaylistEntity();
    pl.name = "IPTV #" + idx;
    pl.url = dnsUrl.trim();
    pl.isActive = true;
    pl.lastUpdated = 0;

    // === DÉTECTION AUTOMATIQUE DU TYPE ===
    if (isXtreamUrl(pl.url)) {
        pl.type = PlaylistEntity.TYPE_XTREAM;
        pl.username = login;
        pl.password = password;
        Log.d(TAG, "Détecté comme Xtream: " + pl.url);
    } else {
        pl.type = PlaylistEntity.TYPE_M3U_URL;
        // Pour les M3U classiques, on n'a pas besoin de login/password
        pl.username = "";
        pl.password = "";
        Log.d(TAG, "Détecté comme M3U simple: " + pl.url);
    }

    pl.id = db.playlistDao().insert(pl);
    return pl;
}

/** Détection intelligente du type de playlist */
private boolean isXtreamUrl(String url) {
    if (url == null || url.isEmpty()) return false;
    String lower = url.toLowerCase();

    // Cas très clair
    if (lower.contains("/get.php") || 
        lower.contains("/player_api.php") || 
        lower.contains("/apiget.php") ||
        lower.contains("/panel_api.php")) {
        return true;
    }

    // Format Xtream alternatif : domaine/username/password/...
    if (lower.matches(".*https?://.+/[^/]+/[^/]+/(m3u_plus|m3u|ts).*")) {
        return true;
    }

    // Si l'URL contient "username=" et "password=", très probablement Xtream
    if (lower.contains("username=") && lower.contains("password=")) {
        return true;
    }

    return false;
}

    // ── Sync manuelle (bouton 🔄) ─────────────────────────────────

    private void syncNow() {
        SharedPreferences prefs = getSharedPreferences("wise_activation_tv", Context.MODE_PRIVATE);
        String savedDnsRaw = prefs.getString("dns_urls", "");
        String savedLogin  = prefs.getString("login",    null);
        String savedPass   = prefs.getString("password", "");
        String savedExp    = prefs.getString("expires_at", "");

        if (savedLogin != null && !savedDnsRaw.isEmpty()) {
            String[] urls = savedDnsRaw.split(",");
            String[] epgs = prefs.getString("dns_epg_urls", "").split(",");
            loadDnsSequentially(savedLogin, savedPass, savedExp, urls, epgs);
        } else {
            // Pas d'activation → refresh playlists manuelles
            showSyncStatus("Synchronisation…");
            PlaylistLoader.refreshStaleIfNeeded(db, new PlaylistLoader.Callback() {
                @Override public void onDone(int c) {
                    runOnUiThread(() -> { hideSyncStatus(); Toast.makeText(TvMainActivity.this,
                        "✅ " + c + " chaînes", Toast.LENGTH_SHORT).show(); });
                }
                @Override public void onError(String msg) {
                    runOnUiThread(() -> { hideSyncStatus(); Toast.makeText(TvMainActivity.this,
                        "Aucune mise à jour disponible", Toast.LENGTH_SHORT).show(); });
                }
            });
        }
    }

    // ── Dialog ajout playlist manuelle ────────────────────────────

    private void showAddPlaylistDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        b.setTitle("➕ Ajouter une playlist");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        RadioGroup rgType = new RadioGroup(this);
        rgType.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbUrl = new RadioButton(this); rbUrl.setText("URL M3U"); rbUrl.setId(1);
        RadioButton rbXt  = new RadioButton(this); rbXt.setText("Xtream");  rbXt.setId(2);
        rbUrl.setChecked(true);
        rgType.addView(rbUrl); rgType.addView(rbXt);
        layout.addView(rgType);

        EditText etName = new EditText(this); etName.setHint("Nom (optionnel)");
        EditText etUrl  = new EditText(this); etUrl.setHint("URL M3U : http://...");
        EditText etSrv  = new EditText(this); etSrv.setHint("Serveur Xtream : http://..."); etSrv.setVisibility(View.GONE);
        EditText etUser = new EditText(this); etUser.setHint("Login Xtream");  etUser.setVisibility(View.GONE);
        EditText etPass = new EditText(this); etPass.setHint("Mot de passe");
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setVisibility(View.GONE);

        layout.addView(etName); layout.addView(etUrl);
        layout.addView(etSrv); layout.addView(etUser); layout.addView(etPass);

        rgType.setOnCheckedChangeListener((g, id) -> {
            boolean isXt = id == 2;
            etUrl.setVisibility(isXt ? View.GONE : View.VISIBLE);
            etSrv.setVisibility(isXt ? View.VISIBLE : View.GONE);
            etUser.setVisibility(isXt ? View.VISIBLE : View.GONE);
            etPass.setVisibility(isXt ? View.VISIBLE : View.GONE);
        });

        AlertDialog dialog = b.create();
        dialog.setView(layout);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Charger", (AlertDialog.OnClickListener) null);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Annuler", (d2, w) -> {});
        dialog.show();

        // Override du clic positif pour valider avant de fermer
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            int cid = rgType.getCheckedRadioButtonId();
            PlaylistEntity pl = new PlaylistEntity();
            pl.name = name.isEmpty() ? "Playlist" : name;
            pl.lastUpdated = 0; pl.isActive = true;

            if (cid == 2) {
                pl.type     = PlaylistEntity.TYPE_XTREAM;
                pl.url      = etSrv.getText().toString().trim();
                pl.username = etUser.getText().toString().trim();
                pl.password = etPass.getText().toString().trim();
                if (pl.url.isEmpty() || pl.username.isEmpty()) {
                    Toast.makeText(this, "Serveur et login requis", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                pl.type = PlaylistEntity.TYPE_M3U_URL;
                pl.url  = etUrl.getText().toString().trim();
                if (pl.url.isEmpty()) {
                    Toast.makeText(this, "URL requise", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            dialog.dismiss();
            showSyncStatus("Chargement de " + pl.name + "…");

            Executors.newSingleThreadExecutor().execute(() -> {
                pl.id = db.playlistDao().insert(pl);
                PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
                    @Override public void onDone(int count) {
                        runOnUiThread(() -> { hideSyncStatus(); Toast.makeText(TvMainActivity.this,
                            count + " chaînes ✅", Toast.LENGTH_SHORT).show(); });
                    }
                    @Override public void onError(String msg) {
                        runOnUiThread(() -> { hideSyncStatus(); Toast.makeText(TvMainActivity.this,
                            "Erreur : " + msg, Toast.LENGTH_LONG).show(); });
                    }
                });
            });
        });
    }

    // ── Statut sync ───────────────────────────────────────────────

    private void showSyncStatus(String msg) {
        if (tvSyncStatus != null) { tvSyncStatus.setText(msg); tvSyncStatus.setVisibility(View.VISIBLE); }
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
    }

    private void hideSyncStatus() {
        if (tvSyncStatus != null) tvSyncStatus.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    // ── Player ────────────────────────────────────────────────────

    private void openPlayer(ChannelEntity ch) {
        saveRecent(ch.id);
        Intent i = new Intent(this, TvPlayerActivity.class);
        i.putExtra(TvPlayerActivity.EXTRA_ID,    ch.id);
        i.putExtra(TvPlayerActivity.EXTRA_NAME,  ch.name);
        i.putExtra(TvPlayerActivity.EXTRA_URL,   ch.streamUrl);
        i.putExtra(TvPlayerActivity.EXTRA_TYPE,  ch.contentType);
        i.putExtra(TvPlayerActivity.EXTRA_GROUP, ch.groupTitle);
        i.putExtra(TvPlayerActivity.EXTRA_ORDER, ch.sortOrder);
        startActivity(i);
    }
}
package com.wdesign.wiseiptv.mobile.ui;

import android.content.*;
import android.os.*;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.tabs.TabLayout;
import com.wdesign.wiseiptv.core.db.AppDatabase;
import com.wdesign.wiseiptv.core.db.entity.ChannelEntity;
import com.wdesign.wiseiptv.core.db.entity.PlaylistEntity;
import com.wdesign.wiseiptv.mobile.R;
import com.wdesign.wiseiptv.mobile.adapter.ChannelAdapter;
import com.wdesign.wiseiptv.mobile.util.PlaylistLoader;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * MainActivity — s'ouvre DIRECTEMENT depuis ActivationActivity.
 * Le téléchargement des playlists se fait ici, en background, DNS par DNS.
 *
 * Flux identique à TvMainActivity :
 *  1. Lit les extras passés par ActivationActivity (login + dnsUrls)
 *  2. Sinon lit les SharedPreferences "wise_activation" (reboot / rotation)
 *  3. Sinon refresh stale des playlists manuelles
 *  4. processNextDns() : séquentiel, auto-détect Xtream vs M3U URL
 */
public class MainActivity extends AppCompatActivity implements ChannelAdapter.OnChannelClick {

    // Extras reçus depuis ActivationActivity
    public static final String EXTRA_LOGIN    = "act_login";
    public static final String EXTRA_PASSWORD = "act_password";
    public static final String EXTRA_EXPIRES  = "act_expires";
    public static final String EXTRA_DNS_URLS = "act_dns_urls";
    public static final String EXTRA_DNS_EPGS = "act_dns_epg_urls";

    // Alias attendus par ActivationActivity
    public static final String EXTRA_ACT_LOGIN       = EXTRA_LOGIN;
    public static final String EXTRA_ACT_PASSWORD    = EXTRA_PASSWORD;
    public static final String EXTRA_ACT_EXPIRES     = EXTRA_EXPIRES;
    public static final String EXTRA_ACT_DNS_URLS    = EXTRA_DNS_URLS;
    public static final String EXTRA_ACT_DNS_EPG_URLS = EXTRA_DNS_EPGS;

    private static final String TAG   = "MainActivity";
    private static final String PREFS = "wise_activation";

    private RecyclerView          rvChannels;
    private ChannelAdapter        adapter;
    private ProgressBar           progressBar;
    private TextView              tvEmpty, tvSyncStatus;
    private TabLayout             tabLayout;
    private BottomNavigationView  bottomNav;
    private SearchView            searchView;
    private AppDatabase           db;
    private int                   currentTab = 0;

    // Executor unique pour toute la chaîne processNextDns — évite conflits Room multi-thread
    private final java.util.concurrent.ExecutorService syncExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db           = AppDatabase.get(this);
        rvChannels   = findViewById(R.id.rv_channels);
        progressBar  = findViewById(R.id.progress_bar);
        tvEmpty      = findViewById(R.id.tv_empty);
        tvSyncStatus = findViewById(R.id.tv_sync_status);
        tabLayout    = findViewById(R.id.tab_layout);
        bottomNav    = findViewById(R.id.bottom_nav);
        searchView   = findViewById(R.id.search_view);

        adapter = new ChannelAdapter(this);
        rvChannels.setLayoutManager(new GridLayoutManager(this, 3));
        rvChannels.setAdapter(adapter);

        setupTabs();
        setupSearch();
        setupBottomNav();
        observeCurrentTab();
        startBackgroundSync();
    }

    // ── Sync background ───────────────────────────────────────────

    private void startBackgroundSync() {
        Intent intent = getIntent();

        // Chemin 1 : extras frais depuis ActivationActivity
        String login    = intent.getStringExtra(EXTRA_LOGIN);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        String[] dnsUrls = intent.getStringArrayExtra(EXTRA_DNS_URLS);
        String[] dnsEpgs = intent.getStringArrayExtra(EXTRA_DNS_EPGS);

        if (login != null && dnsUrls != null && dnsUrls.length > 0) {
            Log.d(TAG, "Sync ch1: extras DNS=" + dnsUrls.length);
            syncExecutor.execute(() ->
                processNextDns(0, dnsUrls, login,
                    password != null ? password : "",
                    dnsEpgs != null ? dnsEpgs : new String[0], 0));
            return;
        }

        // Chemin 2 : SharedPreferences (reboot / rotation)
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedStatus = prefs.getString("status",   "UNKNOWN");
        String savedLogin  = prefs.getString("login",    null);
        String savedPass   = prefs.getString("password", "");
        String savedDnsRaw = prefs.getString("dns_urls", "");

        if ("ACTIVE".equals(savedStatus) && savedLogin != null && !savedDnsRaw.isEmpty()) {
            // Vérifier si DB déjà peuplée — éviter re-sync inutile
            syncExecutor.execute(() -> {
                boolean empty = (db.channelDao().count() == 0);
                long lastSync = prefs.getLong("last_sync_ts", 0);
                long weekMs   = 7L * 24 * 60 * 60 * 1000;
                boolean stale = (System.currentTimeMillis() - lastSync) > weekMs;
                if (!empty && !stale) {
                    Log.d(TAG, "Sync ch2: DB ok + sync récente, skip");
                    return;
                }
                Log.d(TAG, "Sync ch2: prefs dns=" + savedDnsRaw);
                String[] urls = savedDnsRaw.split(",");
                String[] epgs = prefs.getString("dns_epg_urls", "").split(",");
                processNextDns(0, urls, savedLogin, savedPass, epgs, 0);
            });
            return;
        }

        // Chemin 3 : playlists manuelles stale
        Log.d(TAG, "Sync ch3: stale refresh");
        PlaylistLoader.refreshStaleIfNeeded(db, new PlaylistLoader.Callback() {
            @Override public void onDone(int c) {
                if (c > 0 && !isFinishing() && !isDestroyed())
                    runOnUiThread(MainActivity.this::observeCurrentTab);
            }
            @Override public void onError(String msg) { Log.w(TAG, "stale: " + msg); }
        });
    }

    /**
     * Traitement séquentiel DNS — identique à TvMainActivity.
     * Appels DB via syncExecutor uniquement → pas de conflit Room.
     * PlaylistLoader.load() callbacks re-postés sur syncExecutor.
     */
    private void processNextDns(int index, String[] dnsUrls, String login,
                                 String password, String[] epgUrls, int accumulated) {
        if (index >= dnsUrls.length) {
            // Tous les DNS traités
            final int total = accumulated;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showSync(false, null);
                observeCurrentTab();
                if (total > 0)
                    Toast.makeText(this, "✅ " + total + " chaînes chargées",
                        Toast.LENGTH_SHORT).show();
                else {
                    // DB vide → proposer ajout manuel
                    syncExecutor.execute(() -> {
                        if (db.channelDao().count() == 0)
                            runOnUiThread(this::showAddPlaylistDialog);
                    });
                }
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("last_sync_ts", System.currentTimeMillis()).apply();
            });
            return;
        }

        String url = dnsUrls[index].trim();
        if (url.isEmpty()) {
            processNextDns(index + 1, dnsUrls, login, password, epgUrls, accumulated);
            return;
        }

        final int idx   = index + 1;
        final int total = dnsUrls.length;
        runOnUiThread(() -> showSync(true, "📥 Serveur " + idx + "/" + total + "…"));

        // findOrCreatePlaylist s'exécute sur syncExecutor (déjà le cas ici)
        PlaylistEntity pl = findOrCreatePlaylist(login, password, url, idx);

        PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
            @Override public void onDone(int count) {
                Log.d(TAG, "DNS " + idx + " OK: " + count + " ch");
                // Re-poster sur syncExecutor — évite appel DB sur thread PlaylistLoader
                syncExecutor.execute(() ->
                    processNextDns(index + 1, dnsUrls, login, password, epgUrls,
                        accumulated + count));
            }
            @Override public void onError(String msg) {
                Log.w(TAG, "DNS " + idx + " err: " + msg);
                syncExecutor.execute(() ->
                    processNextDns(index + 1, dnsUrls, login, password, epgUrls, accumulated));
            }
        });
    }

    /**
     * Auto-détecte Xtream (get.php / username= / base serveur) vs M3U URL (.m3u/.m3u8).
     * Exemples :
     *   https://tvradiozap.eu/get.php?username=x&password=y  → XTREAM
     *   https://iptv-org.github.io/iptv/languages/eng.m3u    → M3U URL
     */
    private PlaylistEntity findOrCreatePlaylist(String login, String password,
                                                 String dnsUrl, int idx) {
        // Chercher une playlist existante avec cette URL + login
        PlaylistEntity existing = db.playlistDao().findByUrlAndLogin(dnsUrl, login);
        if (existing != null) {
            existing.password = password;
            existing.isActive = true;
            db.playlistDao().update(existing);
            return existing;
        }
        PlaylistEntity pl = new PlaylistEntity();
        String low = dnsUrl.toLowerCase();
        // Détecter M3U : se termine par .m3u ou .m3u8, ou contient "m3u" SANS "get.php"/"username"
        boolean isM3uUrl = low.endsWith(".m3u") || low.endsWith(".m3u8")
            || (low.contains("m3u") && !low.contains("get.php") && !low.contains("username="));
        if (isM3uUrl) {
            pl.type = PlaylistEntity.TYPE_M3U_URL;
            pl.name = "M3U #" + idx;
        } else {
            // Tout le reste → Xtream (get.php, username=, base serveur)
            pl.type = PlaylistEntity.TYPE_XTREAM;
            pl.name = "IPTV #" + idx;
        }
        pl.url      = dnsUrl;
        pl.username = login;
        pl.password = password;
        pl.isActive = true;
        pl.lastUpdated = 0;
        pl.id = db.playlistDao().insert(pl);
        return pl;
    }

    // ── UI helpers ────────────────────────────────────────────────

    private void showSync(boolean show, String msg) {
        if (progressBar  != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tvSyncStatus != null) {
            tvSyncStatus.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && msg != null) tvSyncStatus.setText(msg);
        }
        if (!show && tvEmpty != null) tvEmpty.setVisibility(View.GONE);
    }

    // ── Tabs, search, nav ─────────────────────────────────────────

    private void setupTabs() {
        String[] tabs = {"Tout","Live","Films","Séries","Favoris"};
        for (String t : tabs) tabLayout.addTab(tabLayout.newTab().setText(t));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab t) {
                currentTab = t.getPosition(); observeCurrentTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab t) {}
            @Override public void onTabReselected(TabLayout.Tab t) {}
        });
    }

    private void observeCurrentTab() {
        switch (currentTab) {
            case 0: db.channelDao().getAll().observe(this, this::updateList); break;
            case 1: db.channelDao().getLive().observe(this, this::updateList); break;
            case 2: db.channelDao().getFilms().observe(this, this::updateList); break;
            case 3: db.channelDao().getSeries().observe(this, this::updateList); break;
            case 4: db.channelDao().getFavorites().observe(this, this::updateList); break;
        }
    }

    private void updateList(List<ChannelEntity> list) {
        if (adapter != null) adapter.setData(list);
        if (tvEmpty != null)
            tvEmpty.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setupSearch() {
        if (searchView == null) return;
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) { return false; }
            @Override public boolean onQueryTextChange(String q) {
                if (TextUtils.isEmpty(q)) { observeCurrentTab(); return true; }
                db.channelDao().search(q).observe(MainActivity.this, MainActivity.this::updateList);
                return true;
            }
        });
    }

    private void setupBottomNav() {
        if (bottomNav == null) return;
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if      (id == R.id.nav_home)    { observeCurrentTab(); return true; }
            else if (id == R.id.nav_add)     { showAddPlaylistDialog(); return true; }
            else if (id == R.id.nav_settings){ startActivity(new Intent(this, SettingsActivity.class)); return true; }
            return false;
        });
    }

    // ── Dialog ajout playlist manuel ──────────────────────────────

    public void showAddPlaylistDialog() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("➕ Ajouter une playlist");
        android.view.View v = getLayoutInflater().inflate(R.layout.dialog_add_playlist, null);
        EditText etName = v.findViewById(R.id.et_playlist_name);
        EditText etUrl  = v.findViewById(R.id.et_playlist_url);
        b.setView(v);
        b.setPositiveButton("Charger", (d, w) -> {
            String name = etName.getText().toString().trim();
            String url  = etUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url)) return;
            showSync(true, "Chargement…");
            syncExecutor.execute(() -> {
                PlaylistEntity pl = new PlaylistEntity();
                pl.name = name.isEmpty() ? "Playlist" : name;
                String low = url.toLowerCase();
                pl.type = (low.endsWith(".m3u") || low.endsWith(".m3u8"))
                    ? PlaylistEntity.TYPE_M3U_URL : PlaylistEntity.TYPE_XTREAM;
                pl.url = url; pl.isActive = true; pl.lastUpdated = 0;
                pl.id = db.playlistDao().insert(pl);
                PlaylistLoader.load(pl, db, new PlaylistLoader.Callback() {
                    @Override public void onDone(int c) {
                        runOnUiThread(() -> {
                            showSync(false, null); observeCurrentTab();
                            Toast.makeText(MainActivity.this, c + " chaînes ✅",
                                Toast.LENGTH_SHORT).show();
                        });
                    }
                    @Override public void onError(String msg) {
                        runOnUiThread(() -> {
                            showSync(false, null);
                            Toast.makeText(MainActivity.this, "Erreur : " + msg,
                                Toast.LENGTH_LONG).show();
                        });
                    }
                });
            });
        });
        b.setNegativeButton("Annuler", null);
        b.show();
    }

    // ── Clic sur une chaîne ───────────────────────────────────────

    @Override
    public void onClick(ChannelEntity ch) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ID,   ch.id);
        i.putExtra(PlayerActivity.EXTRA_NAME, ch.name);
        i.putExtra(PlayerActivity.EXTRA_URL,  ch.streamUrl);
        i.putExtra(PlayerActivity.EXTRA_TYPE, ch.contentType);
        startActivity(i);
    }

    @Override protected void onResume() { super.onResume(); observeCurrentTab(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        syncExecutor.shutdownNow();
    }
}
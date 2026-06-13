package com.wdesign.wiseiptv.mobile.ui;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.wdesign.wiseiptv.mobile.R;
public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);
        Button btnAdd = findViewById(R.id.btn_add_playlist);
        if (btnAdd != null) btnAdd.setOnClickListener(v ->
            startActivity(new android.content.Intent(this, MainActivity.class)));
    }
}

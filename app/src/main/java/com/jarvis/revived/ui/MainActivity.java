package com.jarvis.revived.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jarvis.core.llm.ChatModel;
import com.jarvis.revived.JarvisApp;
import com.jarvis.revived.R;
import com.jarvis.revived.assistant.AssistantController;

import java.util.ArrayList;
import java.util.List;

/**
 * Single screen: conversation, type box, Talk / Hands-free / Stop, model import
 * and the Memory screen. Asks for the microphone only when the user first
 * taps Talk, and for optional permissions once.
 */
public final class MainActivity extends Activity implements AssistantController.Listener {

    private static final int REQ_MIC_TALK = 10;
    private static final int REQ_MIC_HANDS_FREE = 11;
    private static final int REQ_OPTIONAL = 12;
    private static final int REQ_PICK_MODEL = 20;
    private static final String PREFS = "jarvis_ui";
    private static final String KEY_OPTIONAL_ASKED = "optional_permissions_asked";
    private static final int MAX_BUBBLES = 80;

    private AssistantController controller;
    private TextView tvStatus;
    private TextView tvModel;
    private LinearLayout transcript;
    private ScrollView scroll;
    private EditText etInput;
    /** Bubble that grows while the model is still writing; replaced by the final reply. */
    private TextView liveBubble;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        controller = ((JarvisApp) getApplication()).controller();

        tvStatus = findViewById(R.id.tvStatus);
        tvModel = findViewById(R.id.tvModel);
        transcript = findViewById(R.id.transcript);
        scroll = findViewById(R.id.scroll);
        etInput = findViewById(R.id.etInput);

        findViewById(R.id.btnSend).setOnClickListener(v -> sendTyped());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped();
                return true;
            }
            return false;
        });
        findViewById(R.id.btnTalk).setOnClickListener(v -> withMic(REQ_MIC_TALK));
        findViewById(R.id.btnHandsFree).setOnClickListener(v -> withMic(REQ_MIC_HANDS_FREE));
        findViewById(R.id.btnStop).setOnClickListener(v -> controller.stopAll());
        findViewById(R.id.btnImportModel).setOnClickListener(v -> pickModel());
        findViewById(R.id.btnMemory).setOnClickListener(v -> startActivity(new Intent(this, MemoryActivity.class)));

        // Speed tuning: long-press the model line to try 2 / 4 / 6 / 8 CPU threads.
        tvModel.setOnLongClickListener(v -> {
            int n = controller.cycleThreads();
            Toast.makeText(this, "Using " + n + " CPU threads. Ask something and compare the \"Last answer\" time.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.setUiVisible(true);
        transcript.removeAllViews(); // the controller replays the conversation
        liveBubble = null;
        controller.addListener(this);
    }

    @Override
    protected void onPause() {
        controller.setUiVisible(false);
        controller.removeListener(this);
        super.onPause();
    }

    // ------------------------------------------------------------- controls

    private void sendTyped() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        controller.submitText(text);
    }

    private void withMic(int requestCode) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onMicReady(requestCode);
            return;
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("mic_denied_once", false)) {
            // Android stopped showing the prompt ("don't ask again"): send the user to settings.
            Toast.makeText(this, "Allow Microphone for Jarvis in Settings → Permissions", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && requestCode == REQ_MIC_HANDS_FREE) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(perms.toArray(new String[0]), requestCode);
    }

    private void onMicReady(int requestCode) {
        if (requestCode == REQ_MIC_HANDS_FREE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_OPTIONAL);
            }
            controller.startHandsFree();
        } else {
            controller.listenOnce();
        }
        askOptionalPermissionsOnce();
    }

    /** Contacts + phone: needed for "call Ravi". Asked once; Jarvis works without them. */
    private void askOptionalPermissionsOnce() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_OPTIONAL_ASKED, false)) return;
        prefs.edit().putBoolean(KEY_OPTIONAL_ASKED, true).apply();
        List<String> perms = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_CONTACTS);
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CALL_PHONE);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_OPTIONAL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC_TALK && requestCode != REQ_MIC_HANDS_FREE) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onMicReady(requestCode);
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("mic_denied_once", true).apply();
            Toast.makeText(this, "Without the microphone you can still type to Jarvis.", Toast.LENGTH_LONG).show();
        }
    }

    private void pickModel() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, REQ_PICK_MODEL);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK && data != null && data.getData() != null) {
            controller.importModel(data.getData());
        }
    }

    // ------------------------------------------------------ controller events

    @Override
    public void onStatus(AssistantController.Status status, String detail) {
        String label;
        switch (status) {
            case LISTENING: label = detail.isEmpty() ? "Listening…" : "“" + detail + "”"; break;
            case THINKING: label = detail.isEmpty() ? "Thinking…" : detail; break;
            case SPEAKING: label = "Speaking"; break;
            default: label = controller.isHandsFree() ? "Hands-free" : (detail.isEmpty() ? "" : detail);
        }
        tvStatus.setText(label);
    }

    @Override
    public void onTranscript(boolean fromUser, String text) {
        if (!fromUser && liveBubble != null) {
            liveBubble.setText(text);
            liveBubble = null;
            return;
        }
        liveBubble = null;
        addBubble(fromUser, text);
    }

    @Override
    public void onReplyProgress(String textSoFar) {
        if (liveBubble == null) {
            liveBubble = addBubble(false, textSoFar);
        } else {
            liveBubble.setText(textSoFar);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onModelState(ChatModel.State state, String detail) {
        String prefix;
        switch (state) {
            case READY: prefix = "Brain: ready"; break;
            case LOADING: prefix = "Brain: loading"; break;
            case FAILED: prefix = "Brain: failed"; break;
            default: prefix = "Brain: no model";
        }
        tvModel.setText(detail == null || detail.isEmpty() ? prefix : prefix + " · " + detail);
    }

    // ------------------------------------------------------------------ view

    private TextView addBubble(boolean fromUser, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(getColor(R.color.text));
        int pad = dp(10);
        tv.setPadding(pad, dp(8), pad, dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(getColor(fromUser ? R.color.user_bubble : R.color.jarvis_bubble));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.gravity = fromUser ? Gravity.END : Gravity.START;
        if (fromUser) lp.leftMargin = dp(48); else lp.rightMargin = dp(48);
        transcript.addView(tv, lp);

        while (transcript.getChildCount() > MAX_BUBBLES) transcript.removeViewAt(0);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

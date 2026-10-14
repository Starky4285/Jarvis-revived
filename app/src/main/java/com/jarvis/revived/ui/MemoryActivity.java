package com.jarvis.revived.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.jarvis.core.memory.Fact;
import com.jarvis.core.memory.MemoryStore;
import com.jarvis.core.memory.ProtectedItem;
import com.jarvis.revived.JarvisApp;
import com.jarvis.revived.R;
import com.jarvis.revived.assistant.AssistantController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Everything Jarvis remembers, in plain sight. Tap a protected item to reveal
 * it, long-press any item to forget it. Database work runs on the assistant's
 * background thread.
 */
public final class MemoryActivity extends Activity {

    private static final String MASK = "••••••";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private MemoryStore store;
    private ExecutorService worker;
    private TextView hint;

    private static final class Row {
        final String key;
        final boolean secret;
        final String label;
        final String value;

        Row(String key, boolean secret, String label, String value) {
            this.key = key;
            this.secret = secret;
            this.label = label;
            this.value = value;
        }

        String display(boolean reveal) {
            if (key.startsWith(Fact.NOTE_PREFIX)) return "Note: " + value;
            return label + ": " + (secret && !reveal ? MASK : value) + (secret ? "  (protected)" : "");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory);

        AssistantController controller = ((JarvisApp) getApplication()).controller();
        store = controller.memory().store();
        worker = controller.brainThread();

        hint = findViewById(R.id.tvMemoryHint);
        ListView list = findViewById(R.id.listMemory);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.secret) {
                new AlertDialog.Builder(this)
                        .setTitle(row.label)
                        .setMessage(row.value.isEmpty() ? "(could not decrypt)" : row.value)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            new AlertDialog.Builder(this)
                    .setMessage("Forget \"" + row.display(false) + "\"?")
                    .setPositiveButton("Forget", (d, w) -> worker.execute(() -> {
                        if (row.secret) store.deleteProtected(row.key); else store.deleteKey(row.key);
                        reload();
                    }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        findViewById(R.id.btnForgetAll).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.forget_everything)
                .setMessage("This erases every fact, note, protected item and the conversation log. It can't be undone.")
                .setPositiveButton("Erase", (d, w) -> worker.execute(() -> {
                    store.deleteAll();
                    reload();
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        worker.execute(this::reload);
    }

    /** Runs on the worker thread. */
    private void reload() {
        List<Row> fresh = new ArrayList<>();
        for (Fact f : store.activeFacts()) fresh.add(new Row(f.key, false, f.label, f.value));
        for (ProtectedItem p : store.listProtected()) fresh.add(new Row(p.key, true, p.label, p.value));
        main.post(() -> {
            rows.clear();
            rows.addAll(fresh);
            adapter.clear();
            for (Row r : rows) adapter.add(r.display(false));
            adapter.notifyDataSetChanged();
            hint.setText(rows.isEmpty()
                    ? "Nothing yet. Say \"remember my birthday is 5 May\" or \"my name is …\"."
                    : rows.size() + " item(s). Tap a protected item to reveal it. Long-press to forget.");
        });
    }
}

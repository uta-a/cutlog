package dev.utaa.cutlog.extension.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 文字の追加と、選んでいる文字の色・サイズ・削除。 */
final class TextPanel extends ToolPanel {
    private static final int[] COLORS = {
        Color.WHITE, Color.BLACK, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, Ui.ACCENT,
    };
    private static final float MIN_SIZE = 0.03f;
    private static final float MAX_SIZE = 0.20f;
    private static final float DEFAULT_SIZE = 0.08f;

    private final Context context;
    private final List<View> swatches = new ArrayList<>();
    /** 文字を選んでいる間だけ使える操作。 */
    private final List<View> selectionControls = new ArrayList<>();
    private SeekBar sizeBar;

    TextPanel(Context context, EditorHost host) {
        super(host);
        this.context = context;
    }

    @Override
    protected View build() {
        LinearLayout panel = Ui.column(context);

        LinearLayout inputRow = Ui.row(context);
        EditText input = new EditText(context);
        input.setHint("テキストを入力");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextColor(Ui.TEXT_PRIMARY);
        input.setHintTextColor(Ui.TEXT_SECONDARY);
        input.setTextSize(15);
        input.setBackground(Ui.pill(Ui.SURFACE));
        input.setPadding(Ui.dp(context, 18), Ui.dp(context, 11), Ui.dp(context, 18), Ui.dp(context, 11));
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = Ui.pillButton(context, "追加");
        add.setBackground(Ui.pill(Ui.ACCENT));
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setOnClickListener(v -> add(input));
        input.setOnEditorActionListener((view, actionId, event) -> {
            add(input);
            return true;
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.leftMargin = Ui.dp(context, 8);
        inputRow.addView(add, addParams);
        panel.addView(inputRow);

        LinearLayout colors = Ui.row(context);
        colors.setPadding(0, Ui.dp(context, 12), 0, 0);
        for (int color : COLORS) {
            View swatch = new View(context);
            swatch.setTag(color);
            swatch.setOnClickListener(v -> {
                TextSpec selected = selectedText();
                if (selected != null) {
                    selected.color = color;
                    refreshSwatches(selected);
                    host.layersChanged();
                }
            });
            int size = Ui.dp(context, 30);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = Ui.dp(context, 12);
            colors.addView(swatch, params);
            swatches.add(swatch);
            selectionControls.add(swatch);
        }
        panel.addView(colors);

        LinearLayout sizeRow = Ui.row(context);
        sizeRow.setPadding(0, Ui.dp(context, 8), 0, 0);
        TextView sizeLabel = Ui.text(context, "サイズ", 14, Ui.TEXT_SECONDARY, false);
        sizeLabel.setMinWidth(Ui.dp(context, 64));
        sizeRow.addView(sizeLabel);
        sizeBar = Ui.seekBar(context, 100, 0);
        sizeBar.setOnSeekBarChangeListener(new Ui.SeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                TextSpec selected = selectedText();
                if (fromUser && selected != null) {
                    selected.size = MIN_SIZE + (MAX_SIZE - MIN_SIZE) * progress / 100f;
                    host.layersChanged();
                }
            }
        });
        sizeRow.addView(sizeBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView delete = Ui.pillButton(context, "削除");
        delete.setOnClickListener(v -> {
            TextSpec selected = selectedText();
            if (selected != null) {
                host.spec().layers.remove(selected);
                host.selectLayer(null);
                host.layersChanged();
            }
        });
        sizeRow.addView(delete);
        selectionControls.add(sizeBar);
        selectionControls.add(delete);
        panel.addView(sizeRow);

        TextView hint = Ui.text(context, "プレビューのテキストはドラッグで動かせます", 12, Ui.TEXT_SECONDARY, false);
        hint.setPadding(0, Ui.dp(context, 10), 0, 0);
        panel.addView(hint);
        onLayerSelected(host.selectedLayer());
        return panel;
    }

    private TextSpec selectedText() {
        Layer layer = host.selectedLayer();
        return layer instanceof TextSpec ? (TextSpec) layer : null;
    }

    private void add(EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        int count = 0;
        for (Layer layer : host.spec().layers) {
            if (layer instanceof TextSpec) {
                count++;
            }
        }
        float offset = 0.08f * (count % 5);
        TextSpec added = new TextSpec(text, Color.WHITE, DEFAULT_SIZE, 0.5f, 0.5f + offset);
        host.spec().layers.add(added);
        input.setText("");
        host.layersChanged();
        host.selectLayer(added);
    }

    @Override
    void onLayerSelected(Layer layer) {
        if (sizeBar == null) {
            return;
        }
        TextSpec selected = layer instanceof TextSpec ? (TextSpec) layer : null;
        for (View control : selectionControls) {
            control.setEnabled(selected != null);
            control.setAlpha(selected != null ? 1f : 0.35f);
        }
        refreshSwatches(selected);
        if (selected != null) {
            sizeBar.setProgress(Math.round((selected.size - MIN_SIZE) / (MAX_SIZE - MIN_SIZE) * 100f));
        }
    }

    private void refreshSwatches(TextSpec selected) {
        for (View swatch : swatches) {
            int color = (Integer) swatch.getTag();
            GradientDrawable shape = Ui.circle(color);
            boolean current = selected != null && selected.color == color;
            shape.setStroke(Ui.dp(context, current ? 3 : 1), current ? Ui.ACCENT : Ui.STROKE);
            swatch.setBackground(shape);
        }
    }
}

package dev.utaa.cutlog.extension.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 文字の追加と、選んでいる文字のフォント・色・スタイル・サイズ・角度・削除。 */
final class TextPanel extends ToolPanel {
    private static final int[] COLORS = {
        Color.WHITE, Color.BLACK, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, Ui.ACCENT,
    };
    /** 色の一覧で RGB を表す tag。 */
    private static final int RAINBOW = 0;
    private static final float DEFAULT_SIZE = 0.08f;

    private enum Style {
        OUTLINE("縁取り"),
        SHADOW("影"),
        BACKGROUND("背景"),
        NEON("ネオン");

        final String label;

        Style(String label) {
            this.label = label;
        }

        boolean get(TextSpec text) {
            switch (this) {
                case OUTLINE:
                    return text.outline;
                case SHADOW:
                    return text.shadow;
                case BACKGROUND:
                    return text.background;
                default:
                    return text.neon;
            }
        }

        void toggle(TextSpec text) {
            switch (this) {
                case OUTLINE:
                    text.outline = !text.outline;
                    break;
                case SHADOW:
                    text.shadow = !text.shadow;
                    break;
                case BACKGROUND:
                    text.background = !text.background;
                    break;
                default:
                    text.neon = !text.neon;
                    break;
            }
        }
    }

    private final Context context;
    private final List<View> swatches = new ArrayList<>();
    private final List<TextView> fontChips = new ArrayList<>();
    private final List<TextView> styleChips = new ArrayList<>();
    /** 文字を選んでいる間だけ使える操作。 */
    private final List<View> selectionControls = new ArrayList<>();
    private SeekBar sizeBar;
    private SeekBar angleBar;
    private TextView angleLabel;

    TextPanel(Context context, EditorHost host) {
        super(host);
        this.context = context;
    }

    @Override
    protected View build() {
        LinearLayout panel = Ui.column(context);
        panel.addView(buildInputRow());
        panel.addView(chipRow(buildFontChips()), spaced());
        panel.addView(buildColors(), spaced());
        panel.addView(chipRow(buildStyleChips()), spaced());
        panel.addView(buildSizeRow(), spaced());
        panel.addView(buildAngleRow());

        LinearLayout footer = Ui.row(context);
        footer.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8));
        TextView hint = Ui.text(context, "ドラッグで移動、2 本指で拡大・回転", 12, Ui.TEXT_SECONDARY, false);
        footer.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView delete = Ui.pillButton(context, "削除");
        delete.setOnClickListener(v -> {
            TextSpec selected = selectedText();
            if (selected != null) {
                host.spec().layers.remove(selected);
                host.selectLayer(null);
                host.layersChanged();
            }
        });
        footer.addView(delete);
        selectionControls.add(delete);
        panel.addView(footer);

        onLayerSelected(host.selectedLayer());
        return panel;
    }

    private View buildInputRow() {
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
        return inputRow;
    }

    /** 各フォントの名前を、そのフォントで書いたチップ。フォントは裏で読んでから当てる。 */
    private List<TextView> buildFontChips() {
        Handler main = new Handler(Looper.getMainLooper());
        for (TextFont font : TextFont.values()) {
            TextView chip = Ui.pillButton(context, font.label);
            chip.setTag(font);
            chip.setOnClickListener(v -> {
                TextSpec selected = selectedText();
                if (selected != null) {
                    selected.font = font;
                    refresh(selected);
                    host.layersChanged();
                }
            });
            fontChips.add(chip);
            selectionControls.add(chip);
            new Thread(() -> {
                Typeface typeface = font.typeface();
                main.post(() -> chip.setTypeface(typeface));
            }, "cutlog-font-chip").start();
        }
        return fontChips;
    }

    private List<TextView> buildStyleChips() {
        for (Style style : Style.values()) {
            TextView chip = Ui.pillButton(context, style.label);
            chip.setTag(style);
            chip.setOnClickListener(v -> {
                TextSpec selected = selectedText();
                if (selected != null) {
                    style.toggle(selected);
                    refresh(selected);
                    host.layersChanged();
                }
            });
            styleChips.add(chip);
            selectionControls.add(chip);
        }
        return styleChips;
    }

    private View chipRow(List<TextView> chips) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        for (TextView chip : chips) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Ui.dp(context, 8);
            row.addView(chip, params);
        }
        scroll.addView(row);
        return scroll;
    }

    /** 7 色と、色相が回る RGB。 */
    private View buildColors() {
        LinearLayout colors = Ui.row(context);
        int size = Ui.dp(context, 30);
        for (int i = 0; i <= COLORS.length; i++) {
            boolean rainbow = i == COLORS.length;
            int color = rainbow ? RAINBOW : COLORS[i];
            View swatch = new View(context);
            swatch.setTag(color);
            swatch.setContentDescription(rainbow ? "RGB" : null);
            swatch.setOnClickListener(v -> {
                TextSpec selected = selectedText();
                if (selected != null) {
                    selected.rainbow = rainbow;
                    if (!rainbow) {
                        selected.color = color;
                    }
                    refresh(selected);
                    host.layersChanged();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = Ui.dp(context, 12);
            colors.addView(swatch, params);
            swatches.add(swatch);
            selectionControls.add(swatch);
        }
        return colors;
    }

    private View buildSizeRow() {
        LinearLayout row = Ui.row(context);
        row.addView(label("サイズ"));
        sizeBar = Ui.seekBar(context, 100, 0);
        sizeBar.setOnSeekBarChangeListener(new Ui.SeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                TextSpec selected = selectedText();
                if (fromUser && selected != null) {
                    selected.size = LayerOverlayView.MIN_SIZE
                            + (LayerOverlayView.MAX_SIZE - LayerOverlayView.MIN_SIZE) * progress / 100f;
                    host.layersChanged();
                }
            }
        });
        row.addView(sizeBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectionControls.add(sizeBar);
        return row;
    }

    /** -180〜180 度。ラベルをタップすると 0 度に戻す。 */
    private View buildAngleRow() {
        LinearLayout row = Ui.row(context);
        row.setPadding(0, Ui.dp(context, 4), 0, 0);
        angleLabel = label("角度");
        angleLabel.setOnClickListener(v -> {
            TextSpec selected = selectedText();
            if (selected != null) {
                selected.rotation = 0f;
                refresh(selected);
                host.layersChanged();
            }
        });
        row.addView(angleLabel);
        angleBar = Ui.seekBar(context, 360, 180);
        angleBar.setOnSeekBarChangeListener(new Ui.SeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                TextSpec selected = selectedText();
                if (fromUser && selected != null) {
                    selected.rotation = progress - 180;
                    angleLabel.setText(angleText(selected.rotation));
                    host.layersChanged();
                }
            }
        });
        row.addView(angleBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectionControls.add(angleBar);
        selectionControls.add(angleLabel);
        return row;
    }

    private TextView label(String text) {
        TextView label = Ui.text(context, text, 14, Ui.TEXT_SECONDARY, false);
        label.setMinWidth(Ui.dp(context, 64));
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private static String angleText(float rotation) {
        return String.format(Locale.ROOT, "角度 %d°", Math.round(rotation));
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(context, 10);
        return params;
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
        // 直前に選んでいた文字のフォントとスタイルを引き継ぐ。続けて同じ見た目で足せるように。
        TextSpec previous = selectedText();
        if (previous != null) {
            added.font = previous.font;
            added.color = previous.color;
            added.outline = previous.outline;
            added.shadow = previous.shadow;
            added.background = previous.background;
            added.neon = previous.neon;
            added.rainbow = previous.rainbow;
        }
        host.spec().layers.add(added);
        input.setText("");
        // 追加したらキーボードを閉じ、プレビューで位置や見た目を確かめられるようにする。
        input.clearFocus();
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
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
        refresh(selected);
    }

    private void refresh(TextSpec selected) {
        for (View swatch : swatches) {
            int color = (Integer) swatch.getTag();
            boolean rainbow = color == RAINBOW;
            GradientDrawable shape = rainbow ? rainbowCircle() : Ui.circle(color);
            boolean current = selected != null && (rainbow ? selected.rainbow : !selected.rainbow && selected.color == color);
            shape.setStroke(Ui.dp(context, current ? 3 : 1), current ? Ui.ACCENT : Ui.STROKE);
            swatch.setBackground(shape);
        }
        for (TextView chip : fontChips) {
            Ui.setChipSelected(context, chip, selected != null && chip.getTag() == selected.font);
        }
        for (TextView chip : styleChips) {
            Ui.setChipSelected(context, chip, selected != null && ((Style) chip.getTag()).get(selected));
        }
        if (selected != null) {
            sizeBar.setProgress(Math.round((selected.size - LayerOverlayView.MIN_SIZE)
                    / (LayerOverlayView.MAX_SIZE - LayerOverlayView.MIN_SIZE) * 100f));
            angleBar.setProgress(Math.round(selected.rotation) + 180);
            angleLabel.setText(angleText(selected.rotation));
        } else {
            angleLabel.setText("角度");
        }
    }

    private static GradientDrawable rainbowCircle() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        shape.setColors(new int[] {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        return shape;
    }
}

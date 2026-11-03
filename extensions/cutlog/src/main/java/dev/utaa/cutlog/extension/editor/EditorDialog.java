package dev.utaa.cutlog.extension.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;
import dev.utaa.cutlog.extension.hook.CaptureReplacement;
import dev.utaa.cutlog.extension.hook.CaptureSession;
import dev.utaa.cutlog.extension.hook.VideoFiles;
import dev.utaa.cutlog.extension.pref.Prefs;
import dev.utaa.cutlog.extension.render.EffectsFactory;
import dev.utaa.cutlog.extension.render.Renderer;
import dev.utaa.cutlog.shaded.androidx.media3.common.MediaItem;
import dev.utaa.cutlog.shaded.androidx.media3.common.Player;
import dev.utaa.cutlog.shaded.androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 撮影直後に出す全画面の編集画面（F1〜F3）。
 *
 * <p>見た目は setlog の送信先選択画面に揃える。プレビュー左上の ✕ がキャンセル、右上のピンクの ↑ が
 * 「編集して投稿」、下の行が「そのまま投稿」。カメラ画面の Activity の上に重ねるだけなので、
 * 閉じれば本体の撮影画面の状態がそのまま残る。結果は {@link CaptureSession} を通して一度だけ本体へ返す。
 */
public final class EditorDialog extends Dialog {
    private static final int[] TEXT_COLORS = {
        Color.WHITE, Color.BLACK, 0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, Ui.ACCENT,
    };
    private static final float MIN_TEXT_SIZE = 0.03f;
    private static final float MAX_TEXT_SIZE = 0.20f;
    private static final float DEFAULT_TEXT_SIZE = 0.08f;

    private final CaptureSession session;
    private final File capturedFile;
    private final CaptureReplacement video;
    private final EditSpec spec = new EditSpec();

    private ExoPlayer player;
    private Renderer renderer;
    private TextureView previewTexture;
    private TextOverlayView overlay;
    private TextView sendButton;
    private ProgressBar sendProgress;
    private TextView closeButton;
    private View originalRow;
    private final List<TextView> filterChips = new ArrayList<>();
    private final List<View> colorSwatches = new ArrayList<>();
    private final List<View> textControls = new ArrayList<>();
    private SeekBar textSizeBar;

    public EditorDialog(Activity activity, CaptureSession session) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.session = session;
        this.capturedFile = session.capturedFile();
        try {
            this.video = VideoFiles.readMetadata(capturedFile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the capture.", e);
        }
        spec.filter = Prefs.lastFilter(activity);
        setCancelable(false);
        setContentView(buildContent(activity));
        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(Ui.BACKGROUND);
            window.setNavigationBarColor(Ui.BACKGROUND);
        }
        setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                confirmCancel();
                return true;
            }
            return false;
        });
        setOnDismissListener(dialog -> release());
    }

    @Override
    public void show() {
        super.show();
        startPreview();
    }

    @Override
    public void dismiss() {
        // 編集画面のキーボードが本体の画面に残らないよう、閉じる前に隠す。
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        super.dismiss();
    }

    // ---- 画面構成 ----

    private View buildContent(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        int gutter = Ui.dp(context, 12);
        root.setPadding(gutter, gutter, gutter, gutter);

        root.addView(buildPreview(context), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout panels = new FrameLayout(context);
        View[] tabPanels = {buildFilterPanel(context), buildAdjustPanel(context), buildTextPanel(context)};
        for (View panel : tabPanels) {
            panels.addView(panel);
        }
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = Ui.dp(context, 16);
        root.addView(buildTabs(context, tabPanels), tabsParams);
        root.addView(panels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 196)));

        originalRow = buildOriginalRow(context);
        root.addView(originalRow);
        return root;
    }

    /** 送信先選択画面と同じく、角丸の動画の上に ✕ と ↑ を重ねる。 */
    private View buildPreview(Context context) {
        FrameLayout area = new FrameLayout(context);
        AspectFrame frame = new AspectFrame(context, video.width / (float) video.height);
        float radius = Ui.dp(context, 28);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        frame.setBackgroundColor(Ui.SURFACE);

        previewTexture = new TextureView(context);
        frame.addView(previewTexture, matchParent());
        overlay = new TextOverlayView(context, spec, this::onTextSelected);
        frame.addView(overlay, matchParent());

        int buttonSize = Ui.dp(context, 44);
        int inset = Ui.dp(context, 14);
        closeButton = Ui.text(context, "✕", 18, Ui.TEXT_PRIMARY, true);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setBackground(Ui.circle(Ui.SCRIM));
        closeButton.setContentDescription("キャンセル");
        closeButton.setOnClickListener(v -> confirmCancel());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP | Gravity.START);
        closeParams.setMargins(inset, inset, inset, inset);
        frame.addView(closeButton, closeParams);

        FrameLayout send = new FrameLayout(context);
        sendButton = Ui.text(context, "↑", 22, Color.WHITE, true);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(Ui.circle(Ui.ACCENT));
        sendButton.setContentDescription("編集して投稿");
        sendButton.setOnClickListener(v -> exportAndDeliver());
        send.addView(sendButton, matchParent());
        sendProgress = new ProgressBar(context);
        sendProgress.setIndeterminateTintList(Ui.accentTint());
        sendProgress.setVisibility(View.GONE);
        send.addView(sendProgress, matchParent());
        int sendSize = Ui.dp(context, 52);
        FrameLayout.LayoutParams sendParams = new FrameLayout.LayoutParams(sendSize, sendSize, Gravity.TOP | Gravity.END);
        sendParams.setMargins(inset, inset, inset, inset);
        frame.addView(send, sendParams);

        // 送信先選択画面と同じく、プレビューは上に寄せる。
        area.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return area;
    }

    /** 「カメラ／ログ」の切り替えと同じ形のタブ。 */
    private View buildTabs(Context context, View[] panels) {
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        int padding = Ui.dp(context, 4);
        tabs.setPadding(padding, padding, padding, padding);
        tabs.setBackground(Ui.pill(Ui.SURFACE));
        String[] labels = {"フィルター", "調整", "テキスト"};
        List<TextView> buttons = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            TextView tab = Ui.text(context, labels[i], 15, Ui.TEXT_SECONDARY, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10));
            tab.setOnClickListener(v -> showTab(buttons, panels, index));
            buttons.add(tab);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        showTab(buttons, panels, 0);
        return tabs;
    }

    private View buildOriginalRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rounded(context, Ui.SURFACE, 22));
        int padding = Ui.dp(context, 18);
        row.setPadding(padding, Ui.dp(context, 14), padding, Ui.dp(context, 14));
        row.addView(Ui.text(context, "そのまま投稿", 16, Ui.TEXT_PRIMARY, true));
        TextView detail = Ui.text(context, "編集せずに撮影した動画で進みます", 13, Ui.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = Ui.dp(context, 4);
        row.addView(detail, detailParams);
        row.setClickable(true);
        row.setOnClickListener(v -> deliverOriginal());
        return row;
    }

    private View buildFilterPanel(Context context) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.HORIZONTAL);
        list.setGravity(Gravity.CENTER_VERTICAL);
        list.setPadding(0, Ui.dp(context, 16), 0, 0);
        for (FilterPreset preset : FilterPreset.values()) {
            TextView chip = Ui.pillButton(context, preset.label);
            chip.setTag(preset);
            chip.setOnClickListener(v -> {
                spec.filter = preset;
                refreshFilterChips();
                updatePreviewEffects();
            });
            filterChips.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Ui.dp(context, 8);
            list.addView(chip, params);
        }
        refreshFilterChips();
        scroll.addView(list);
        return scroll;
    }

    private View buildAdjustPanel(Context context) {
        LinearLayout panel = column(context);
        panel.addView(slider(context, "明るさ", value -> spec.brightness = value));
        panel.addView(slider(context, "コントラスト", value -> spec.contrast = value));
        panel.addView(slider(context, "彩度", value -> spec.saturation = value));
        return panel;
    }

    private View buildTextPanel(Context context) {
        LinearLayout panel = column(context);

        LinearLayout inputRow = row(context);
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
        add.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        add.setOnClickListener(v -> addText(input));
        input.setOnEditorActionListener((view, actionId, event) -> {
            addText(input);
            return true;
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.leftMargin = Ui.dp(context, 8);
        inputRow.addView(add, addParams);
        panel.addView(inputRow);

        LinearLayout colors = row(context);
        colors.setPadding(0, Ui.dp(context, 12), 0, 0);
        for (int color : TEXT_COLORS) {
            View swatch = new View(context);
            swatch.setTag(color);
            swatch.setOnClickListener(v -> {
                TextSpec selected = overlay.selected();
                if (selected != null) {
                    selected.color = color;
                    refreshSwatches(selected);
                    overlay.invalidate();
                }
            });
            int size = Ui.dp(context, 30);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = Ui.dp(context, 12);
            colors.addView(swatch, params);
            colorSwatches.add(swatch);
            textControls.add(swatch);
        }
        panel.addView(colors);

        LinearLayout sizeRow = row(context);
        sizeRow.setPadding(0, Ui.dp(context, 8), 0, 0);
        TextView sizeLabel = Ui.text(context, "サイズ", 14, Ui.TEXT_SECONDARY, false);
        sizeLabel.setMinWidth(Ui.dp(context, 64));
        sizeRow.addView(sizeLabel);
        textSizeBar = seekBar(context, 100, 0);
        textSizeBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                TextSpec selected = overlay.selected();
                if (fromUser && selected != null) {
                    selected.size = MIN_TEXT_SIZE + (MAX_TEXT_SIZE - MIN_TEXT_SIZE) * progress / 100f;
                    overlay.invalidate();
                }
            }
        });
        sizeRow.addView(textSizeBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView delete = Ui.pillButton(context, "削除");
        delete.setOnClickListener(v -> {
            TextSpec selected = overlay.selected();
            if (selected != null) {
                spec.texts.remove(selected);
                overlay.select(null);
            }
        });
        sizeRow.addView(delete);
        textControls.add(textSizeBar);
        textControls.add(delete);
        panel.addView(sizeRow);

        TextView hint = Ui.text(context, "プレビューのテキストはドラッグで動かせます", 12, Ui.TEXT_SECONDARY, false);
        hint.setPadding(0, Ui.dp(context, 10), 0, 0);
        panel.addView(hint);
        onTextSelected(null);
        return panel;
    }

    // ---- 操作 ----

    private void addText(EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        float offset = 0.08f * (spec.texts.size() % 5);
        TextSpec added = new TextSpec(text, Color.WHITE, DEFAULT_TEXT_SIZE, 0.5f, 0.5f + offset);
        spec.texts.add(added);
        input.setText("");
        overlay.select(added);
    }

    private void onTextSelected(TextSpec selected) {
        for (View control : textControls) {
            control.setEnabled(selected != null);
            control.setAlpha(selected != null ? 1f : 0.35f);
        }
        refreshSwatches(selected);
        if (selected != null && textSizeBar != null) {
            textSizeBar.setProgress(Math.round((selected.size - MIN_TEXT_SIZE) / (MAX_TEXT_SIZE - MIN_TEXT_SIZE) * 100f));
        }
    }

    private void confirmCancel() {
        if (renderer != null) {
            return;
        }
        showAlert(new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("撮影した動画を破棄して撮影画面を閉じますか？")
                .setPositiveButton("破棄する", (dialog, which) -> {
                    dismiss();
                    session.cancel();
                })
                .setNegativeButton("戻る", null));
    }

    private void deliverOriginal() {
        if (renderer != null) {
            return;
        }
        dismiss();
        session.deliverOriginal();
    }

    private void exportAndDeliver() {
        if (renderer != null) {
            return;
        }
        if (spec.isEmpty()) {
            deliverOriginal();
            return;
        }
        Prefs.setLastFilter(getContext(), spec.filter);
        setBusy(true);
        File output = new File(capturedFile.getParentFile(), capturedFile.getName() + ".cutlog.mp4");
        renderer = Renderer.start(getContext(), capturedFile, spec, video.width, video.height, output,
                new Renderer.Callback() {
                    @Override
                    public void onSuccess(File output) {
                        renderer = null;
                        try {
                            dismiss();
                            session.deliverEdited(output);
                        } catch (IOException e) {
                            output.delete();
                            showExportError(e);
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        renderer = null;
                        setBusy(false);
                        showExportError(error);
                    }
                });
    }

    private void showExportError(Exception error) {
        Log.w(CaptureInterceptor.TAG, "Cannot deliver the edited video.", error);
        if (!isShowing()) {
            // 書き出し後に失敗した場合は編集画面を閉じているため、元の動画で続ける。
            session.deliverOriginal();
            return;
        }
        showAlert(new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("編集した動画を書き出せませんでした。")
                .setPositiveButton("元の動画で投稿", (dialog, which) -> deliverOriginal())
                .setNegativeButton("編集に戻る", null));
    }

    /** 確認ダイアログも setlog の配色に合わせる。端末テーマのボタン色は暗い背景で読みにくい。 */
    private void showAlert(AlertDialog.Builder builder) {
        AlertDialog alert = builder.show();
        Window window = alert.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(Ui.rounded(getContext(), Ui.SURFACE, 22));
        }
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.ACCENT);
        alert.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Ui.TEXT_PRIMARY);
    }

    private void setBusy(boolean busy) {
        sendButton.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        sendProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        for (View view : new View[] {closeButton, originalRow}) {
            view.setEnabled(!busy);
            view.setAlpha(busy ? 0.35f : 1f);
        }
    }

    // ---- プレビュー ----

    private void startPreview() {
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVideoTextureView(previewTexture);
        player.setVideoEffects(EffectsFactory.colorEffects(spec));
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(capturedFile)));
        player.prepare();
        player.play();
    }

    private void updatePreviewEffects() {
        if (player == null) {
            return;
        }
        try {
            player.setVideoEffects(EffectsFactory.colorEffects(spec));
        } catch (RuntimeException e) {
            Log.w(CaptureInterceptor.TAG, "Cannot update preview effects; restarting the preview.", e);
            player.release();
            startPreview();
        }
    }

    private void release() {
        if (renderer != null) {
            renderer.cancel();
            renderer = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }

    // ---- 部品 ----

    private interface FloatSetter {
        void set(float value);
    }

    private View slider(Context context, String name, FloatSetter setter) {
        LinearLayout row = row(context);
        row.setPadding(0, Ui.dp(context, 12), 0, 0);
        TextView label = Ui.text(context, name, 14, Ui.TEXT_SECONDARY, false);
        label.setMinWidth(Ui.dp(context, 104));
        row.addView(label);
        SeekBar bar = seekBar(context, 200, 100);
        bar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setter.set((seekBar.getProgress() - 100) / 100f);
                updatePreviewEffects();
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static SeekBar seekBar(Context context, int max, int progress) {
        SeekBar bar = new SeekBar(context);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setProgressTintList(Ui.accentTint());
        bar.setProgressBackgroundTintList(Ui.trackTint());
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        return bar;
    }

    private void refreshFilterChips() {
        for (TextView chip : filterChips) {
            Ui.setChipSelected(getContext(), chip, chip.getTag() == spec.filter);
        }
    }

    private void refreshSwatches(TextSpec selected) {
        Context context = getContext();
        for (View swatch : colorSwatches) {
            int color = (Integer) swatch.getTag();
            GradientDrawable shape = Ui.circle(color);
            boolean current = selected != null && selected.color == color;
            shape.setStroke(Ui.dp(context, current ? 3 : 1), current ? Ui.ACCENT : Ui.STROKE);
            swatch.setBackground(shape);
        }
    }

    private static void showTab(List<TextView> tabs, View[] panels, int index) {
        for (int i = 0; i < panels.length; i++) {
            boolean selected = i == index;
            panels[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            TextView tab = tabs.get(i);
            tab.setBackground(selected ? Ui.pill(Ui.BACKGROUND) : null);
            tab.setTextColor(selected ? Ui.TEXT_PRIMARY : Ui.TEXT_SECONDARY);
        }
    }

    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, Ui.dp(context, 12), 0, 0);
        return column;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    /** 子 View を動画と同じ縦横比で、親の中に収まる最大の大きさにする。 */
    private static final class AspectFrame extends FrameLayout {
        private final float aspect;

        AspectFrame(Context context, float aspect) {
            super(context);
            this.aspect = aspect;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
            int width = maxWidth;
            int height = Math.round(width / aspect);
            if (height > maxHeight) {
                height = maxHeight;
                width = Math.round(height * aspect);
            }
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }
}

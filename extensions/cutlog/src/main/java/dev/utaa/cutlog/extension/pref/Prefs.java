package dev.utaa.cutlog.extension.pref;

import android.content.Context;
import android.content.SharedPreferences;
import dev.utaa.cutlog.extension.editor.FilterPreset;

/** cutlog 専用の設定。setlog 本体の設定ファイルとは分ける。 */
public final class Prefs {
    private static final String FILE = "cutlog";
    private static final String LAST_FILTER = "last_filter";

    private Prefs() {}

    public static FilterPreset lastFilter(Context context) {
        String name = prefs(context).getString(LAST_FILTER, FilterPreset.NONE.name());
        try {
            return FilterPreset.valueOf(name);
        } catch (IllegalArgumentException e) {
            return FilterPreset.NONE;
        }
    }

    public static void setLastFilter(Context context, FilterPreset filter) {
        prefs(context).edit().putString(LAST_FILTER, filter.name()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}

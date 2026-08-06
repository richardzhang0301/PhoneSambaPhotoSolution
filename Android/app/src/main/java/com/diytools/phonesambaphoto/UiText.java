package com.diytools.phonesambaphoto;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

final class UiText {
    private UiText() {
    }

    static boolean isChinese(Context context) {
        Locale locale;
        Configuration configuration = context.getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = configuration.getLocales().get(0);
        } else {
            locale = configuration.locale;
        }
        return locale != null && "zh".equalsIgnoreCase(locale.getLanguage());
    }

    static String text(Context context, String english, String chinese) {
        return isChinese(context) ? chinese : english;
    }
}

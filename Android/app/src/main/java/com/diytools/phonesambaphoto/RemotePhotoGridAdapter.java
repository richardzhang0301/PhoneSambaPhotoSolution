package com.diytools.phonesambaphoto;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

final class RemotePhotoGridAdapter extends BaseAdapter {
    private final Context context;
    private final List<RemotePhotoItem> photos;
    private final RemoteThumbLoader thumbLoader;
    private final int itemSizePx;
    private SambaSettings settings;

    RemotePhotoGridAdapter(Context context, List<RemotePhotoItem> photos, RemoteThumbLoader thumbLoader, SambaSettings settings) {
        this.context = context;
        this.photos = photos;
        this.thumbLoader = thumbLoader;
        this.settings = settings;
        this.itemSizePx = dp(120);
    }

    void setSettings(SambaSettings settings) {
        this.settings = settings;
    }

    @Override
    public int getCount() {
        return photos.size();
    }

    @Override
    public RemotePhotoItem getItem(int position) {
        return photos.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            SquareFrame item = new SquareFrame(context);
            item.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemSizePx));
            item.setPadding(dp(1), dp(1), dp(1), dp(1));

            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            item.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            TextView name = new TextView(context);
            name.setTextColor(Color.WHITE);
            name.setTextSize(11);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setPadding(dp(6), 0, dp(6), 0);
            name.setBackgroundColor(Color.argb(162, 0, 0, 0));
            FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(26),
                    Gravity.BOTTOM
            );
            item.addView(name, nameParams);

            holder = new Holder(image, name);
            item.setTag(holder);
            convertView = item;
        } else {
            holder = (Holder) convertView.getTag();
        }

        RemotePhotoItem photo = getItem(position);
        holder.name.setText(photo.name);
        if (settings != null && settings.isConfigured()) {
            thumbLoader.load(holder.image, photo, settings, itemSizePx);
        } else {
            holder.image.setImageDrawable(null);
        }
        return convertView;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Holder {
        final ImageView image;
        final TextView name;

        Holder(ImageView image, TextView name) {
            this.image = image;
            this.name = name;
        }
    }

    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}

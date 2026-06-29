package com.diytools.phonesambaphoto;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.List;

final class PhotoGridAdapter extends BaseAdapter {
    private final Context context;
    private final List<PhotoItem> photos;
    private final ThumbLoader thumbLoader;
    private final int itemSizePx;

    PhotoGridAdapter(Context context, List<PhotoItem> photos, ThumbLoader thumbLoader) {
        this.context = context;
        this.photos = photos;
        this.thumbLoader = thumbLoader;
        this.itemSizePx = dp(120);
    }

    @Override
    public int getCount() {
        return photos.size();
    }

    @Override
    public PhotoItem getItem(int position) {
        return photos.get(position);
    }

    @Override
    public long getItemId(int position) {
        return photos.get(position).mediaKey().hashCode();
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

            ImageView play = new ImageView(context);
            play.setImageResource(R.drawable.ic_play_circle);
            FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
            item.addView(play, playParams);

            View overlay = new View(context);
            overlay.setBackgroundColor(Color.argb(106, 23, 104, 172));
            item.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            ImageView check = new ImageView(context);
            check.setImageResource(R.drawable.ic_check_circle);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
            checkParams.setMargins(0, dp(7), dp(7), 0);
            item.addView(check, checkParams);

            View uploaded = new View(context);
            uploaded.setBackgroundResource(R.drawable.uploaded_dot);
            FrameLayout.LayoutParams uploadedParams = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.BOTTOM | Gravity.END);
            uploadedParams.setMargins(0, 0, dp(8), dp(8));
            item.addView(uploaded, uploadedParams);

            holder = new Holder(image, play, overlay, check, uploaded);
            item.setTag(holder);
            convertView = item;
        } else {
            holder = (Holder) convertView.getTag();
        }

        PhotoItem photo = getItem(position);
        thumbLoader.load(holder.image, photo, itemSizePx);
        holder.play.setVisibility(photo.video ? View.VISIBLE : View.GONE);
        holder.overlay.setVisibility(photo.selected ? View.VISIBLE : View.GONE);
        holder.check.setVisibility(photo.selected ? View.VISIBLE : View.GONE);
        holder.uploaded.setVisibility(photo.uploaded ? View.VISIBLE : View.GONE);
        return convertView;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Holder {
        final ImageView image;
        final ImageView play;
        final View overlay;
        final ImageView check;
        final View uploaded;

        Holder(ImageView image, ImageView play, View overlay, ImageView check, View uploaded) {
            this.image = image;
            this.play = play;
            this.overlay = overlay;
            this.check = check;
            this.uploaded = uploaded;
        }
    }

    public static final class SquareFrame extends FrameLayout {
        public SquareFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
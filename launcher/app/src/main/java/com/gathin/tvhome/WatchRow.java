package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Continue watching": a strip of cards under the dock, from the system's Watch Next rows (WatchNext).
 *
 * It lives in the grid's own wrapper, below the grid, and takes its height off the grid only while it has cards, so
 * with no permission, no rows or a TV that publishes none, Home is laid out exactly as without it. Down from the dock
 * reaches it, Up goes back to the dock and Down goes on to the first row of apps; the rest of the grid's navigation
 * is untouched. Without READ_TV_LISTINGS granted it never has cards, so `pm revoke` turns it off.
 */
final class WatchRow {

    /** What the strip needs from Home. */
    interface Host {
        /** Up from a card: back to the dock. */
        void onUp();
        /** Down from a card: on to the first row of apps. */
        void onDown();
        /** A card took focus: the dock's glass and title no longer belong on screen. */
        void onFocused();
    }

    /** Screen height (dp) under which the strip is not added: the dock would be pushed into the clock. */
    private static final int MIN_SCREEN_DP = 480;
    /** Height the strip takes under the dock, heading and the room a focused card grows into included. */
    private static final int STRIP_DP = 128;
    private static final int CARD_W_DP = 148;
    private static final int CARD_H_DP = 83;     // 16:9, the shape of a leanback banner
    private static final int CARD_GAP_DP = 14;
    private static final int SIDE_DP = 44;
    private static final int RADIUS_DP = 10;
    private static final long MIN_REFRESH_MS = 1500;

    private final Activity activity;
    private final View grid;
    private final Host host;
    private final float density;
    private final boolean animate;
    private final LinearLayout strip;
    private final RecyclerView list;
    private final CardAdapter adapter = new CardAdapter();
    private boolean showing;
    private boolean loading;
    private long lastLoad = -MIN_REFRESH_MS;
    private int lastFocusPos;

    /** Null when the strip cannot be added here (grid not in a FrameLayout, or a screen too short for it). */
    static WatchRow create(Activity activity, RecyclerView grid, Host host) {
        float density = activity.getResources().getDisplayMetrics().density;
        float heightDp = activity.getResources().getDisplayMetrics().heightPixels / density;
        if (heightDp < MIN_SCREEN_DP) return null;
        if (!(grid.getParent() instanceof FrameLayout)) return null;
        return new WatchRow(activity, grid, host, density);
    }

    private WatchRow(Activity activity, RecyclerView grid, Host host, float density) {
        this.activity = activity;
        this.grid = grid;
        this.host = host;
        this.density = density;
        this.animate = activity.getResources().getBoolean(R.bool.animations)
                && !activity.getResources().getBoolean(R.bool.reduced_motion);

        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setVisibility(View.GONE);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);

        TextView heading = new TextView(activity);
        heading.setText(R.string.continue_watching);
        heading.setTextColor(Color.argb(190, 255, 255, 255));
        heading.setTextSize(14f);
        heading.setSingleLine(true);
        heading.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.setMarginStart(px(SIDE_DP));
        strip.addView(heading, headingLp);

        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(px(SIDE_DP), px(10), px(SIDE_DP), px(10));
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        strip.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout parent = (FrameLayout) grid.getParent();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(STRIP_DP), Gravity.BOTTOM);
        parent.addView(strip, lp);
    }

    boolean isShowing() {
        return showing;
    }

    /**
     * Moves focus into the strip. True when it took the key (so Home does not also move focus); false when there is
     * nothing to focus and Down should behave as it always did.
     */
    boolean focus() {
        int count = adapter.getItemCount();
        if (!showing || count == 0) return false;
        final int pos = Math.max(0, Math.min(lastFocusPos, count - 1));
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(pos);
        if (holder != null) {
            holder.itemView.requestFocus();
        } else {
            list.scrollToPosition(pos);
            list.post(() -> {
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.requestFocus();
            });
        }
        return true;
    }

    /** Reads the rows off the main thread and updates the strip. Called each time Home comes to the front. */
    void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (loading || now - lastLoad < MIN_REFRESH_MS) return;
        loading = true;
        lastLoad = now;
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            List<WatchNext.Item> items;
            try {
                items = WatchNext.load(app, System.currentTimeMillis());
            } catch (RuntimeException e) {
                items = Collections.emptyList();
            }
            final List<WatchNext.Item> result = items;
            activity.runOnUiThread(() -> {
                loading = false;
                if (!activity.isDestroyed() && !activity.isFinishing()) apply(result);
            });
        }, "watch-next").start();
    }

    private void apply(List<WatchNext.Item> items) {
        if (items.isEmpty()) {
            if (showing) {
                if (list.hasFocus()) host.onUp();   // never leave focus on a card that is about to go
                showing = false;
                strip.setVisibility(View.GONE);
                reserve(false);
                adapter.set(items);
            }
            return;
        }
        adapter.set(items);
        if (!showing) {
            showing = true;
            reserve(true);
            strip.setVisibility(View.VISIBLE);
        }
    }

    /** Takes the strip's height off the bottom of the grid, so the dock rises by that much and the strip sits under it. */
    private void reserve(boolean on) {
        ViewGroup.LayoutParams raw = grid.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        int want = on ? px(STRIP_DP) : 0;
        if (lp.bottomMargin == want) return;
        lp.bottomMargin = want;
        grid.setLayoutParams(lp);
    }

    private void launch(WatchNext.Item item) {
        try {
            Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            // The row is another app's data: it may open that app's screen, never hand out access to anything.
            i.setFlags((i.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK)
                    & ~Intent.FLAG_GRANT_READ_URI_PERMISSION
                    & ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    & ~Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    & ~Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            activity.startActivity(i);
        } catch (Exception e) {   // URISyntaxException, ActivityNotFoundException, SecurityException
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
        }
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    // ---- cards ------------------------------------------------------------------------------------

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private List<WatchNext.Item> items = new ArrayList<>();
        private String signature = "";

        void set(List<WatchNext.Item> next) {
            StringBuilder sb = new StringBuilder();
            for (WatchNext.Item it : next) sb.append(it.signature()).append('\u0001');
            String sig = sb.toString();
            if (sig.equals(signature)) return;
            signature = sig;
            items = new ArrayList<>(next);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(buildCard());
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            h.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private View buildCard() {
        FrameLayout card = new FrameLayout(activity);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(px(CARD_W_DP), px(CARD_H_DP));
        lp.setMarginEnd(px(CARD_GAP_DP));
        card.setLayoutParams(lp);
        card.setBackgroundColor(Color.rgb(28, 28, 32));
        card.setFocusable(true);
        card.setClickable(true);
        final float radius = px(RADIUS_DP);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        ImageView art = new ImageView(activity);
        art.setId(R.id.watch_art);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Darkens the bottom third so the name reads over any banner.
        View scrim = new View(activity);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.TRANSPARENT, Color.argb(215, 0, 0, 0)});
        scrim.setBackground(fade);
        card.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(52), Gravity.BOTTOM));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(px(8), 0, px(8), px(8));
        TextView title = new TextView(activity);
        title.setId(R.id.watch_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(12f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView sub = new TextView(activity);
        sub.setId(R.id.watch_sub);
        sub.setTextColor(Color.argb(200, 255, 255, 255));
        sub.setTextSize(10f);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(sub);
        card.addView(labels, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Progress: a thin track along the bottom edge with the watched part filled.
        View track = new View(activity);
        track.setBackgroundColor(Color.argb(70, 255, 255, 255));
        card.addView(track, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(3), Gravity.BOTTOM));
        View fill = new View(activity);
        fill.setId(R.id.watch_progress);
        fill.setBackgroundColor(Color.WHITE);
        card.addView(fill, new FrameLayout.LayoutParams(0, px(3), Gravity.BOTTOM | Gravity.START));

        // The focus ring, drawn over everything else.
        View ring = new View(activity);
        ring.setId(R.id.watch_ring);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.TRANSPARENT);
        stroke.setCornerRadius(radius);
        stroke.setStroke(px(2), Color.argb(235, 255, 255, 255));
        ring.setBackground(stroke);
        ring.setVisibility(View.INVISIBLE);
        card.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        private final ImageView art;
        private final TextView title;
        private final TextView sub;
        private final View fill;
        private final View ring;

        CardHolder(@NonNull View v) {
            super(v);
            art = v.findViewById(R.id.watch_art);
            title = v.findViewById(R.id.watch_title);
            sub = v.findViewById(R.id.watch_sub);
            fill = v.findViewById(R.id.watch_progress);
            ring = v.findViewById(R.id.watch_ring);

            v.setOnClickListener(x -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) launch(adapter.items.get(pos));
            });
            v.setOnFocusChangeListener((view, focused) -> {
                ring.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                float s = focused ? 1.08f : 1f;
                if (animate) view.animate().scaleX(s).scaleY(s).setDuration(150).start();
                else {
                    view.setScaleX(s);
                    view.setScaleY(s);
                }
                if (focused) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) lastFocusPos = pos;
                    host.onFocused();
                }
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        host.onUp();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        host.onDown();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos <= 0;                                   // the row's start: nowhere to go
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos >= adapter.getItemCount() - 1;          // the row's end
                    default:
                        return false;
                }
            });
        }

        void bind(WatchNext.Item item) {
            title.setText(item.title);
            String subtitle = WatchNext.subtitle(item);
            sub.setText(subtitle);
            sub.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = item.percent > 0 ? Math.max(px(3), px(CARD_W_DP) * item.percent / 100) : 0;
            fill.setLayoutParams(lp);
            fill.setVisibility(item.percent > 0 ? View.VISIBLE : View.GONE);
            ring.setVisibility(View.INVISIBLE);
            itemView.setScaleX(1f);
            itemView.setScaleY(1f);
            bindArt(item.pkg);
        }

        /** The app's own banner, else its icon on the card's dark ground. Both come from the package manager. */
        private void bindArt(String pkg) {
            PackageManager pm = activity.getPackageManager();
            Drawable banner = null;
            Drawable icon = null;
            try {
                banner = pm.getApplicationBanner(pkg);
                if (banner == null) icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // The app is gone or hidden from this launcher: the card keeps its plain ground.
            }
            if (banner != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setPadding(0, 0, 0, 0);
                art.setImageDrawable(banner);
            } else if (icon != null) {
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                art.setPadding(px(44), px(12), px(44), px(24));
                art.setImageDrawable(icon);
            } else {
                art.setImageDrawable(null);
            }
        }
    }
}

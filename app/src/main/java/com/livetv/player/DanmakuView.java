package com.livetv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 轻量滚动弹幕层：按播放位置把弹幕从右向左滚动，不依赖第三方库。 */
public class DanmakuView extends View {

    private static final long LIFE_MS = 7000;
    private static final int ROWS = 8;

    private final List<DanmakuItem> items = new ArrayList<>();
    private int first = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long positionMs;
    private boolean active;

    public DanmakuView(Context context) {
        this(context, null);
    }

    public DanmakuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        paint.setTextSize(15 * density);
        paint.setColor(Color.WHITE);
        stroke.setTextSize(15 * density);
        stroke.setColor(0xCC000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setItems(List<DanmakuItem> list) {
        items.clear();
        first = 0;
        if (list != null) items.addAll(list);
        invalidate();
    }

    public void clear() {
        items.clear();
        first = 0;
        active = false;
        invalidate();
    }

    public void setPosition(long positionMs) {
        this.positionMs = positionMs;
        while (first < items.size() && positionMs - items.get(first).timeMs > LIFE_MS) first++;
        if (first > 0 && first == items.size()) {
            items.clear();
            first = 0;
        } else if (first > 64) {
            items.subList(0, first).clear();
            first = 0;
        }
        if (active && !items.isEmpty()) invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) items.clear();
        invalidate();
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || items.isEmpty()) return;
        float width = getWidth();
        float density = getResources().getDisplayMetrics().density;
        float top = 96 * density;
        boolean drawing = false;
        int row = 0;
        for (int i = first; i < items.size(); i++) {
            DanmakuItem item = items.get(i);
            long age = positionMs - item.timeMs;
            if (age < 0) break;
            if (age > LIFE_MS) continue;
            float textWidth = paint.measureText(item.text);
            float travel = width + textWidth;
            float x = width - travel * age / LIFE_MS;
            if (x > width) continue;
            float y = top + (row++ % ROWS) * 32 * density;
            int alpha = 255;
            if (age > LIFE_MS - 400) alpha = (int) (255 * (LIFE_MS - age) / 400);
            paint.setAlpha(alpha);
            stroke.setAlpha(alpha);
            canvas.drawText(item.text, x, y, stroke);
            canvas.drawText(item.text, x, y, paint);
            drawing = true;
        }
        if (drawing) postInvalidateDelayed(50);
    }
}




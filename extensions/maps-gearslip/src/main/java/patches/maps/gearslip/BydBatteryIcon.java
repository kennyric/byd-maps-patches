package patches.maps.gearslip;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A small battery gauge drawn from primitives, so the navigation card can show the expected charge
 * without spending the ETA line's width on words. Drawn here rather than shipped as an asset: no
 * third-party icon licence enters the bundle, and it scales with the host density.
 */
public final class BydBatteryIcon extends Drawable {
    private static final int LOW = 0xFFFF5252;
    private static final int NORMAL = 0xFFFFFFFF;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF nub = new RectF();
    private final RectF charge = new RectF();
    private final float radius;
    private final int width;
    private final int height;
    private int level = -1;

    /** Sized in device pixels; {@code dp} is the caller's density scale. */
    public BydBatteryIcon(float density) {
        float stroke = Math.max(1f, 1.4f * density);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(stroke);
        outline.setColor(NORMAL);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(NORMAL);
        radius = Math.max(1f, 2.5f * density);
        width = Math.round(22 * density);
        height = Math.round(12 * density);
    }

    /** 0..100 paints that share of the cell; a negative level draws the outline only. */
    public void setCharge(int value) {
        int clamped = value < 0 ? -1 : (value > 100 ? 100 : value);
        if (clamped == level) return;
        level = clamped;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float nubWidth = Math.max(1.5f, bounds.width() * 0.09f);
        body.set(bounds.left, bounds.top, bounds.right - nubWidth, bounds.bottom);
        float inset = outline.getStrokeWidth();
        canvas.drawRoundRect(body, radius, radius, outline);
        nub.set(body.right + inset * 0.5f, bounds.top + bounds.height() * 0.32f,
                bounds.right, bounds.bottom - bounds.height() * 0.32f);
        canvas.drawRoundRect(nub, radius * 0.5f, radius * 0.5f, outline);
        if (level <= 0) return;
        float usable = body.width() - inset * 2f;
        charge.set(body.left + inset, body.top + inset,
                body.left + inset + usable * (level / 100f), body.bottom - inset);
        fill.setColor(level <= 15 ? LOW : NORMAL);
        canvas.drawRoundRect(charge, radius * 0.6f, radius * 0.6f, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        outline.setAlpha(alpha);
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        outline.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return width;
    }

    @Override
    public int getIntrinsicHeight() {
        return height;
    }
}

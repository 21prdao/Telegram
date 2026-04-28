package org.telegram.wallet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Lightweight line icons drawn in code so the wallet module does not depend on external drawables. */
public class Web3IconView extends View {
    public static final int BACK = 1, SETTINGS = 2, COPY = 3, PLUS = 4, IMPORT = 5, SWITCH = 6,
            WALLET = 7, SEND = 8, SHIELD = 9, MANAGE = 10, COINS = 11, RED_PACKET = 12,
            CLOCK = 13, LINK = 14, CHEVRON = 15, LOCK = 16, KEY = 17, CUBE = 18,
            LIGHTNING = 19, EYE = 20;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int icon;
    private int color;

    public Web3IconView(Context context, int icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIconColor(int color) { this.color = color; invalidate(); }
    public void setIcon(int icon) { this.icon = icon; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        canvas.save();
        canvas.translate((w - s) / 2f, (h - s) / 2f);
        paint.setColor(color);
        paint.setStrokeWidth(s * 0.085f);
        paint.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (icon) {
            case BACK: drawBack(canvas, s); break;
            case SETTINGS: drawSettings(canvas, s); break;
            case COPY: drawCopy(canvas, s); break;
            case PLUS: drawPlus(canvas, s); break;
            case IMPORT: drawImport(canvas, s); break;
            case SWITCH: drawSwitch(canvas, s); break;
            case WALLET: drawWallet(canvas, s); break;
            case SEND: drawSend(canvas, s); break;
            case SHIELD: drawShield(canvas, s); break;
            case MANAGE: drawManage(canvas, s); break;
            case COINS: drawCoins(canvas, s); break;
            case RED_PACKET: drawRedPacket(canvas, s); break;
            case CLOCK: drawClock(canvas, s); break;
            case LINK: drawLink(canvas, s); break;
            case CHEVRON: drawChevron(canvas, s); break;
            case LOCK: drawLock(canvas, s); break;
            case KEY: drawKey(canvas, s); break;
            case CUBE: drawCube(canvas, s); break;
            case LIGHTNING: drawLightning(canvas, s); break;
            case EYE: drawEye(canvas, s); break;
            default: drawPlus(canvas, s); break;
        }
        canvas.restore();
    }

    private void drawBack(Canvas c, float s) { c.drawLine(s*.68f,s*.20f,s*.32f,s*.50f,paint); c.drawLine(s*.32f,s*.50f,s*.68f,s*.80f,paint); c.drawLine(s*.35f,s*.50f,s*.86f,s*.50f,paint); }
    private void drawSettings(Canvas c, float s) { c.drawCircle(s*.50f,s*.50f,s*.18f,paint); for(int i=0;i<8;i++){double a=Math.PI*2*i/8.0; c.drawLine((float)(s*.50f+Math.cos(a)*s*.31f),(float)(s*.50f+Math.sin(a)*s*.31f),(float)(s*.50f+Math.cos(a)*s*.40f),(float)(s*.50f+Math.sin(a)*s*.40f),paint);} }
    private void drawCopy(Canvas c, float s) { rect.set(s*.28f,s*.22f,s*.72f,s*.66f); c.drawRoundRect(rect,s*.08f,s*.08f,paint); rect.set(s*.18f,s*.34f,s*.60f,s*.78f); c.drawRoundRect(rect,s*.08f,s*.08f,paint); }
    private void drawPlus(Canvas c, float s) { c.drawLine(s*.50f,s*.25f,s*.50f,s*.75f,paint); c.drawLine(s*.25f,s*.50f,s*.75f,s*.50f,paint); }
    private void drawImport(Canvas c, float s) { c.drawLine(s*.50f,s*.18f,s*.50f,s*.62f,paint); c.drawLine(s*.32f,s*.48f,s*.50f,s*.66f,paint); c.drawLine(s*.68f,s*.48f,s*.50f,s*.66f,paint); c.drawLine(s*.24f,s*.80f,s*.76f,s*.80f,paint); c.drawLine(s*.24f,s*.80f,s*.24f,s*.66f,paint); c.drawLine(s*.76f,s*.80f,s*.76f,s*.66f,paint); }
    private void drawSwitch(Canvas c, float s) { rect.set(s*.20f,s*.24f,s*.80f,s*.68f); c.drawArc(rect,195,235,false,paint); c.drawLine(s*.70f,s*.20f,s*.82f,s*.32f,paint); c.drawLine(s*.82f,s*.32f,s*.66f,s*.36f,paint); rect.set(s*.20f,s*.32f,s*.80f,s*.76f); c.drawArc(rect,15,235,false,paint); c.drawLine(s*.30f,s*.80f,s*.18f,s*.68f,paint); c.drawLine(s*.18f,s*.68f,s*.34f,s*.64f,paint); }
    private void drawWallet(Canvas c, float s) { rect.set(s*.16f,s*.28f,s*.82f,s*.74f); c.drawRoundRect(rect,s*.10f,s*.10f,paint); rect.set(s*.55f,s*.42f,s*.88f,s*.62f); c.drawRoundRect(rect,s*.08f,s*.08f,paint); c.drawCircle(s*.68f,s*.52f,s*.025f,paint); c.drawLine(s*.20f,s*.32f,s*.44f,s*.20f,paint); c.drawLine(s*.44f,s*.20f,s*.72f,s*.28f,paint); }
    private void drawSend(Canvas c, float s) { path.moveTo(s*.16f,s*.48f); path.lineTo(s*.84f,s*.18f); path.lineTo(s*.62f,s*.82f); path.lineTo(s*.46f,s*.58f); path.close(); c.drawPath(path,paint); c.drawLine(s*.46f,s*.58f,s*.84f,s*.18f,paint); }
    private void drawShield(Canvas c, float s) { path.moveTo(s*.50f,s*.12f); path.lineTo(s*.80f,s*.25f); path.lineTo(s*.74f,s*.64f); path.quadTo(s*.50f,s*.86f,s*.50f,s*.86f); path.quadTo(s*.26f,s*.64f,s*.20f,s*.25f); path.close(); c.drawPath(path,paint); c.drawLine(s*.36f,s*.50f,s*.46f,s*.60f,paint); c.drawLine(s*.46f,s*.60f,s*.66f,s*.40f,paint); }
    private void drawManage(Canvas c, float s) { path.moveTo(s*.50f,s*.14f); path.lineTo(s*.78f,s*.30f); path.lineTo(s*.78f,s*.68f); path.lineTo(s*.50f,s*.86f); path.lineTo(s*.22f,s*.68f); path.lineTo(s*.22f,s*.30f); path.close(); c.drawPath(path,paint); c.drawCircle(s*.50f,s*.50f,s*.13f,paint); }
    private void drawCoins(Canvas c, float s) { rect.set(s*.20f,s*.20f,s*.80f,s*.42f); c.drawOval(rect,paint); c.drawLine(s*.20f,s*.31f,s*.20f,s*.60f,paint); c.drawLine(s*.80f,s*.31f,s*.80f,s*.60f,paint); rect.set(s*.20f,s*.49f,s*.80f,s*.71f); c.drawOval(rect,paint); rect.set(s*.20f,s*.64f,s*.80f,s*.86f); c.drawOval(rect,paint); }
    private void drawRedPacket(Canvas c, float s) { rect.set(s*.24f,s*.18f,s*.76f,s*.84f); c.drawRoundRect(rect,s*.10f,s*.10f,paint); c.drawLine(s*.24f,s*.38f,s*.50f,s*.54f,paint); c.drawLine(s*.76f,s*.38f,s*.50f,s*.54f,paint); c.drawCircle(s*.50f,s*.58f,s*.12f,paint); c.drawLine(s*.42f,s*.58f,s*.58f,s*.58f,paint); c.drawLine(s*.50f,s*.50f,s*.50f,s*.70f,paint); }
    private void drawClock(Canvas c, float s) { c.drawCircle(s*.50f,s*.50f,s*.34f,paint); c.drawLine(s*.50f,s*.50f,s*.50f,s*.30f,paint); c.drawLine(s*.50f,s*.50f,s*.66f,s*.62f,paint); }
    private void drawLink(Canvas c, float s) { rect.set(s*.14f,s*.36f,s*.52f,s*.64f); c.drawRoundRect(rect,s*.14f,s*.14f,paint); rect.set(s*.48f,s*.36f,s*.86f,s*.64f); c.drawRoundRect(rect,s*.14f,s*.14f,paint); c.drawLine(s*.40f,s*.50f,s*.60f,s*.50f,paint); }
    private void drawChevron(Canvas c, float s) { c.drawLine(s*.36f,s*.22f,s*.64f,s*.50f,paint); c.drawLine(s*.64f,s*.50f,s*.36f,s*.78f,paint); }
    private void drawLock(Canvas c, float s) { rect.set(s*.28f,s*.44f,s*.72f,s*.80f); c.drawRoundRect(rect,s*.08f,s*.08f,paint); rect.set(s*.34f,s*.16f,s*.66f,s*.56f); c.drawArc(rect,200,140,false,paint); c.drawCircle(s*.50f,s*.62f,s*.02f,paint); }
    private void drawKey(Canvas c, float s) { c.drawCircle(s*.34f,s*.42f,s*.16f,paint); c.drawLine(s*.46f,s*.54f,s*.78f,s*.82f,paint); c.drawLine(s*.64f,s*.68f,s*.72f,s*.60f,paint); c.drawLine(s*.72f,s*.76f,s*.80f,s*.68f,paint); }
    private void drawCube(Canvas c, float s) { path.moveTo(s*.50f,s*.14f); path.lineTo(s*.80f,s*.32f); path.lineTo(s*.80f,s*.68f); path.lineTo(s*.50f,s*.86f); path.lineTo(s*.20f,s*.68f); path.lineTo(s*.20f,s*.32f); path.close(); c.drawPath(path,paint); c.drawLine(s*.50f,s*.14f,s*.50f,s*.50f,paint); c.drawLine(s*.20f,s*.32f,s*.50f,s*.50f,paint); c.drawLine(s*.80f,s*.32f,s*.50f,s*.50f,paint); c.drawLine(s*.50f,s*.50f,s*.50f,s*.86f,paint); }
    private void drawLightning(Canvas c, float s) { path.moveTo(s*.56f,s*.10f); path.lineTo(s*.24f,s*.56f); path.lineTo(s*.50f,s*.56f); path.lineTo(s*.40f,s*.90f); path.lineTo(s*.78f,s*.44f); path.lineTo(s*.52f,s*.44f); path.close(); paint.setStyle(Paint.Style.FILL); c.drawPath(path,paint); paint.setStyle(Paint.Style.STROKE); }
    private void drawEye(Canvas c, float s) { path.moveTo(s*.12f,s*.50f); path.quadTo(s*.50f,s*.18f,s*.88f,s*.50f); path.quadTo(s*.50f,s*.82f,s*.12f,s*.50f); c.drawPath(path,paint); c.drawCircle(s*.50f,s*.50f,s*.13f,paint); }
}

package com.aron.autobill.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.aron.autobill.model.ParseResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 待确认队列。
 *
 * 为什么需要它：旧逻辑对置信度 < 50 的解析结果是直接 return 丢掉。
 * 「宁可不记，不可错记」的精神是对的，但用户完全不知道漏了什么 ——
 * 这正是「自动记账效果差」的主观感受来源。
 *
 * 现在不确定的东西有个地方放，用户点开就能一键确认或修正，
 * 比手动补录快得多，且账本依然只收用户认可过的账。
 */
public class PendingStore extends SQLiteOpenHelper {

    private static final String DB = "pending.db";
    private static final int VER = 1;
    private static final String T = "pending";

    public PendingStore(Context ctx) {
        super(ctx, DB, null, VER);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "platform TEXT,"
                + "merchant TEXT,"
                + "amount_fen INTEGER NOT NULL,"
                + "type INTEGER NOT NULL,"
                + "category TEXT,"
                + "note TEXT,"
                + "confidence INTEGER,"
                + "raw TEXT,"
                + "channel TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("DROP TABLE IF EXISTS " + T);
        onCreate(db);
    }

    /** 入队，返回行 id */
    public long add(ParseResult r, String raw, String pkg, String channel) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("platform", pkg);
        v.put("merchant", r.merchant == null ? "" : r.merchant);
        v.put("amount_fen", r.amountFen);
        v.put("type", r.type);
        v.put("category", Category.classifyFor(r, raw));
        v.put("note", r.note == null ? "" : r.note);
        v.put("confidence", r.confidence);
        v.put("raw", raw == null ? "" : raw);
        v.put("channel", channel);
        try {
            return getWritableDatabase().insert(T, null, v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 全部待确认项（新的在前） */
    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, null, null, null, null, "_id DESC");
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                it.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
                it.platform = c.getString(c.getColumnIndexOrThrow("platform"));
                it.merchant = c.getString(c.getColumnIndexOrThrow("merchant"));
                it.amountFen = c.getLong(c.getColumnIndexOrThrow("amount_fen"));
                it.type = c.getInt(c.getColumnIndexOrThrow("type"));
                it.category = c.getString(c.getColumnIndexOrThrow("category"));
                it.note = c.getString(c.getColumnIndexOrThrow("note"));
                it.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
                it.raw = c.getString(c.getColumnIndexOrThrow("raw"));
                it.channel = c.getString(c.getColumnIndexOrThrow("channel"));
                out.add(it);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    public int count() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("select count(*) from " + T, null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    public Item get(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(T, null, "_id=?",
                    new String[]{String.valueOf(id)}, null, null, null);
            if (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                it.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
                it.platform = c.getString(c.getColumnIndexOrThrow("platform"));
                it.merchant = c.getString(c.getColumnIndexOrThrow("merchant"));
                it.amountFen = c.getLong(c.getColumnIndexOrThrow("amount_fen"));
                it.type = c.getInt(c.getColumnIndexOrThrow("type"));
                it.category = c.getString(c.getColumnIndexOrThrow("category"));
                it.note = c.getString(c.getColumnIndexOrThrow("note"));
                it.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
                it.raw = c.getString(c.getColumnIndexOrThrow("raw"));
                it.channel = c.getString(c.getColumnIndexOrThrow("channel"));
                return it;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    public void remove(long id) {
        try {
            getWritableDatabase().delete(T, "_id=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {}
    }

    public void clear() {
        try { getWritableDatabase().delete(T, null, null); } catch (Throwable ignored) {}
    }

    /** 一条待确认记录 */
    public static class Item {
        public long id, ts, amountFen;
        public String platform, merchant, category, note, raw, channel;
        public int type, confidence;
    }
}

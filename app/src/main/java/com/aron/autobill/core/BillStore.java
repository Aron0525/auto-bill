package com.aron.autobill.core;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;

import com.aron.autobill.model.Bill;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地账本（SQLite）
 * v2：去重键格式变更（去掉平台前缀）
 * v3：新增 category 分类列
 * v4：新增 note 条目、confidence 置信度
 * v5：新增 pending 待确认标记（置信度 50~79 入账但需用户复核）
 */
public class BillStore extends SQLiteOpenHelper {

    private static final String DB = "autobill.db";
    private static final int VER = 5;

    public BillStore(Context ctx) {
        super(ctx, DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bills (" +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts INTEGER NOT NULL," +
            "platform TEXT," +
            "merchant TEXT," +
            "amount_fen INTEGER NOT NULL," +
            "type INTEGER NOT NULL," +
            "raw TEXT," +
            "category TEXT DEFAULT '" + Category.OTHER + "'," +
            "note TEXT," +
            "confidence INTEGER DEFAULT 100," +
            "pending INTEGER DEFAULT 0," +
            "dedup TEXT UNIQUE" +
            ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        android.util.Log.i("BillStore", "升级数据库 " + oldVersion + " -> " + newVersion);

        // v3：只加一列，尽量保留用户已有账目
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE bills ADD COLUMN category TEXT DEFAULT '" + Category.OTHER + "'");
            } catch (Exception e) {
                android.util.Log.w("BillStore", "加分类列失败，重建表: " + e);
                db.execSQL("DROP TABLE IF EXISTS bills");
                onCreate(db);
                return;
            }
        }
        // v4：条目 + 置信度
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE bills ADD COLUMN note TEXT");
                db.execSQL("ALTER TABLE bills ADD COLUMN confidence INTEGER DEFAULT 100");
            } catch (Exception e) {
                android.util.Log.w("BillStore", "加条目列失败，重建表: " + e);
                db.execSQL("DROP TABLE IF EXISTS bills");
                onCreate(db);
                return;
            }
        }
        // v5：待确认标记
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE bills ADD COLUMN pending INTEGER DEFAULT 0");
            } catch (Exception e) {
                android.util.Log.w("BillStore", "加待确认列失败（可忽略，老数据默认非待确认）: " + e);
            }
        }
        // v2：去重键格式变更，v1 的旧数据必须清掉，否则同笔交易会重复入账
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS bills");
            onCreate(db);
        }
    }

    /** 尝试插入；返回是否真正写入（重复返回 false） */
    public synchronized boolean insert(Bill b) {
        ContentValues cv = new ContentValues();
        cv.put("ts", b.ts);
        cv.put("platform", b.platform);
        cv.put("merchant", b.merchant);
        cv.put("amount_fen", b.amountFen);
        cv.put("type", b.type);
        cv.put("raw", b.raw);
        cv.put("category", b.category == null ? Category.OTHER : b.category);
        cv.put("note", b.note == null ? "" : b.note);
        cv.put("confidence", b.confidence);
        cv.put("pending", b.pending);
        cv.put("dedup", b.dedupKey());
        try {
            long id = getWritableDatabase().insertWithOnConflict("bills", null, cv,
                    SQLiteDatabase.CONFLICT_IGNORE);
            if (id == -1) return false;
            b.id = id;
            return true;
        } catch (Exception e) {
            // 数据库被外部删除/损坏时自愈：重建表后再插一次
            try {
                android.util.Log.w("BillStore", "插入失败，尝试重建表: " + e);
                SQLiteDatabase db = getWritableDatabase();
                db.execSQL("DROP TABLE IF EXISTS bills");
                onCreate(db);
                long id = db.insertWithOnConflict("bills", null, cv,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (id == -1) return false;
                b.id = id;
                return true;
            } catch (Exception e2) {
                android.util.Log.e("BillStore", "重建数据库仍失败: " + e2);
                return false;
            }
        }
    }

    public synchronized List<Bill> recent(int limit) {
        List<Bill> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("bills", null, null, null, null, null, "ts DESC", String.valueOf(limit));
        while (c.moveToNext()) {
            out.add(readBill(c));
        }
        c.close();
        return out;
    }

    public synchronized Bill findById(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query("bills", null, "_id=?", new String[]{String.valueOf(id)},
                null, null, null, "1");
        Bill b = null;
        if (c.moveToFirst()) b = readBill(c);
        c.close();
        return b;
    }

    /**
     * 语义去重：查找时间窗内「同方向 + 同金额 + 同商户」的记录。
     *
     *  - 内容指纹只能挡住「同一条通知被反复扫到」
     *  - 同一笔支付可能被不同通道抓到（通知/屏幕/短信/截图），文案不同 → 指纹不同
     *  - 用「方向 + 金额 + 商户 + 时间窗」做语义判定合并成一笔
     *
     * 商户匹配放宽：任一方是兜底值（未识别/手动记账/手动/本机）时，
     * 跳过商户维度，只按「方向 + 金额 + 时间窗」——否则手动补记的同额
     * 会被自动通道的兜底商户绕过去。
     */
    public synchronized Bill findSimilar(long type, long amountFen, String merchant,
                                         long fromTs, long toTs) {
        String m = merchant == null ? "" : merchant.trim();
        boolean merchantIsFallback = m.isEmpty()
                || m.equals("未识别") || m.equals("手动记账") || m.equals("手动/本机");
        String sql;
        String[] args;
        if (merchantIsFallback) {
            sql = "SELECT * FROM bills WHERE type=? AND amount_fen=? AND ts>=? AND ts<=? " +
                  "ORDER BY ts DESC LIMIT 1";
            args = new String[]{String.valueOf(type), String.valueOf(amountFen),
                    String.valueOf(fromTs), String.valueOf(toTs)};
        } else {
            sql = "SELECT * FROM bills WHERE type=? AND amount_fen=? AND ts>=? AND ts<=? " +
                  "AND (IFNULL(TRIM(merchant),'')=? OR IFNULL(TRIM(merchant),'') IN " +
                  "('未识别','手动记账','手动/本机')) " +
                  "ORDER BY ts DESC LIMIT 1";
            args = new String[]{String.valueOf(type), String.valueOf(amountFen),
                    String.valueOf(fromTs), String.valueOf(toTs), m};
        }
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        Bill b = null;
        if (c.moveToFirst()) b = readBill(c);
        c.close();
        return b;
    }

    /** 提升既有记录的置信度（同一笔被更可信的通道再次抓到时调用） */
    public synchronized void bumpConfidence(long id, int confidence) {
        try {
            getWritableDatabase().execSQL(
                    "UPDATE bills SET confidence=? WHERE _id=? AND confidence<?",
                    new Object[]{confidence, id, confidence});
        } catch (Exception e) {
            android.util.Log.w("BillStore", "更新置信度失败: " + e);
        }
    }

    public synchronized boolean delete(long id) {
        int n = getWritableDatabase().delete("bills", "_id=?", new String[]{String.valueOf(id)});
        return n > 0;
    }

    public synchronized boolean updateCategory(long id, String category) {
        ContentValues cv = new ContentValues();
        cv.put("category", category);
        int n = getWritableDatabase().update("bills", cv, "_id=?", new String[]{String.valueOf(id)});
        return n > 0;
    }

    /**
     * 整笔修改：时间 / 金额 / 方向 / 分类 / 商户 / 条目。
     * 关键点：改完要重算去重指纹并写回，否则指纹还是旧内容，
     * 而同一条通知再次被抓到时会因为指纹不匹配而被重复插入。
     * 反之若新指纹与别的账单冲突（用户改成了一模一样的账），按失败处理，不抛异常。
     */
    public synchronized boolean updateFull(long id, long ts, long amountFen, int type,
                                           String category, String merchant, String note) {
        try {
            Bill existing = findById(id);
            if (existing == null) return false;

            Bill probe = new Bill(ts, existing.platform, merchant, amountFen, type,
                    existing.raw, category, note, existing.confidence);
            ContentValues cv = new ContentValues();
            cv.put("ts", ts);
            cv.put("amount_fen", amountFen);
            cv.put("type", type);
            cv.put("category", category == null ? Category.OTHER : category);
            cv.put("merchant", merchant == null ? "" : merchant);
            cv.put("note", note == null ? "" : note);
            cv.put("dedup", probe.dedupKey());

            int n = getWritableDatabase().updateWithOnConflict("bills", cv, "_id=?",
                    new String[]{String.valueOf(id)}, SQLiteDatabase.CONFLICT_ABORT);
            return n > 0;
        } catch (Exception e) {
            android.util.Log.w("BillStore", "修改账单失败: " + e);
            return false;
        }
    }

    /** 汇总某时间点之后：返回 [支出总额(分), 收入总额(分)] */
    public synchronized long[] sumByType(long fromTs) {
        long[] out = new long[]{0, 0};
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT type, SUM(amount_fen) FROM bills WHERE ts>=? GROUP BY type",
                new String[]{String.valueOf(fromTs)});
        while (c.moveToNext()) {
            if (c.getInt(0) == 2) out[1] = c.getLong(1);
            else out[0] = c.getLong(1);
        }
        c.close();
        return out;
    }

    /** 汇总时间范围（闭区间）：返回 [支出总额(分), 收入总额(分)] */
    public synchronized long[] sumByTypeRange(long fromTs, long toTs) {
        long[] out = new long[]{0, 0};
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT type, SUM(amount_fen) FROM bills WHERE ts>=? AND ts<=? GROUP BY type",
                new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
        while (c.moveToNext()) {
            if (c.getInt(0) == 2) out[1] = c.getLong(1);
            else out[0] = c.getLong(1);
        }
        c.close();
        return out;
    }

    /** 分类统计：返回 [分类, 金额(分)]，按金额降序 */
    public synchronized List<String[]> statsByCategory(long fromTs, long toTs, int type) {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount_fen) AS s FROM bills " +
                "WHERE ts>=? AND ts<? AND type=? GROUP BY category ORDER BY s DESC",
                new String[]{String.valueOf(fromTs), String.valueOf(toTs), String.valueOf(type)});
        while (c.moveToNext()) {
            String cat = c.getString(0);
            if (cat == null || cat.isEmpty()) cat = Category.OTHER;
            out.add(new String[]{cat, String.valueOf(c.getLong(1))});
        }
        c.close();
        return out;
    }

    public synchronized int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM bills", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** 清空全部账单（设置页用） */
    public synchronized void clearAll() {
        getWritableDatabase().delete("bills", null, null);
    }

    /** 最早一笔账单的时间戳（无记录返回 Long.MAX_VALUE） */
    public synchronized long earliestTs() {
        Cursor c = getReadableDatabase().rawQuery("SELECT MIN(ts) FROM bills", null);
        long t = Long.MAX_VALUE;
        if (c.moveToFirst() && !c.isNull(0)) t = c.getLong(0);
        c.close();
        return t;
    }

    private Bill readBill(Cursor c) {
        Bill b = new Bill(
                c.getLong(c.getColumnIndexOrThrow("ts")),
                c.getString(c.getColumnIndexOrThrow("platform")),
                c.getString(c.getColumnIndexOrThrow("merchant")),
                c.getLong(c.getColumnIndexOrThrow("amount_fen")),
                c.getInt(c.getColumnIndexOrThrow("type")),
                c.getString(c.getColumnIndexOrThrow("raw")),
                null,
                null,
                100
        );
        b.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        int catIdx = c.getColumnIndex("category");
        b.category = (catIdx >= 0 && !c.isNull(catIdx)) ? c.getString(catIdx) : Category.OTHER;
        int noteIdx = c.getColumnIndex("note");
        b.note = (noteIdx >= 0 && !c.isNull(noteIdx)) ? c.getString(noteIdx) : "";
        int confIdx = c.getColumnIndex("confidence");
        b.confidence = (confIdx >= 0 && !c.isNull(confIdx)) ? c.getInt(confIdx) : 100;
        int pendIdx = c.getColumnIndex("pending");
        b.pending = (pendIdx >= 0 && !c.isNull(pendIdx)) ? c.getInt(pendIdx) : 0;
        return b;
    }
}

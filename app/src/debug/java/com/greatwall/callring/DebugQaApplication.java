package com.greatwall.callring;

import android.app.Application;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** Debug-only routing keeps local Telecom checks separate from the user's database. */
public class DebugQaApplication extends Application {
    static final String STATE = "management_phone_qa_state";
    static final String DATABASE = "management_phone_qa.db";
    private String route(String name) {
        return "callring.db".equals(name) && getSharedPreferences(STATE, MODE_PRIVATE).getBoolean("active", false)
                ? DATABASE : name;
    }
    @Override public File getDatabasePath(String name) { return super.getDatabasePath(route(name)); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return super.openOrCreateDatabase(route(name), mode, factory);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler handler) {
        return super.openOrCreateDatabase(route(name), mode, factory, handler);
    }
}

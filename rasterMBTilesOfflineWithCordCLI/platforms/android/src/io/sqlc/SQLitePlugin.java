/*
 * Copyright (c) 2012-2016: Christopher J. Brody (aka Chris Brody)
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

package io.sqlc;

import android.annotation.SuppressLint;

import android.util.Log;

import java.io.File;
import java.lang.IllegalArgumentException;
import java.lang.Number;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

import io.liteglue.*;

public class SQLitePlugin extends CordovaPlugin {

    static SQLiteConnector connector = new SQLiteConnector();

    /**
     * Multiple database runner map (static).
     * NOTE: no public static accessor to db (runner) map since it would not work with db threading.
     * FUTURE put DBRunner into a public class that can provide external accessor.
     */
    static ConcurrentHashMap<String, DBRunner> dbrmap = new ConcurrentHashMap<String, DBRunner>();

    /**
     * NOTE: Using default constructor, no explicit constructor.
     */

    /**
     * Executes the request and returns PluginResult.
     *
     * @param actionAsString The action to execute.
     * @param args   JSONArry of arguments for the plugin.
     * @param cbc    Callback context from Cordova API
     * @return       Whether the action was valid.
     */
    @Override
    public boolean execute(String actionAsString, JSONArray args, CallbackContext cbc) {

        Action action;
        try {
            action = Action.valueOf(actionAsString);
        } catch (IllegalArgumentException e) {
            // shouldn't ever happen
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }

        try {
            return executeAndPossiblyThrow(action, args, cbc);
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            return false;
        }
    }

    private boolean executeAndPossiblyThrow(Action action, JSONArray args, CallbackContext cbc)
            throws JSONException {

        boolean status = true;
        JSONObject o;
        String echo_value;
        String dbname;
        String dbname2;
        String alias;

        switch (action) {
            case echoStringValue:
                o = args.getJSONObject(0);
                echo_value = o.getString("value");
                cbc.success(echo_value);
                break;

            case open:
                o = args.getJSONObject(0);
                dbname = o.getString("name");
                // open database and start reading its queue
                this.startDatabase(dbname, o, cbc);
                break;

            case attach:
                o = args.getJSONObject(0);
                dbname = o.getString("dbname1");
                dbname2 = o.getString("dbname2");
                alias = o.getString("alias");
                this.attachDatabase(dbname, dbname2, alias, cbc);
                break;

            case close:
                o = args.getJSONObject(0);
                dbname = o.getString("path");
                // put request in the q to close the db
                this.closeDatabase(dbname, cbc);
                break;

            case delete:
                o = args.getJSONObject(0);
                dbname = o.getString("path");

                deleteDatabase(dbname, cbc);

                break;

            case flatSqlBatch:
                JSONObject allargs = args.getJSONObject(0);

                JSONObject dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");

                int mylen = allargs.getInt("flen");

                JSONArray flatlist = allargs.getJSONArray("flatlist");
                int ai = 0;

                String[] queries = new String[mylen];

                // XXX TODO: currently goes through flatlist in multiple [2] passes
                for (int i = 0; i < mylen; i++) {
                    queries[i] = flatlist.getString(ai++);
                    int alen = flatlist.getInt(ai++);
                    ai += alen;
                }

                // put db query in the queue to be executed in the db thread:
                DBQuery q = new DBQuery(true, queries, flatlist, cbc);
                DBRunner r = dbrmap.get(dbname);
                if (r != null) {
                    try {
                        r.q.put(q); 
                    } catch(Exception e) {
                        Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                        cbc.error("couldn't add to queue");
                    }
                } else {
                    cbc.error("database not open");
                }
                break;

            case executeSqlBatch:
            case backgroundExecuteSqlBatch:
                queries = null;
                //ignored:
                //String[] queryIDs = null;

                JSONArray jsonArr = null;
                int paramLen = 0;
                JSONArray[] jsonparams = null;

                allargs = args.getJSONObject(0);
                dbargs = allargs.getJSONObject("dbargs");
                dbname = dbargs.getString("dbname");
                JSONArray txargs = allargs.getJSONArray("executes");

                if (txargs.isNull(0)) {
                    queries = new String[0];
                } else {
                    int len = txargs.length();
                    queries = new String[len];
                    jsonparams = new JSONArray[len];

                    for (int i = 0; i < len; i++) {
                        JSONObject a = txargs.getJSONObject(i);
                        queries[i] = a.getString("sql");
                        //ignored:
                        //queryIDs[i] = a.getString("qid");
                        jsonArr = a.getJSONArray("params");
                        paramLen = jsonArr.length();
                        jsonparams[i] = jsonArr;
                    }
                }

                // put db query in the queue to be executed in the db thread:
                q = new DBQuery(queries, jsonparams, cbc);
                r = dbrmap.get(dbname);
                if (r != null) {
                    try {
                        r.q.put(q); 
                    } catch(Exception e) {
                        Log.e(SQLitePlugin.class.getSimpleName(), "couldn't add to queue", e);
                        cbc.error("couldn't add to queue");
                    }
                } else {
                    cbc.error("database not open");
                }
                break;
        }

        return status;
    }

    /**
     * Clean up and close all open databases.
     */
    @Override
    public void onDestroy() {
/* TBD ???:
        while (!dbrmap.isEmpty()) {
            String dbname = dbrmap.keySet().iterator().next();

            this.closeDatabaseNow(dbname);

            DBRunner r = dbrmap.get(dbname);
            try {
                // stop the db runner thread:
                r.q.put(new DBQuery());
            } catch(Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't stop db thread", e);
            }
            dbrmap.remove(dbname);
        }
// ** */
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------

    private void startDatabase(String dbname, JSONObject options, CallbackContext cbc) {
        // TODO: is it an issue that we can orphan an existing thread?  What should we do here?
        // If we re-use the existing DBRunner it might be in the process of closing...
        DBRunner r = dbrmap.get(dbname);

        // Brody TODO: It may be better to terminate the existing db thread here & start a new one, instead.
        if (r != null) {
            // don't orphan the existing thread; just re-open the existing database.
            // In the worst case it might be in the process of closing, but even that's less serious
            // than orphaning the old DBRunner.
/* **
            cbc.success();
// */
//* **
            if (r.oldImpl)
                cbc.success();
            else
                cbc.success("a1");
// */
        } else {
            r = new DBRunner(dbname, options, cbc);
            dbrmap.put(dbname, r);
            this.cordova.getThreadPool().execute(r);
        }
    }
    /**
     * Open a database.
     *
     * @param dbName   The name of the database file
     */
    private SQLiteDatabaseNDK openDatabase(String dbname, boolean createFromResource, CallbackContext cbc, boolean old_impl) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists() && createFromResource) this.createFromResource(dbname, dbfile);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteDatabaseNDK mydb = new SQLiteDatabaseNDK();
            mydb.open(dbfile);

/* **
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.success();
// */
            // Indicate Android version with flat JSON interface
//* **
            cbc.success("a1");
// */

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    private SQLiteAndroidDatabase openDatabase2(String dbname, boolean createFromResource, CallbackContext cbc, boolean old_impl) throws Exception {
        try {
            // ASSUMPTION: no db (connection/handle) is already stored in the map
            // [should be true according to the code in DBRunner.run()]

            File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

            if (!dbfile.exists() && createFromResource) this.createFromResource(dbname, dbfile);

            if (!dbfile.exists()) {
                dbfile.getParentFile().mkdirs();
            }

            Log.v("info", "Open sqlite db: " + dbfile.getAbsolutePath());

            SQLiteAndroidDatabase mydb = new SQLiteAndroidDatabase();
            mydb.open(dbfile);

            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.success();

            return mydb;
        } catch (Exception e) {
            if (cbc != null) // XXX Android locking/closing BUG workaround
                cbc.error("can't open database " + e);
            throw e;
        }
    }

    /**
     * Create from resource (assets) for pre-populated database
     *
     * If a prepopulated DB file exists in the assets folder it is copied to the dbPath.
     * Only runs the first time the app runs.
     */
    private void createFromResource(String myDBName, File dbfile)
    {
        InputStream in = null;
        OutputStream out = null;

        try {
            in = this.cordova.getActivity().getAssets().open("www/" + myDBName);
            String dbPath = dbfile.getAbsolutePath();
            dbPath = dbPath.substring(0, dbPath.lastIndexOf("/") + 1);

            File dbPathFile = new File(dbPath);
            if (!dbPathFile.exists())
                dbPathFile.mkdirs();

            File newDbFile = new File(dbPath + myDBName);
            out = new FileOutputStream(newDbFile);

            // XXX TODO: this is very primitive, other alternatives at:
            // http://www.journaldev.com/861/4-ways-to-copy-file-in-java
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0)
                out.write(buf, 0, len);
    
            Log.v("info", "Copied prepopulated DB content to: " + newDbFile.getAbsolutePath());
        } catch (IOException e) {
            Log.v("createFromResource", "No prepopulated DB found, Error=" + e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    Log.v("info", "Error closing input DB file, ignored");
                }
            }
    
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    Log.v("info", "Error closing output DB file, ignored");
                }
            }
        }
    }

    private void attachDatabase(String dbname1, String dbname2, String alias, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname1);
        if (r != null) {
            try {
                r.q.put(new DBQuery(Action.attach, dbname2, alias, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't attach database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't attach database", e);
            }
        } else {
            if (cbc != null) {
                cbc.error("sorry db not open");
            }
        }
    }

    /**
     * Close a database (in another thread).
     *
     * @param dbName   The name of the database file
     */
    private void closeDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(false, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            if (cbc != null) {
                cbc.success();
            }
        }
    }

    /**
     * Close a database (in the current thread).
     *
     * @param dbname   The name of the database file
     */
    private void closeDatabaseNow(String dbname) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteAndroidDatabase mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    private void closeDatabaseNow2(String dbname) {
        DBRunner r = dbrmap.get(dbname);

        if (r != null) {
            SQLiteAndroidDatabase mydb = r.mydb;

            if (mydb != null)
                mydb.closeDatabaseNow();
        }
    }

    private void deleteDatabase(String dbname, CallbackContext cbc) {
        DBRunner r = dbrmap.get(dbname);
        if (r != null) {
            try {
                r.q.put(new DBQuery(true, cbc));
            } catch(Exception e) {
                if (cbc != null) {
                    cbc.error("couldn't close database" + e);
                }
                Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
            }
        } else {
            boolean deleteResult = this.deleteDatabaseNow(dbname);
            if (deleteResult) {
                cbc.success();
            } else {
                cbc.error("couldn't delete database");
            }
        }
    }

    /**
     * Delete a database.
     *
     * @param dbName   The name of the database file
     *
     * @return true if successful or false if an exception was encountered
     */
    private boolean deleteDatabaseNow(String dbname) {
        File dbfile = this.cordova.getActivity().getDatabasePath(dbname);

        try {
            return cordova.getActivity().deleteDatabase(dbfile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
            return false;
        }
    }

    // NOTE: class hierarchy is ugly, done to reduce number of modules for manual installation.
    // FUTURE TBD SQLiteDatabaseNDK class belongs in its own module.
    class SQLiteDatabaseNDK extends SQLiteAndroidDatabase {
      SQLiteConnection mydb;

      /**
       * Open a database.
       *
       * @param dbFile   The database File specification
       */
      @Override
      void open(File dbFile) throws Exception {
        mydb = connector.newSQLiteConnection(dbFile.getAbsolutePath(),
          SQLiteOpenFlags.READWRITE | SQLiteOpenFlags.CREATE);
      }

      @Override
      void attachDatabaseNow(File dbname2, String alias, CallbackContext cbc) {
        cbc.error("Sorry ATTACH not implemented for default db implementation");
      }

      /**
       * Close a database (in the current thread).
       */
      @Override
      void closeDatabaseNow() {
        try {
          if (mydb != null)
            mydb.dispose();
        } catch (Exception e) {
            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database, ignoring", e);
        }
      }

      /**
       * Ignore Android bug workaround for NDK version
       */
      @Override
      void bugWorkaround() { }

      void flatSqlBatch(String[] queryarr, JSONArray flatlist, CallbackContext cbc) {
        SQLiteConnection mydbc = mydb;

        if (mydbc == null) {
            // not allowed - can only happen if someone has closed (and possibly deleted) a database and then re-used the database
            cbc.error("database has been closed");
            return;
        }

        int len = queryarr.length;
        JSONArray batchResultsList = new JSONArray();

        int ai=0;

        for (int i = 0; i < len; i++) {
            int rowsAffectedCompat = 0;
            boolean needRowsAffectedCompat = false;

            try {
                String query = queryarr[i];

                ai++;
                int alen = flatlist.getInt(ai++);

                // Need to do this in case this.executeSqlStatement() throws:
                int query_ai = ai;
                ai += alen;

                this.flatSqlStatement(mydbc, query, flatlist, query_ai, alen, batchResultsList);
            } catch (Exception ex) {
                ex.printStackTrace();
                Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + ex.getMessage());
                // TODO what to do?
            }
        }

        cbc.success(batchResultsList);
      }

      private void flatSqlStatement(SQLiteConnection mydbc, String query, JSONArray paramsAsJson,
                                     int firstParamIndex, int paramCount, JSONArray batchResultsList) throws Exception {
        boolean hasRows = false;

        SQLiteStatement myStatement = null;

        long newTotal = 0;
        long rowsAffected = 0;
        long insertId = -1;

        String errorMessage = null;
        int sqlite_error_code = -1;

        try {
            myStatement = mydbc.prepareStatement(query);

            for (int i = 0; i < paramCount; ++i) {
                int jsonParamIndex = firstParamIndex + i;
                if (paramsAsJson.isNull(jsonParamIndex)) {
                    myStatement.bindNull(i + 1);
                } else {
                    Object p = paramsAsJson.get(jsonParamIndex);
                    if (p instanceof Float || p instanceof Double) 
                        myStatement.bindDouble(i + 1, paramsAsJson.getDouble(jsonParamIndex));
                    else if (p instanceof Number) 
                        myStatement.bindLong(i + 1, paramsAsJson.getLong(jsonParamIndex));
                    else
                        myStatement.bindTextNativeString(i + 1, paramsAsJson.getString(jsonParamIndex));
                }
            }

            long lastTotal = mydbc.getTotalChanges();
            hasRows = myStatement.step();

            newTotal = mydbc.getTotalChanges();
            rowsAffected = newTotal - lastTotal;

            if (rowsAffected > 0)
                insertId = mydbc.getLastInsertRowid();

        } catch (java.sql.SQLException ex) {
            ex.printStackTrace();
            sqlite_error_code= ex.getErrorCode();
            errorMessage = ex.getMessage();
            Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): sqlite error code: " + sqlite_error_code + " message: " + errorMessage);
        } catch (Exception ex) {
            ex.printStackTrace();
            errorMessage = ex.getMessage();
            Log.e("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);
        }

        // If query result has rows
        if (hasRows) {
            String key = "";
            int colCount = myStatement.getColumnCount();

            // XXX ASSUMPTION: in this case insertId & rowsAffected would not apply here
            batchResultsList.put("okrows");

            // Build up JSON result object for each row
            do {
                try {
                    batchResultsList.put(colCount);

                    for (int i = 0; i < colCount; ++i) {
                        key = myStatement.getColumnName(i);
                        batchResultsList.put(key);

                        switch (myStatement.getColumnType(i)) {
                        case SQLColumnType.NULL:
                            batchResultsList.put(JSONObject.NULL);
                            break;

                        case SQLColumnType.REAL:
                            batchResultsList.put(myStatement.getColumnDouble(i));
                            break;

                        case SQLColumnType.INTEGER:
                            batchResultsList.put(myStatement.getColumnLong(i));
                            break;

                        // For TEXT & BLOB:
                        default:
                            batchResultsList.put(myStatement.getColumnTextNativeString(i));
                        }

                    }

                } catch (JSONException e) {
                    e.printStackTrace();
                    // TODO what to do?
                }
            } while (myStatement.step());

            batchResultsList.put("endrows");
        } else if (errorMessage != null) {
            batchResultsList.put("error");
            switch (sqlite_error_code) {
            case SQLCode.ERROR:
                batchResultsList.put(5); // SQLException.SYNTAX_ERR
                break;
            case 13: // SQLITE_FULL
                batchResultsList.put(4); // SQLException.QUOTA_ERR
                break;
            case 19: // SQLITE_CONSTRAINT
                batchResultsList.put(6); // SQLException.CONSTRAINT_ERR
                break;
            default:
                batchResultsList.put(0); // SQLException.UNKNOWN_ERR
            }
            batchResultsList.put(sqlite_error_code);
            batchResultsList.put(errorMessage);
        } else if (rowsAffected > 0) {
            batchResultsList.put("ch2");
            batchResultsList.put(rowsAffected);
            batchResultsList.put(insertId);
        } else {
            batchResultsList.put("ok");
        }

        if (myStatement != null) myStatement.dispose();
      }

      /**
       * Executes a batch request and sends the results via cbc.
       *
       * @param dbname     The name of the database.
       * @param queryarr   Array of query strings
       * @param jsonparams Array of JSON query parameters
       * @param queryIDs   IGNORED: Array of query ids
       * @param cbc        Callback context from Cordova API
       */
      @Override
      void executeSqlBatch( String[] queryarr, JSONArray[] jsonparams,
                            String[] queryIDs, CallbackContext cbc) {

        if (mydb == null) {
            // not allowed - can only happen if someone has closed (and possibly deleted) a database and then re-used the database
            cbc.error("database has been closed");
            return;
        }

        int len = queryarr.length;
        JSONArray batchResults = new JSONArray();

        for (int i = 0; i < len; i++) {
            int rowsAffectedCompat = 0;
            boolean needRowsAffectedCompat = false;
            //String query_id = queryIDs[i];

            JSONObject queryResult = null;
            String errorMessage = "unknown";

            try {
                String query = queryarr[i];

                long lastTotal = mydb.getTotalChanges();
                queryResult = this.executeSqlStatementNDK(query, jsonparams[i], cbc);
                long newTotal = mydb.getTotalChanges();
                long rowsAffected = newTotal - lastTotal;

                queryResult.put("rowsAffected", rowsAffected);
                if (rowsAffected > 0) {
                    long insertId = mydb.getLastInsertRowid();
                    if (insertId > 0) {
                        queryResult.put("insertId", insertId);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                errorMessage = ex.getMessage();
                Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);
            }

            try {
                if (queryResult != null) {
                    JSONObject r = new JSONObject();
                    //r.put("qid", query_id);

                    r.put("type", "success");
                    r.put("result", queryResult);

                    batchResults.put(r);
                } else {
                    JSONObject r = new JSONObject();
                    //r.put("qid", query_id);
                    r.put("type", "error");

                    JSONObject er = new JSONObject();
                    er.put("message", errorMessage);
                    r.put("result", er);

                    batchResults.put(r);
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
                Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + ex.getMessage());
                // TODO what to do?
            }
        }

        cbc.success(batchResults);
      }

      /**
       * Get rows results from query cursor.
       *
       * @param cur Cursor into query results
       * @return results in string form
       */
      private JSONObject executeSqlStatementNDK(String query, JSONArray paramsAsJson,
                                                CallbackContext cbc) throws Exception {
        JSONObject rowsResult = new JSONObject();

        boolean hasRows = false;

        SQLiteStatement myStatement = mydb.prepareStatement(query);

        try {
            String[] params = null;

            params = new String[paramsAsJson.length()];

            for (int i = 0; i < paramsAsJson.length(); ++i) {
                if (paramsAsJson.isNull(i)) {
                    myStatement.bindNull(i + 1);
                } else {
                    Object p = paramsAsJson.get(i);
                    if (p instanceof Float || p instanceof Double) 
                        myStatement.bindDouble(i + 1, paramsAsJson.getDouble(i));
                    else if (p instanceof Number) 
                        myStatement.bindLong(i + 1, paramsAsJson.getLong(i));
                    else
                        myStatement.bindTextNativeString(i + 1, paramsAsJson.getString(i));
                }
            }

            hasRows = myStatement.step();
        } catch (Exception ex) {
            ex.printStackTrace();
            String errorMessage = ex.getMessage();
            Log.v("executeSqlBatch", "SQLitePlugin.executeSql[Batch](): Error=" + errorMessage);

            // cleanup statement and throw the exception:
            myStatement.dispose();
            throw ex;
        }

        // If query result has rows
        if (hasRows) {
            JSONArray rowsArrayResult = new JSONArray();
            String key = "";
            int colCount = myStatement.getColumnCount();

            // Build up JSON result object for each row
            do {
                JSONObject row = new JSONObject();
                try {
                    for (int i = 0; i < colCount; ++i) {
                        key = myStatement.getColumnName(i);

                        switch (myStatement.getColumnType(i)) {
                        case SQLColumnType.NULL:
                            row.put(key, JSONObject.NULL);
                            break;

                        case SQLColumnType.REAL:
                            row.put(key, myStatement.getColumnDouble(i));
                            break;

                        case SQLColumnType.INTEGER:
                            row.put(key, myStatement.getColumnLong(i));
                            break;

                        case SQLColumnType.BLOB:
                        case SQLColumnType.TEXT:
                        default: // (just in case)
                            row.put(key, myStatement.getColumnTextNativeString(i));
                        }

                    }

                    rowsArrayResult.put(row);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            } while (myStatement.step());

            try {
                rowsResult.put("rows", rowsArrayResult);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        myStatement.dispose();

        return rowsResult;
      }
    }

    private class DBRunner implements Runnable {
        final String dbname;
        private boolean createFromResource;
        // expose oldImpl:
        boolean oldImpl;
        private boolean bugWorkaround;

        final BlockingQueue<DBQuery> q;
        final CallbackContext openCbc;

        SQLiteDatabaseNDK mydb1;
        SQLiteAndroidDatabase mydb;

        DBRunner(final String dbname, JSONObject options, CallbackContext cbc) {
            this.dbname = dbname;
            this.createFromResource = options.has("createFromResource");
            this.oldImpl = options.has("androidOldDatabaseImplementation");
            Log.v(SQLitePlugin.class.getSimpleName(), "Android db implementation: built-in android.database.sqlite package");
            this.bugWorkaround = this.oldImpl && options.has("androidBugWorkaround");
            if (this.bugWorkaround)
                Log.v(SQLitePlugin.class.getSimpleName(), "Android db closing/locking workaround applied");

            this.q = new LinkedBlockingQueue<DBQuery>();
            this.openCbc = cbc;
        }

        public void run() {
            try {
              if (!oldImpl)
                this.mydb1 = openDatabase(dbname, this.createFromResource, this.openCbc, this.oldImpl);
              else
                this.mydb = openDatabase2(dbname, this.createFromResource, this.openCbc, this.oldImpl);
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error, stopping db thread", e);
                dbrmap.remove(dbname);
                return;
            }

            DBQuery dbq = null;

            try {
                dbq = q.take();

                while (!dbq.stop) {
                    if (dbq.attach) {
                        File dbfile2 = cordova.getActivity().getDatabasePath(dbq.dbname2);

                        if (dbfile2.exists() && mydb != null) {
                            mydb.attachDatabaseNow(dbfile2, dbq.alias, dbq.cbc);
                        } else {
                            dbq.cbc.error("Sorry db file does not exist to ATTACH");
                        }
                    } else if (!oldImpl) {
                        mydb1.flatSqlBatch(dbq.queries, dbq.flatlist, dbq.cbc);
                    } else {
                        mydb.executeSqlBatch(dbq.queries, dbq.jsonparams, null, dbq.cbc);
                    }

                    if (this.bugWorkaround && dbq.queries.length == 1 && dbq.queries[0] == "COMMIT")
                      if (oldImpl)
                        mydb.bugWorkaround();

                    dbq = q.take();
                }
            } catch (Exception e) {
                Log.e(SQLitePlugin.class.getSimpleName(), "unexpected error", e);
            }

            if (dbq != null && dbq.close) {
                try {
                    closeDatabaseNow(dbname);

                    dbrmap.remove(dbname); // (should) remove ourself

                    if (!dbq.delete) {
                        dbq.cbc.success();
                    } else {
                        try {
                            boolean deleteResult = deleteDatabaseNow(dbname);
                            if (deleteResult) {
                                dbq.cbc.success();
                            } else {
                                dbq.cbc.error("couldn't delete database");
                            }
                        } catch (Exception e) {
                            Log.e(SQLitePlugin.class.getSimpleName(), "couldn't delete database", e);
                            dbq.cbc.error("couldn't delete database: " + e);
                        }
                    }                    
                } catch (Exception e) {
                    Log.e(SQLitePlugin.class.getSimpleName(), "couldn't close database", e);
                    if (dbq.cbc != null) {
                        dbq.cbc.error("couldn't close database: " + e);
                    }
                }
            }
        }
    }

    private final class DBQuery {
        final boolean is_flat;
        // XXX TODO replace with DBRunner action enum:
        final boolean stop;
        final boolean close;
        final boolean delete;
        final boolean attach;
        final String dbname2;
        final String alias;
        final String[] queries;
        final JSONArray[] jsonparams;
        final JSONArray flatlist;
        final CallbackContext cbc;

        DBQuery(String[] myqueries, JSONArray[] params, CallbackContext c) {
            this.is_flat = false;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.attach = false;
            this.dbname2 = null;
            this.alias = null;
            this.queries = myqueries;
            this.jsonparams = params;
            this.flatlist = null;
            this.cbc = c;
        }

        DBQuery(boolean is_flat, String[] myqueries, JSONArray flatlist, CallbackContext c) {
            this.is_flat = is_flat;
            this.stop = false;
            this.close = false;
            this.delete = false;
            this.attach = false;
            this.dbname2 = null;
            this.alias = null;
            this.queries = myqueries;
            this.jsonparams = null;
            this.flatlist = flatlist;
            this.cbc = c;
        }

        DBQuery(boolean delete, CallbackContext cbc) {
            this.is_flat = false;
            this.stop = true;
            this.close = true;
            this.delete = delete;
            this.attach = false;
            this.dbname2 = null;
            this.alias = null;
            this.queries = null;
            this.jsonparams = null;
            this.flatlist = null;
            this.cbc = cbc;
        }

        DBQuery(Action action, String dbname2, String alias, CallbackContext cbc) {
            if (action == Action.attach) {
                this.is_flat = false;
                this.stop = false;
                this.close = false;
                this.delete = false;
                this.attach = true;
                this.dbname2 = dbname2;
                this.alias = alias;
                this.queries = null;
                this.jsonparams = null;
                this.flatlist = null;
                this.cbc = cbc;
            } else {
                throw new RuntimeException("Sorry bad arguments");
            }
        }

        // signal the DBRunner thread to stop:
        DBQuery() {
            this.is_flat = false;
            this.stop = true;
            this.close = false;
            this.delete = false;
            this.attach = true;
            this.dbname2 = null;
            this.alias = null;
            this.queries = null;
            this.jsonparams = null;
            this.flatlist = null;
            this.cbc = null;
        }
    }

    private static enum Action {
        echoStringValue,
        open,
        attach,
        detach,
        close,
        delete,
        executeSqlBatch,
        backgroundExecuteSqlBatch,
        flatSqlBatch,
    }
}

/* vim: set expandtab : */

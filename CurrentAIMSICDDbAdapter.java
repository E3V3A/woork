package com.SecUpwN.AIMSICD.adapters;

import com.SecUpwN.AIMSICD.AIMSICD;
import com.SecUpwN.AIMSICD.constants.DBTableColumnIds;
import com.SecUpwN.AIMSICD.smsdetection.AdvanceUserItems;
import com.SecUpwN.AIMSICD.smsdetection.CapturedSmsData;
import com.SecUpwN.AIMSICD.utils.Cell;
import com.SecUpwN.AIMSICD.utils.CMDProcessor;
import com.SecUpwN.AIMSICD.utils.Device;
import com.SecUpwN.AIMSICD.utils.MiscUtils;
import com.SecUpwN.AIMSICD.utils.RequestTask;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Log;
import android.util.SparseArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;


/**
 * Brief:   Handles the AMISICD DataBase tables (creation, population, updates,
 *
 * Description:
 *
 *      This class handle all the AMISICD DataBase maintenance operations, like
 *      creation, population, updates, backup, restore and various selections.
 *
 *
 *
 * Current Issues:
 *
 *      As of 2015-01-01 we will start migrating from the old DB structure
 *      to the new one as detailed here:
 *      https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/215
 *      Please try to work on only one table at the time, before making
 *      new PRs or committing to "develop" branch.
 *
 *      [ ] We'd like to Export the entire DB (like a dump), so we need ...
 *      [ ] Clarify the difference between cell.getCID() and CellID (see insertCell() below.)
 *
 *  ChangeLog:
 *
 *      2015-01-22  E:V:A   Started DBe_import migration
 *      2015-01-23  E:V:A   ~~changed silent sms column names~~ NOT!
 *                          Added EventLog table
 *
 *
 *  Notes:
 *
 *  ======  !! IMPORTANT !!  ======================================================================
 *  For damn good reasons, we should try to stay with mDb.rawQuery() and NOT with mDb.query().
 *  In fact we should try to avoid the entire AOS SQLite API as much as possible, to keep our
 *  queries and SQL related clean, portable and neat. That's what most developers understand.
 *
 *  See:
 *  [1] http://stackoverflow.com/questions/1122679/querying-and-working-with-cursors-in-sqlite-on-android
 *  [2] http://developer.android.com/reference/android/database/sqlite/SQLiteDatabase.html#rawQuery%28java.lang.String,%20java.lang.String%5B%5D%29
 *  ===============================================================================================
 *
 *  +   Some examples we can use:
 *
 *   1) "Proper" style:
 *      rawQuery("SELECT id, name FROM people WHERE name = ? AND id = ?", new String[] {"David", "2"});
 *
 *   2) Hack style: (avoiding the use of "?")
 *      String q = "SELECT * FROM customer WHERE _id = " + customerDbId  ;
 *      Cursor mCursor = mDb.rawQuery(q, null);
 *
 *   3) Info on execSQL():
 *      Execute a single SQL statement that is NOT a SELECT or when passed with an argument a
 *      SELECT/INSERT/UPDATE/DELETE statement. Suggested use with: ALTER, CREATE or DROP.
 *
 *  +   A few words about DB "Cursors":
 *      http://developer.android.com/reference/android/database/Cursor.html
 *      http://stackoverflow.com/questions/3861558/what-are-the-benefits-of-using-database-cursor
 *
 */

public class AIMSICDDbAdapter extends SQLiteOpenHelper{

    public static String FOLDER;
    public static final int DATABASE_VERSION = 9; // Is this "pragma user_version;" ?

    // TODO: This should be implemented as a SharedPreference...
    private final Boolean MONO_DB_DUMP = true; // Also back-up DB with one monolithic dump file?

    private final String TAG = "AIMSICD";
    private final String mTAG = "DbAdapter";
    private static String DB_NAME = "aimsicd.db";

    private final String[] mTables;
    private SQLiteDatabase mDb;
    private final Context mContext;
    //newly added
    private static String DB_PATH = "/data/data/com.SecUpwN.AIMSICD/databases/";
    private  String DB_LOCATION = DB_PATH+DB_NAME;


    /**
     * Description:
     *              These tables are the ones that can be individually backed-up or restored in
     *              the backupDB() and restoreDB(). That's why the pre-loaded tables are NOT
     *              backed up, nor restored. They are:
     *                      TABLE_DEFAULT_MCC
     *                      TABLE_DET_FLAGS
     *                      TABLE_DBE_CAPAB
     *                      TABLE_SECTORTYPE
     *
     * @param context   Tables that can be used in:  backupDB() and restoreDB()
     */
    public AIMSICDDbAdapter(Context context) {
        super(context, DB_NAME, null, 1);
        mContext = context;
        FOLDER = mContext.getExternalFilesDir(null) + File.separator; //e.g. /storage/emulated/0/Android/data/com.SecUpwN.AIMSICD/
        //mDbHelper = new DbHelper(context);

        //Create a new blank db then write pre-compiled db in assets folder to blank db.
        //will throw error on first create because there is no db to open this is normal
        createDataBase();

        //return writable database
        mDb = SQLiteDatabase.openDatabase(DB_LOCATION, null, SQLiteDatabase.OPEN_READWRITE);

        //This will return the database as open so we dont need to use .open
        //when app is exiting we use new AIMSICDDbAdapter(getApplicationContext()).close(); to close it
        this.getWritableDatabase();
        mTables = new String[]{

                //I am trying to keep in same order and aimsicd.sql script
                DBTableColumnIds.DEFAULT_LOCATION_TABLE_NAME,       // Default MCC for each country
                DBTableColumnIds.API_KEYS_TABLE_NAME,               // API keys for OPEN_CELL_ID
                DBTableColumnIds.COUNTER_MEASURES_TABLE_NAME,       // Counter Measures thresholds and description
                DBTableColumnIds.DBE_CAPABILITIES_TABLE_NAME,       // External: MNO & BTS network capabilities
                DBTableColumnIds.DBE_IMPORT_TABLE_NAME,             // External: BTS import table
                DBTableColumnIds.DBI_BTS_TABLE_NAME,                // Internal: (physical) BTS data
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,            // Internal: (volatile) network measurements
                DBTableColumnIds.DETECTION_FLAGS_TABLE_NAME,        // Detection Flag description, settings and scoring table
                DBTableColumnIds.EVENTLOG_TABLE_NAME,               // Detection and general EventLog (persistent)
                DBTableColumnIds.SECTOR_TYPE_TABLE_NAME,            // BTS tower sector configuration (Many CID, same BTS)
                DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME,      // Detection strings to will be picked up in logcat
                DBTableColumnIds.SMS_DATA_TABLE_NAME,               // Silent SMS details
        };
    }

    /**
     * Creates a empty database on the system and rewrites it with your own database.
     * */
    public boolean createDataBase(){
        if(!checkDataBase()){
            //By calling this method and empty database will be created into the default system path
            //of your application so we are gonna be able to overwrite that database with our database.
            this.getReadableDatabase();
            try {
                copyDataBase();
                Log.i(TAG,"Database created");

                return true;
            } catch (IOException e) {
                throw new Error("Error copying database\n"+e.toString());
            }

        }
        return false;
    }

    /**
     * Check if the database already exist to avoid re-copying the file each time you open the application.
     * @return true if it exists, false if it doesn't
     */
    private boolean checkDataBase(){

        SQLiteDatabase checkDB = null;

        try{
            Log.i(TAG,"Checking for db first install this will throw an error on install and is noraml");
            checkDB = SQLiteDatabase.openDatabase(DB_LOCATION, null, SQLiteDatabase.OPEN_READONLY);
        }catch(SQLiteException e){
            Log.e(TAG,"database not created yet "+e.toString());
        }

        if(checkDB != null){
            checkDB.close();
        }

        return checkDB != null ? true : false;
    }

    /**
     * Copies your database from your local assets-folder to the just created empty database in the
     * system folder, from where it can be accessed and handled.
     * This is done by transfering bytestream.
     * */
    private void copyDataBase() throws IOException{

        //Open your local db as the input stream
        InputStream myInput = mContext.getAssets().open(DB_NAME);

        //Open the empty db as the output stream
        OutputStream myOutput = new FileOutputStream(DB_LOCATION);

        //transfer bytes from the inputfile to the outputfile
        byte[] buffer = new byte[1024];
        int length;
        while ((length = myInput.read(buffer))>0){
            myOutput.write(buffer, 0, length);
        }

        //Close the streams
        myOutput.flush();
        myOutput.close();
        myInput.close();
    }

    public AIMSICDDbAdapter open() throws SQLException {
        mDb = this.getWritableDatabase();
        return this;
    }

    public void close() {
        mDb.close();
        Log.i(TAG, "Closing db from AIMSICDDbAdapter.java");
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {

    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i2) {

    }


    // ====================================================================
    //      Populate the DB tables  (INSERT)
    // ====================================================================

    /**
     * Delete cell info - for use in tests
     *
     * @param cellId    This method deletes a cell with CID from CELL_TABLE
     * @return result of deleting that CID
     * TODO this is not called anywhere from what I can see?
     */
    public int deleteCell(int cellId) {
        Log.i(TAG, mTAG + ": Deleted CID: " + cellId);
        //TODO do we also need to delete this cell from DBi_measure?
        return mDb.delete(DBTableColumnIds.DBI_BTS_TABLE_NAME, DBTableColumnIds.DBI_BTS_CID + "=" + cellId, null);
    }

    /* ====================================================================
     *      mDb.query statements (get)        SELECT
     * ====================================================================
     *  IMPORTANT!  The SQL queries as presented here are not in the same order
     *              as in the DB tables themselves, therefore it may be hard
     *              to easily match the various items when using the Cursors
     *              as shown below.
     *
     *              For example, in the opencellid (DBe_import) table, the items are ordered as:
     *                 Lat,Lng,Mcc,Mnc,CellID,...
     *              whereas in the getOpenCellIDData() cursor, they are arranged as:
     *                 CellID,Lac,Mcc,Mnc,Lat,Lng,AvgSigStr,Samples
     *
     *              Thus when used in MapViewerOsmDroid.java at loadEntries() and
     *              loadOpenCellIDMarkers(), the index used there is completely different
     *              than what could be expected.
     *
     *  ISSUES:     [ ] To avoid un-necessary future code obscurity, we should rearrange
     *                  all Cursor queries to reflect the actual DB table order.
     *                  Todo: This is a tedious job...
     *
     * ====================================================================
     */

    // =========== NEW ============================================================================

    // =========== OLD ============================================================================


    /**
     * Returns Cell Information (DBi_bts) database contents
     * TODO This is called from MapViewerOsmDroid and a lot of the tables it ask for are not
     * TODO in DBi_bts but they seem to be in Dbe_import.. is this right?
     */
    public Cursor getCellData() {
        //return returnDBiBts();
        return returnDBeImport();
    }

    /**
     * Returns Cell Information for contribution to the OpenCellID project
     *
     * Function:    Return a list of all rows in cellinfo table where OCID_SUBMITTED is not 1.
     *
     * Todo:        Change column name from OCID_SUBMITTED to isSubmitted
     */
    public Cursor getOPCIDSubmitData() {
        return mDb.query( DBTableColumnIds.DBI_BTS_TABLE_NAME,//TODO this inst working need to fix to get more data
                new String[]{"Mcc", "Mnc", "Lac", "CellID", "Lng", "Lat", "Signal", "Timestamp",
                        "Accuracy", "Speed", "Direction", "NetworkType"}, "OCID_SUBMITTED <> 1",
                null, null, null, null
        );
    }

    /**
     * Returns Location Information (DBi_meas) database contents
     *
     */
    public Cursor getLocationData() {
        return returnDBiMeasure();
    }

    /**
     * Returns OpenCellID (DBe_import) database contents
     *
     * TODO:    Need to implement new items!!
     * URGENT:  Maybe not so urgent, but need looking after, since we added items.
     *
     * Used in:
     *          DbViewerFragment.java
     *          MapViewerOsmDroid.java
     *
     *
     */
    public Cursor getOpenCellIDData() {
        return returnDBeImport();
    }

    /**
     * Returns Default MCC Locations (defaultlocation) database contents
     *
     */
    public Cursor getDefaultMccLocationData() {
        return returnDefaultLocation();
    }

    // ====================================================================
    //      Various DB operations
    // ====================================================================

    /**
     *  Description:    This take a "Cell" bundle (from API) as input and uses its CID to check
     *                  in the DBi_measure (?) if there is already an associated LAC. It then
     *                  compares the API LAC to that of the DBi_Measure LAC.
     *
     *  Issues:     [ ] We should make all detections outside of AIMSICDDbAdapter.java in a
     *                  separate module as described in the diagram in GH issue #215.
     *                  https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/215
     *                  where it is referred to as "Detection Module" (DET)...
     *
     *              [ ] Seem we're querying too much, when we only need items: 1,3,4,8,11
     *                  (Try to avoid over query to improve performance.)
     *
     *              [ ]  V V V V
     *
     *              This is using the LAC found by API and comparing to LAC found from a previous
     *              measurement in the "DBi_measure". This is NOT depending on "DBe_import".
     *              This works for now...but we probably should consider populating "DBi_measure"
     *              as soon as the API gets a new LAC. Then the detection can be done by SQL,
     *              and by just comparing last 2 LAC entries for same CID.
     *
     *
     */
    public boolean checkLAC(Cell cell) {
        String query = String.format("SELECT * FROM %s WHERE %s = %d",
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_CID,  cell.getCID());

        Cursor bts_cursor = mDb.rawQuery(query,null);

        while (bts_cursor.moveToNext()) {
            // 1=LAC, 8=Accuracy, 11=Time
            if (cell.getLAC() != bts_cursor.getInt(bts_cursor.getColumnIndex(DBTableColumnIds.DBI_BTS_LAC))) {
                //Log.i(TAG, "ALERT: Changing LAC on CID: " + cell.getCID()
                //        + " Current LAC(DBi): " + cell.getLAC()
                //        + " Database LAC(DBe): " + cursor.getInt(0));
                Log.i(TAG, "ALERT: Changing LAC on CID: " + cell.getCID()
                        + " LAC(API): " + cell.getLAC()
                        + " LAC(DBi): " + bts_cursor.getInt(bts_cursor.getColumnIndex(DBTableColumnIds.DBI_BTS_LAC)));

                insertEventLog(MiscUtils.getCurrentTimeStamp(),
                        cell.getLAC(),
                        cell.getCID(),
                        cell.getPSC(),//This is giving weird values like 21478364... is this right?
                        String.valueOf(cell.getLat()),
                        String.valueOf(cell.getLon()),
                        (int)cell.getAccuracy(),//TODO cell.getAccuracy() = double & EventLog Column = int? is GPS Api returning int or double for ACC?
                        1,//should we create a add these to constants class like CHANGING_LAC = 1 etc...
                        "Changing LAC");

                bts_cursor.close();
                return false;
            } else {
                Log.v(TAG, "LAC checked - no change on CID:" + cell.getCID()
                        + " LAC(API): " + cell.getLAC()
                        + " LAC(DBi): " + bts_cursor.getInt(bts_cursor.getColumnIndex(DBTableColumnIds.DBI_BTS_LAC)));
            }
        }
        bts_cursor.close();
        return true;
    }


    /**
     * Updates Cell (cellinfo) records to indicate OpenCellID contribution has been made
     * TODO: This should be done on TABLE_DBI_MEASURE::DBi_measure:isSubmitted << vvv DONE vvv
     *
     */
    public void ocidProcessed() {
        ContentValues ocidValues = new ContentValues();
        ocidValues.put(DBTableColumnIds.DBI_MEASURE_IS_SUBMITTED, 1); // isSubmitted
        mDb.update(DBTableColumnIds.DBI_MEASURE_TABLE_NAME, ocidValues, DBTableColumnIds.DBI_MEASURE_IS_SUBMITTED+"<>?", new String[]{"1"}); // isSubmitted
    }

    public double[] getDefaultLocation(int mcc) {
        //Formatting queries like this so its more clear what is happening
        String query = String.format("SELECT %s,%s FROM %s WHERE %s = %d",
                DBTableColumnIds.DEFAULT_LOCATION_LAT,
                DBTableColumnIds.DEFAULT_LOCATION_LON,
                DBTableColumnIds.DEFAULT_LOCATION_TABLE_NAME,
                DBTableColumnIds.DEFAULT_LOCATION_MCC,     mcc);

        double[] loc = new double[2];
        Cursor cursor = mDb.rawQuery(query, null);

        if (cursor != null && cursor.moveToFirst()) {
            loc[0] = Double.parseDouble(cursor.getString(cursor.getColumnIndex(DBTableColumnIds.DEFAULT_LOCATION_LAT)));
            loc[1] = Double.parseDouble(cursor.getString(cursor.getColumnIndex(DBTableColumnIds.DEFAULT_LOCATION_LON)));
        } else {
            loc[0] = 0.0;
            loc[1] = 0.0;
        }
        cursor.close();
        return loc;
    }

    /**
     *  Description:    Remove all but the last row, unless its CID is invalid...
     *
     *  Note:           Q: What is this used for?
     *                  A: It used in the SignalStrengthTracker
     */
    public void cleanseCellTable() {
        //Creating queries with string format because easier to understand
        // This removes all but the last row in the "cellinfo" table
        String query = String.format("DELETE FROM %s WHERE %s NOT IN (SELECT MAX(%s) FROM %s GROUP BY %s)",
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_ID,
                DBTableColumnIds.DBI_BTS_ID,
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_CID
        );

        mDb.execSQL(query);

        String query2 = String.format("DELETE FROM %s WHERE %s = %d OR %s = -1",
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_CID,      Integer.MAX_VALUE,
                DBTableColumnIds.DBI_BTS_CID
        );

        mDb.execSQL(query2);
        //mDb.execSQL("DELETE FROM " + DBTableColumnIds.DBI_BTS_TABLE_NAME + " WHERE " + DBTableColumnIds.DBI_BTS_ID + " NOT IN (SELECT MAX(" + DBTableColumnIds.DBI_BTS_ID + ") FROM " + DBTableColumnIds.DBI_BTS_TABLE_NAME + " GROUP BY CID)");
        // This removes all cells with trouble CID numbers (MAX, -1)
        //mDb.execSQL("DELETE FROM " + CELL_TABLE + " WHERE CellID = " + Integer.MAX_VALUE + " OR CellID = -1");
    }

    /**
     * Description:     Prepares the CSV file used to upload new data to the OCID server.
     *
     * Note:            Q: Where is this?
     *                  A: It is wherever your device has mounted its SDCard.
     *                     For example, in:  /data/media/0/AIMSICD/OpenCellID
     */
    public boolean prepareOpenCellUploadData() {
        boolean result;

        File dir = new File(FOLDER + "OpenCellID/");
        if (!dir.exists()) {
            result = dir.mkdirs();
            if (!result) {
                return false;
            }
        }
        File file = new File(dir, "aimsicd-ocid-data.csv");

        try {
            open(); // open Database
            Cursor c = getOPCIDSubmitData(); // get data not submitted yet

            if(c.getCount() > 0) { // check if we have something to upload
                if (!file.exists()) {
                    result = file.createNewFile();
                    if (!result) {
                        c.close();
                        return false;
                    }

                    CSVWriter csvWrite = new CSVWriter(new FileWriter(file));

                    csvWrite.writeNext("mcc,mnc,lac,cellid,lon,lat,signal,measured_at,rating,speed,direction,act");
                    String[] rowData = new String[c.getColumnCount()];
                    int size = c.getColumnCount();
                    AIMSICD.mProgressBar.setProgress(0);
                    AIMSICD.mProgressBar.setMax(size);
                    while (c.moveToNext()) {
                        for (int i = 0; i < size; i++) {
                            rowData[i] = c.getString(i);
                            AIMSICD.mProgressBar.setProgress(i);
                        }
                        csvWrite.writeNext(rowData);
                    }

                    csvWrite.close();
                    c.close();
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, mTAG + ": Error creating OpenCellID Upload Data: " + e);
            return false;
        } finally {
            AIMSICD.mProgressBar.setProgress(0);
        }
    }


    /**
     *  Description:    Parses the downloaded CSV from OpenCellID and uses it to populate
     *                  "DBe_import" aka. the "opencellid" table.
     *
     *
     *  Dependency:     RequestTask.java :: onPostExecute()
     *                  insertOpenCell()
     *  Issues:
     *
     *          [ ]     Progress bar is not shown or is the operation too quick to be seen?
     *          [ ]     Why are we only populating 8 items out of 19?
     *                  From downloaded OCID CSV file:  (19 items)
     *
     *  NOTES:
     *
     *              a)  We do not include "rej_cause" in backups.
     *              b)
     *
     *   # head -2 opencellid.csv
     *   lat,lon,mcc,mnc,lac,cellid,averageSignalStrength,range,samples,changeable,radio,rnc,cid,psc,tac,pci,sid,nid,bid
     *
     *   0 lat                      TEXT
     *   1 lon                      TEXT
     *   2 mcc                      INTEGER
     *   3 mnc                      INTEGER
     *   4 lac                      INTEGER
     *   5 cellid                   INTEGER
     *   6 averageSignalStrength    INTEGER
     *   7 range                    INTEGER
     *   8 samples                  INTEGER
     *   9 changeable               INTEGER
     *   10 radio                   TEXT aka rat
     *   11 rnc                     INTEGER
     *   12 cid                     INTEGER
     *   13 psc                     INTEGER
     *   14 tac
     *   15 pci
     *   16 sid
     *   17 nid
     *   18 bid
     *
     *   54.63376,25.160243,246,3,20,1294,0,-1,1,1,GSM,,,,,,,,
     *
     *  Unfortunately there are 2 important missing items in the OCID CSV file:
     *   - "time_first"
     *   - "time_last"
     *
     *   In addition the OCID data often contain unexplained negative values for one or both of:
     *    - "samples"
     *    - "range"
     */
    public boolean populateDBeImport() {
        //This was not finding file on my samsung S5
        //String fileName = Environment.getExternalStorageDirectory()
        //        + "/AIMSICD/OpenCellID/opencellid.csv";

        String fileName = mContext.getExternalFilesDir(null) + File.separator + "OpenCellID/opencellid.csv";

        File file = new File(fileName);

        try {
            if (file.exists()) {

                CSVReader csvReader = new CSVReader(new FileReader(file));
                List<String[]> csvCellID = new ArrayList<>();
                String next[];

                //AIMSICD.mProgressBar.setProgress(0);
                //AIMSICD.mProgressBar.setMax(csvSize);
                while ((next = csvReader.readNext()) != null) {
                    csvCellID.add(next);
                }

                if (!csvCellID.isEmpty()) {
                    int lines = csvCellID.size();
                    Log.i(TAG, mTAG + ":updateOpenCellID: OCID CSV size (lines): " + lines );

                    String lQuery = String.format("SELECT %s, COUNT(%s) FROM %s GROUP BY %s;",
                            DBTableColumnIds.DBE_IMPORT_CID,
                            DBTableColumnIds.DBE_IMPORT_CID,
                            DBTableColumnIds.DBE_IMPORT_TABLE_NAME,
                            DBTableColumnIds.DBE_IMPORT_CID
                            );
                    Cursor lCursor = mDb.rawQuery(lQuery, null);
                    SparseArray<Boolean> lPresentCellID = new SparseArray<>();
                    if(lCursor.getCount() > 0) {
                        while(lCursor.moveToNext()) {
                            lPresentCellID.put(lCursor.getInt(0), true );
                        }
                    }
                    lCursor.close();

                    AIMSICD.mProgressBar.setProgress(0);
                    AIMSICD.mProgressBar.setMax(lines);
                    for (int i = 1; i < lines; i++) {
                        AIMSICD.mProgressBar.setProgress(i);

                        // Inserted into the table only unique values CID
                        // without opening additional redundant cursor before each insert.
                        if(lPresentCellID.get(Integer.parseInt(csvCellID.get(i)[5]), false)) {
                            continue;
                        }
                        // Insert details into OpenCellID Database using:  insertOpenCell()
                        // Beware of negative values of "range" and "samples"!!


                      //  for(int ii =csvCellID.get(i).length -5;ii<csvCellID.get(i).length;ii++){
                      //      Log.i(TAG,String.valueOf(ii)+" "+csvCellID.get(i)[ii].toString()+"\n");
                      //  }

                        String  lat =csvCellID.get(i)[0],       //TEXT
                                lon =csvCellID.get(i)[1],       //TEXT
                                mcc =csvCellID.get(i)[2],       //int
                                mnc =csvCellID.get(i)[3],       //int
                                lac =csvCellID.get(i)[4],       //int
                                cellid =csvCellID.get(i)[5],    //int
                                avg_sig =csvCellID.get(i)[6],   //int
                                range =csvCellID.get(i)[7],     //int
                                samples =csvCellID.get(i)[8],   //int
                                change =csvCellID.get(i)[9],    //int
                                radio =csvCellID.get(i)[10],    //TEXT

                                rnc =csvCellID.get(i)[11],      //int
                                cid =csvCellID.get(i)[12],      //int
                                psc =csvCellID.get(i)[13];      //int

                        int iPsc = 0;

                        if(psc != null && !psc.equals("")){iPsc = Integer.parseInt(psc);}


                            //TODO HERE FOR DEBUGGING WILL BE REMOVED
 /*                         for(int ii =0;ii<csvCellID.size();ii++){
                              String bbb = "\n0 lat:"+csvCellID.get(ii)[0].toString()+"\n"+
                                      "1 lon:"+csvCellID.get(ii)[1].toString()+"\n"+
                                      "2 mcc:"+csvCellID.get(ii)[2].toString()+"\n"+
                                      "3 mnc:"+csvCellID.get(ii)[3].toString()+"\n"+
                                      "4 lac:"+csvCellID.get(ii)[4].toString()+"\n"+
                                      "5 cellid:"+csvCellID.get(ii)[5].toString()+"\n"+
                                      "6 avg_sig:"+csvCellID.get(ii)[6].toString()+"\n"+
                                      "7 range:"+csvCellID.get(ii)[7].toString()+"\n"+
                                      "8 samples:"+csvCellID.get(ii)[8].toString()+"\n"+
                                      "9 changeable:"+csvCellID.get(ii)[9].toString()+"\n"+
                                      "10 radio:"+csvCellID.get(ii)[10].toString()+"\n"+
                                      "11 rnc:"+csvCellID.get(ii)[11].toString()+"\n"+
                                      "12 cid:"+csvCellID.get(ii)[12].toString()+"\n"+
                                      "13 psc:"+csvCellID.get(ii)[13].toString()+"\n"+
                                      "14 tac:"+csvCellID.get(ii)[14].toString()+"\n"+
                                      "15 pci:"+csvCellID.get(ii)[15].toString()+"\n"+
                                      "16 sid:"+csvCellID.get(ii)[16].toString()+"\n"+
                                      "17 nid:"+csvCellID.get(ii)[17].toString()+"\n"+
                                      "18 bid:"+csvCellID.get(ii)[18].toString()+"\n";

                               Log.i(TAG,bbb);
                           }
 */



                       insertDBeImport(
                                "OCID",                     //DBsource
                                radio,                      // RAT
                                Integer.parseInt(mcc),      // MCC
                                Integer.parseInt(mnc),      // MNC
                                Integer.parseInt(lac),      // LAC
                                Integer.parseInt(cellid),   // CID (cellid) ?
                                iPsc,                       // psc
                                lat,                        // gps_lat
                                lon,                        // gps_lon
                                0,                          // isGPSexact TODO dont know what to put here
                                Integer.parseInt(avg_sig),  // avg_signal [dBm]
                                Integer.parseInt(range),    // avg_range [m]
                                Integer.parseInt(samples),  // samples
                                "no_time",//TODO where are time_first in csv file?
                                "no_time",//TODO where are time_last in csv file?
                                0//TODO is this ok for rej_cause
                        );
                        //Log.d(TAG,"Dbe_import tables inserted="+i);
                    }
                }
            }else{Log.e(TAG, mTAG + ">>>>> file doesnt exist ");}
            return true;
        } catch (Exception e) {
            Log.e(TAG, mTAG + ": Error parsing OpenCellID data: " + e.getMessage());
            return false;
        } finally {
            AIMSICD.mProgressBar.setProgress(0);
        }
    }

    //=============================================================================================
    // Database Backup and Restore
    //=============================================================================================

    /**
     *  Description:    Imports a previously exported CSV file into the database
     *
     *  Used:           ??
     *
     *  Issues:
     *              [ ] CELL_TABLE items are missing or corrupt
     *              [ ]
     *
     *  Exported CSV format: TODO:
     *
     *  Notes:      1) Restoring the DB can be done from a monolithic SQLite3 DB by (check!):
     *                  # sqlite3 aimsicd.db <aimsicd.dump
     *
     *
     */
    public boolean restoreDB() {
        try {
            for (String table : mTables) {
                File file = new File(FOLDER + "aimsicd-" + table + ".csv");
                if (file.exists()) {
                    List<String[]> records = new ArrayList<>();
                    String next[];
                    CSVReader csvReader = new CSVReader(new FileReader(file));
                    while ((next = csvReader.readNext()) != null) {
                        records.add(next);
                    }

                    if (!records.isEmpty()) {
                        int lines = records.size();
                        AIMSICD.mProgressBar.setMax(lines);
                        AIMSICD.mProgressBar.setProgress(0);
                        for (int i = 1; i < lines; i++) {
                            AIMSICD.mProgressBar.setProgress(i);
                            switch (table) {

                                case DBTableColumnIds.DEFAULT_LOCATION_TABLE_NAME:
                                    insertDefaultLocation(
                                            records.get(i)[1].toString(),           //country
                                            Integer.parseInt(records.get(i)[2]),    //MCC
                                            records.get(i)[3].toString(),           //lat
                                            records.get(i)[4].toString()            //lon
                                    );
                                    break;
                                case DBTableColumnIds.API_KEYS_TABLE_NAME:
                                    insertApiKeys(
                                            records.get(i)[1].toString(),           //name
                                            records.get(i)[2].toString(),           //type
                                            records.get(i)[3].toString(),           //key
                                            records.get(i)[4].toString(),           //time_add
                                            records.get(i)[5].toString()            //time_exp
                                    );
                                    break;
                                case DBTableColumnIds.COUNTER_MEASURES_TABLE_NAME:
                                    insertCounterMeasures(
                                            records.get(i)[1].toString(),           //name
                                            records.get(i)[2].toString(),           //description
                                            Integer.parseInt(records.get(i)[3]),    //thresh
                                            Double.parseDouble(records.get(i)[4])   //thfine
                                    );
                                    break;
                                case DBTableColumnIds.DBE_CAPABILITIES_TABLE_NAME:
                                    insertDBeCapabilities(
                                            records.get(i)[1].toString(),           //MCC
                                            records.get(i)[2].toString(),           //MNC
                                            records.get(i)[3].toString(),           //LAC
                                            records.get(i)[4].toString(),           //op_name
                                            records.get(i)[5].toString(),           //band_plan
                                            records.get(i)[6].toString()            //__EXPAND___
                                    );
                                    break;
                                case DBTableColumnIds.DBE_IMPORT_TABLE_NAME:
                                    insertDBeImport(
                                            records.get(i)[1].toString(),           // DBsource
                                            records.get(i)[2].toString(),           // RAT
                                            Integer.parseInt(records.get(i)[3]),    // MCC
                                            Integer.parseInt(records.get(i)[4]),    // MNC
                                            Integer.parseInt(records.get(i)[5]),    // LAC
                                            Integer.parseInt(records.get(i)[6]),    // CID
                                            Integer.parseInt(records.get(i)[7]),    // PSC..
                                            records.get(i)[8].toString(),           // gps_lat
                                            records.get(i)[9].toString(),           // gps_lon
                                            Integer.parseInt(records.get(i)[10]),   // isGPSexact
                                            Integer.parseInt(records.get(i)[11])  , // avg_range
                                            Integer.parseInt(records.get(i)[13]),   // avg_signal
                                            Integer.parseInt(records.get(i)[14]),   // samples
                                            records.get(i)[15].toString(),          // time_first
                                            records.get(i)[16].toString(),          // time_last
                                            0//Integer.parseInt(records.get(i)[17])    // rej_cause

                                    );
                                    break;
                                case DBTableColumnIds.DBI_BTS_TABLE_NAME:
                                    insertBTS(
                                            Integer.parseInt(records.get(i)[1]),    // MCC
                                            Integer.parseInt(records.get(i)[2]),    // MNC
                                            Integer.parseInt(records.get(i)[3]),    // LAC
                                            Integer.parseInt(records.get(i)[4]),    // CID
                                            Integer.parseInt(records.get(i)[5]),    // PSC
                                            Integer.parseInt(records.get(i)[6]),    // T3212
                                            Integer.parseInt(records.get(i)[7]),    // A5x
                                            Integer.parseInt(records.get(i)[8]),    // ST_id
                                            records.get(i)[9].toString(),           // First Time
                                            records.get(i)[10].toString(),          // last Time
                                            Double.parseDouble(records.get(i)[11]), // lat
                                            Double.parseDouble(records.get(i)[12])  // lon
                                    );

                                    break;
                                case DBTableColumnIds.DBI_MEASURE_TABLE_NAME:

                                    insertDbiMeasure(
                                            Integer.parseInt(records.get(i)[1]),      // bts_id
                                            records.get(i)[2].toString(),             // nc_list
                                            records.get(i)[3].toString(),             // time
                                            records.get(i)[4].toString(),             // gpsd_lat
                                            records.get(i)[5].toString(),             // gpsd_lon
                                            Integer.parseInt(records.get(i)[6]),      // gpsd_accu
                                            records.get(i)[7].toString(),             // gpse_lat
                                            records.get(i)[8].toString(),             // gpse_lon
                                            records.get(i)[9].toString(),            // bb_power
                                            records.get(i)[10].toString(),            // bb_rf_temp
                                            records.get(i)[11].toString(),            // tx_power
                                            records.get(i)[12].toString(),            // rx_signal
                                            records.get(i)[13].toString(),            // rx_stype
                                            records.get(i)[14].toString(),             // rat
                                            records.get(i)[15].toString(),            // BCCH
                                            records.get(i)[16].toString(),            // TMSI
                                            Integer.parseInt(records.get(i)[17]),     // TA
                                            Integer.parseInt(records.get(i)[18]),     // PD
                                            Integer.parseInt(records.get(i)[19]),     // BER
                                            records.get(i)[20].toString(),            // AvgEcNo
                                            Integer.parseInt(records.get(i)[21]),     // isSubmitted
                                            Integer.parseInt(records.get(i)[22])     // isNeighbour
                                    );

                                    break;
                                case DBTableColumnIds.DETECTION_FLAGS_TABLE_NAME:
                                    insertDetectionFlags(
                                            Integer.parseInt(records.get(i)[1]),    //code
                                            records.get(i)[2].toString(),           //name
                                            records.get(i)[3].toString(),           //description
                                            Integer.parseInt(records.get(i)[4]),    //p1
                                            Integer.parseInt(records.get(i)[5]),    //p2
                                            Integer.parseInt(records.get(i)[6]),    //p3
                                            Double.parseDouble(records.get(i)[7]),  //p1_fine
                                            Double.parseDouble(records.get(i)[8]),  //p2_fine
                                            Double.parseDouble(records.get(i)[9]),  //p3_fine
                                            records.get(i)[10].toString(),          //app_text
                                            records.get(i)[11].toString(),          //func_use
                                            Integer.parseInt(records.get(i)[12]),   //istatus
                                            Integer.parseInt(records.get(i)[13])    //CM_id

                                    );
                                    break;
                                case DBTableColumnIds.EVENTLOG_TABLE_NAME:
                                    insertEventLog(
                                            records.get(i)[1].toString(),           //time
                                            Integer.parseInt(records.get(i)[2]),    //LAC
                                            Integer.parseInt(records.get(i)[3]),    //CID
                                            Integer.parseInt(records.get(i)[4]),    //PSC
                                            records.get(i)[5].toString(),           //gpsd_lat
                                            records.get(i)[6].toString(),           //gpsd_lon
                                            Integer.parseInt(records.get(i)[7]),    //gpsd_accu
                                            Integer.parseInt(records.get(i)[8]),    //DF_id
                                            records.get(i)[9].toString()            //DF_description
                                    );
                                    break;
                                case DBTableColumnIds.SECTOR_TYPE_TABLE_NAME:
                                    insertSectorType(
                                            records.get(i)[1].toString()
                                    );
                                    break;
                                case DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME:
                                    insertDetectionStrings(
                                            records.get(i)[1].toString(),
                                            records.get(i)[2].toString()
                                    );
                                    break;
                                case DBTableColumnIds.SMS_DATA_TABLE_NAME:
                                    insertSmsData(
                                            records.get(i)[1].toString(),           //time
                                            records.get(i)[2].toString(),           //number
                                            records.get(i)[3].toString(),           //smsc
                                            records.get(i)[4].toString(),           //message
                                            records.get(i)[5].toString(),           //type
                                            records.get(i)[6].toString(),           //class
                                            Integer.parseInt(records.get(i)[7]),    //lac
                                            Integer.parseInt(records.get(i)[8]),    //cid
                                            records.get(i)[9].toString(),           //rat
                                            Double.parseDouble(records.get(i)[10]), //gps_lat
                                            Double.parseDouble(records.get(i)[11]), //gps_lon
                                            Integer.parseInt(records.get(i)[12])    //isRoaming
                                    );
                                    break;
                            }

                        }
                    }
                }
            }
            Log.i(TAG, mTAG + ": restoreDB() Finished ");
            return true;
        } catch (Exception e) {
            Log.e(TAG, mTAG + ": restoreDB() " + e);
            return false;
        } finally {
            AIMSICD.mProgressBar.setProgress(0);
        }

    }

    /**
     *  Description:    Dumps the entire aimsicd.db to a dump file called "aimsicd_dump.db".
     *
     *  Requires:       root + SQLite3 binary
     *
     *  Dev Status:     INCOMPLETE !!  Either fix or do not try to use..
     *
     *  Template:       DebugLogs.java
     *
     *  TODO:       [ ] Change backup from using CSV files to/also using a complete SQLite dump
     *
     *  Notes:  1) We probably also need to test if we have the sqlite3 binary. (See Busybox checking code.)
     *          2) Apparently pipes doesn't work from Java... No idea why, as they appear to work
     *              in the AtCommandFragment.java... for checking for /dev/ files.
     *          3) We can use either ".dump" or ".backup", but "dump" makes an SQL file,
     *             whereas "backup" make a binary SQLite DB.
     *
     *            a) # sqlite3 aimsicd.db '.dump' | gzip -c >aimsicd.dump.gz
     *               execString = "/system/xbin/sqlite3 " + dir + "aimsicd.db '.dump' | gzip -c >" + file;
     *            b) execString = "/system/xbin/sqlite3 " + aimdir + "aimsicd.db '.dump' >" + file;
     *            c) execString = "/system/xbin/sqlite3 " + aimdir + "aimsicd.db '.backup " +file + "'";
     *
     *          4) To re-import use:
     *             # zcat aimsicd.dump.gz | sqlite3 aimsicd.db
     *
     */
    private void dumpDB()  {
        File dumpdir = new File(FOLDER);
        //if (!dir.exists()) { dir.mkdirs(); }
        File file = new File(dumpdir, "aimsicd_dump.db");
        //Bad coding?
        String aimdir = "/data/data/com.SecUpwN.AIMSICD/databases/";
        //Context.getFilesDir().getPath("com.SecUpwN.AIMSICD/databases"); ????
        String execString = "/system/xbin/sqlite3 " + aimdir + "aimsicd.db '.backup " + file + "'";

        try {
            Log.i(TAG, mTAG + ":dumpDB() Attempting to dump DB to: " + file + "\nUsing: \"" + execString + "\"\n");
            // We need SU here and cd...
            CMDProcessor.runSuCommand(execString);
            //CMDProcessor.runSuCommand(execString).getStdout(); // Need import!
            //Process process = Runtime.getRuntime().exec(execString);

        } catch (Exception e) {
            Log.e(TAG, mTAG + ":dumpDB() Failed to export DB dump file: " + e);
        }
        Log.i(TAG, mTAG + ":dumpDB() Database dumped to: " + file);

    }


    /**
     *  Description:    Backup the database tables to CSV files (or monolithic dump file)
     *
     *  Depends:        On the Boolean MONO_DB_DUMP to indicate if we want to try to
     *                  dump a monolithic DB using the rooted shell + sqlite3 binary
     *                  method above.
     *
     * @return boolean indicating backup outcome
     */
    public boolean backupDB() {
        try {
            for (String table : mTables) {
                backup(table);
            }
            if (MONO_DB_DUMP) {
                dumpDB();
            }
            return true;
        } catch (Exception ioe) {
            Log.e(TAG, mTAG + ":backupDB() Error: " + ioe);
            return false;
        }
    }

    /**
     *  Description:    Exports the database tables to CSV files
     *
     *  Issues:     We should consider having a better file selector here, so that
     *              the user can select his own location for storing the backup files.
     *
     * @param tableName String representing table name to export
     */
    private void backup(String tableName) {
        Log.i(TAG, mTAG + ": Database Backup: " + DB_NAME);

        File dir = new File(FOLDER);
        if (!dir.exists()) { dir.mkdirs(); }  // We should probably add some more error handling here.
        File file = new File(dir, "aimsicd-" + tableName + ".csv");

        try {
            file.createNewFile();
            CSVWriter csvWrite = new CSVWriter(new FileWriter(file));
            Log.d(TAG, mTAG + ": table name " + tableName);
            open();
            Cursor c = mDb.rawQuery("SELECT * FROM " + tableName, new String[0]);

            csvWrite.writeNext(c.getColumnNames());
            String[] rowData = new String[c.getColumnCount()];
            int size = c.getColumnCount();
            AIMSICD.mProgressBar.setProgress(0);
            AIMSICD.mProgressBar.setMax(size);
            while (c.moveToNext()) {
                for (int i = 0; i < size; i++) {
                    rowData[i] = c.getString(i);
                    AIMSICD.mProgressBar.setProgress(i);
                }
                csvWrite.writeNext(rowData);
            }

            csvWrite.close();
            c.close();

        } catch (Exception e) {
            Log.e(TAG, mTAG + ": Error exporting table: " + tableName + " " + e);
        } finally {
            AIMSICD.mProgressBar.setProgress(0);
        }

        Log.i(TAG, mTAG + ": Database Export complete.");
    }


    // ====================================================================
    //      Cleanup and filtering of DB tables
    // ====================================================================

    /**
     *  What:           This is the DBe_import data consistency check
     *
     *  Description:    This method checks each imported BTS data for consistency
     *                  and correctness according to general 3GPP LAC/CID/RAT rules
     *                  and according to the app settings:
     *
     *                  tf_settings         (currently hard-coded)
     *                  min_gps_precision   (currently hard-coded)
     *
     *                  So there are really two steps in this procedure:
     *                  a) Remove bad BTSs from DBe_import
     *                  b) Mark unsafe BTSs in the DBe_import with "rej_cause" value.
     *
     *                  See:    #253    http://tinyurl.com/lybrfxb
     *                          #203    http://tinyurl.com/mzgjdcz
     *
     *                  We filter:
     *
     *  Used:
     *                  RequestTask.java :: onPostExecute()
     *
     *  Issues:
     *
     *          [x] OPENCELLID_TABLE doesn't have a "Net" entry!
     *          [x] OPENCELLID_TABLE doesn't have a "Range" entry!
     *
     *          [ ] Look into "long CID" and "Short CID" for UMTS/LTE...
     *              http://wiki.opencellid.org/wiki/FAQ
     *
     *              The formula for the long cell ID is as follows:
     *                  Long CID = 65536 * RNC + CID
     *
     *              If you have the Long CID, you can get RNC and CID in the following way:
     *                  RNC = Long CID / 65536 (integer division)
     *                  CID = Long CID mod 65536 (modulo operation)
     *
     *  ChangeLog:
     *          2015-01-29  E:V:A   Added
     *
     *  TODO:   (1) Implement some kind of counter, to count how many cells was removed.
     *  TODO:   (2) Better description of what was removed.
     *  TODO:   (3) Give a return value for success/failure
     *  TODO:   (4) Implement the "rej_cause" check and UPDATE table.
     */
    //public void checkDBe( String tf_settings, int min_gps_precision ) {
    public void checkDBe() {
        // We hard-code these for now, but should be in the settings eventually
        int tf_settings=30;         // [days] Minimum acceptable number of days since "time_first" seen.
        int min_gps_precision=50;   // [m]    Minimum acceptable GPS accuracy in meters.

        String sqlq;                // SQL Query string

        //=============================================================
        //===  DELETE bad cells from BTS data
        //=============================================================

        Log.d(TAG, mTAG + ":checkDBe() Attempting to delete bad import data from DBe_import table...");

        // =========== samples ===========
        sqlq = "DELETE FROM " + DBTableColumnIds.DBE_IMPORT_TABLE_NAME + " WHERE samples < 1";
        mDb.execSQL(sqlq);

        // =========== range (DBe_import::avg_range) ===========
        // TODO: OCID data marks many good BTS with a negative range so we can't use this yet.
        //sqlq = "DELETE FROM " + OPENCELLID_TABLE + " WHERE Range < 1";
        //mDb.rawQuery(sqlq, null);

        // =========== LAC ===========
        sqlq = "DELETE FROM " + DBTableColumnIds.DBE_IMPORT_TABLE_NAME + " WHERE LAC < 1";
        mDb.execSQL(sqlq);

        // We should delete cells with CDMA (4) LAC not in [1,65534] but we can simplify this to:
        // Delete ANY cells with a LAC not in [1,65534]
        sqlq = "DELETE FROM " + DBTableColumnIds.DBE_IMPORT_TABLE_NAME + " WHERE LAC > 65534";
        mDb.execSQL(sqlq);
        // Delete cells with GSM/UMTS/LTE (1/2/3/13 ??) (or all others?) LAC not in [1,65533]
        //sqlq = "DELETE FROM " + OPENCELLID_TABLE + " WHERE Lac > 65533 AND Type!='CDMA'";
        //mDb.rawQuery(sqlq, null);

        // =========== CID ===========
        sqlq = "DELETE FROM " + DBTableColumnIds.DBE_IMPORT_TABLE_NAME + " WHERE CID < 1";
        mDb.execSQL(sqlq);

        // We should delete cells with UMTS/LTE (3,13) CID not in [1,268435455] (0xFFF FFFF) but
        // we can simplify this to:
        // Delete ANY cells with a CID not in [1,268435455]
        sqlq = "DELETE FROM " + DBTableColumnIds.DBE_IMPORT_TABLE_NAME + " WHERE CID > 268435455";
        mDb.execSQL(sqlq);
        // Delete cells with GSM/CDMA (1-3,4) CID not in [1,65534]
        //sqlq = "DELETE FROM " + OPENCELLID_TABLE + " WHERE CellID > 65534 AND (Net!=3 OR Net!=13)";
        //mDb.rawQuery(sqlq, null);

        Log.i(TAG, mTAG + ":checkDBe() Deleted BTS entries from DBe_import table with bad LAC/CID...");

        //=============================================================
        //===  UPDATE "rej_cause" in BTS data (DBe_import)
        //=============================================================

        // =========== isGPSexact ===========
        // NOTE!!  OCID present "changeable"=1 ==> isGPSexact (until we get new import!)
        // UPADTE opencellid SET rej_cause = rej_cause + 3 WHERE isGPSexact=1;

        // =========== avg_range ===========
        // "UPDATE opencellid SET rej_cause = rej_cause + 3 WHERE avg_range < " + min_gps_precision;

        // =========== time_first ===========
        // "UPDATE opencellid SET rej_cause = rej_cause + 1 WHERE time_first < " + tf_settings;

    }



    // =======================================================================================
    //      Signal Strengths Table
    // =======================================================================================
    public void cleanseCellStrengthTables(long maxTime) {
        String query = String.format("DELETE FROM %s WHERE %s < %d",
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,
                DBTableColumnIds.DBI_MEASURE_TIME,maxTime );
        Log.d(TAG, mTAG + ": Cleaning " + DBTableColumnIds.DBI_MEASURE_TABLE_NAME + " WHERE time < " + maxTime);
        mDb.execSQL(query);
    }

    public void addSignalStrength( int cellID, int signal, String timestamp ) {
        ContentValues row = new ContentValues();
        row.put(DBTableColumnIds.DBI_MEASURE_BTS_ID, cellID);
        row.put(DBTableColumnIds.DBI_MEASURE_RX_SIGNAL, signal);
        row.put(DBTableColumnIds.DBI_MEASURE_TIME, timestamp);
        //TODO are we inserting or updating? if we insert will this not wipe out all data from last known row
        mDb.insert(DBTableColumnIds.DBI_MEASURE_TABLE_NAME, null, row);
    }

    public int countSignalMeasurements(int cellID) {

        String query = String.format("SELECT COUNT(%s) FROM %s WHERE %s= %d",
                DBTableColumnIds.DBI_MEASURE_BTS_ID,
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,
                DBTableColumnIds.DBI_MEASURE_BTS_ID,cellID );
        Cursor c = mDb.rawQuery(query,null);
        c.moveToFirst();
        int lAnswer = c.getInt(0);//<--- TODO what is this returning? index 0 = _id
        c.close();
        return lAnswer;
    }

    public int getAverageSignalStrength(int cellID) {
        String query = String.format("SELECT AVG(%s) FROM %s WHERE %s= %d",
                DBTableColumnIds.DBI_MEASURE_RX_SIGNAL,
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,
                DBTableColumnIds.DBI_MEASURE_BTS_ID,cellID );
        Cursor c = mDb.rawQuery(query,null);
        c.moveToFirst();
        int lAnswer = c.getInt(0);//<--- TODO what is this returning? index 0 = _id
        c.close();
        return lAnswer;
    }

    public Cursor getSignalStrengthMeasurementData() {
        String query = String.format("SELECT %s, %s, %s FROM  %s ORDER BY %s DESC",
                DBTableColumnIds.DBI_MEASURE_BTS_ID,
                DBTableColumnIds.DBI_MEASURE_RX_SIGNAL,
                DBTableColumnIds.DBI_MEASURE_TIME,
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,
                DBTableColumnIds.DBI_MEASURE_TIME
                );
        return mDb.rawQuery(query,null);
    }

    public Cursor getOpenCellIDDataByRegion(Double lat1, Double lng1, Double lat2, Double lng2) {
        return mDb.query( DBTableColumnIds.DBE_IMPORT_TABLE_NAME,
                new String[]{"CID", "LAC", "MCC", "MNC", "gps_lat", "gps_lon", "avg_signal", "samples"},
                // avg_range, rej_cause, Type
                "? <= gps_lon AND gps_lon <= ? AND ? <= gps_lat AND gps_lat <= ?",
                new String[]{lng1.toString(), lng2.toString(), lat1.toString(), lat2.toString()},
                null, null, null
        );
    }
    //=============================================================================================
    // ********************** ALL NEW FUNCTIONS ADDED AFTER THIS LINE *****************************
    //=============================================================================================

    // ====================================================================
    //      Get all detection strings
    // ====================================================================
    public ArrayList<AdvanceUserItems> getDetectionStrings(){


        Cursor stringcount = mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME,null);

        ArrayList<AdvanceUserItems> detection_strs = new ArrayList<>();
        System.out.println("DB LEN = "+stringcount.getCount());
        if(stringcount.getCount() > 0) {
            while (stringcount.moveToNext()) {
                AdvanceUserItems setitems = new AdvanceUserItems();
                setitems.setDetection_string(stringcount.getString(stringcount.getColumnIndex(DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING)));
                setitems.setDetection_type(stringcount.getString(stringcount.getColumnIndex(DBTableColumnIds.DETECTION_STRINGS_SMS_TYPE)));
                detection_strs.add(setitems);

            }
        }else
        {
            AdvanceUserItems setitems = new AdvanceUserItems();
            setitems.setDetection_string("No data");
            setitems.setDetection_type("No data");
            detection_strs.add(setitems);
        }
        stringcount.close();

        return  detection_strs;
    }

    public boolean deleteDetectedSms(long deleteme) {
        // First check that string not in DB
        try {
            mDb.delete(DBTableColumnIds.SMS_DATA_TABLE_NAME, DBTableColumnIds.SMS_DATA_ID + "=" + deleteme,null);
            return true;
        }catch (Exception ee){Log.i("AIMSICDDbAdapter", "Sms Deleted failed");}

        return false;
    }

    public boolean deleteDetectionString(String deleteme) {

        try {
            mDb.delete(DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME, DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING + "='" + deleteme + "'",null);
            return true;
        }catch (Exception ee){Log.i("AIMSICDDbAdapter", "Delete String failed");}

        return false;

    }

    //====================================================================
    //      Insert new detection strings into database
    //====================================================================
    /*
        When inserting strings it has to be in the format
        i am a type 0 string  <-----your string can be found in logcat
        TYPE0 SILENTVOICE FLASH <--- These have to be in CAPS
        ContentValues newconvalues = new ContentValues();
        newconvalues.put(SILENT_SMS_STRING_COLUMN, "your string goes here");
        newconvalues.put(SILENT_SMS_TYPE_COLUMN, "TYPE0");
        database.insert(SILENT_SMS_STRINGS_TABLE,null,newconvalues);

     */
    public boolean insertNewDetectionString(ContentValues newstring) {
        // First check that string not in DB

        String check4String = String.format("SELECT * FROM %s WHERE %s = \"%s\"",
                DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME,
                DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING,
                newstring.get(DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING).toString());

        Cursor cursor = mDb.rawQuery(check4String, null);
        int count = cursor.getCount();
        cursor.close();
        if (count > 0) {
            Log.i(TAG, "Detection String already in Database");
        } else {

            try {
                mDb.insert(DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME, null, newstring);
                Log.i(TAG, "Detection String Added");
                return true;
            } catch (Exception ee) {
                Log.i(TAG, "Detection String failed");
            }
        }
        return false;
    }

    public CapturedSmsData storeCapturedSms(CapturedSmsData smsdata){

        ContentValues values = new ContentValues();

        values.put(DBTableColumnIds.SMS_DATA_SENDER_NUMBER, smsdata.getSenderNumber());
        values.put(DBTableColumnIds.SMS_DATA_SENDER_MSG, smsdata.getSenderMsg());
        values.put(DBTableColumnIds.SMS_DATA_TIMESTAMP, smsdata.getSmsTimestamp());
        values.put(DBTableColumnIds.SMS_DATA_SMS_TYPE, smsdata.getSmsType());
        values.put(DBTableColumnIds.SMS_DATA_LAC, smsdata.getCurrent_lac());
        values.put(DBTableColumnIds.SMS_DATA_CID, smsdata.getCurrent_cid());
        values.put(DBTableColumnIds.SMS_DATA_RAT, smsdata.getCurrent_nettype());
        values.put(DBTableColumnIds.SMS_DATA_ROAM_STATE, smsdata.getCurrent_roam_status());
        values.put(DBTableColumnIds.SMS_DATA_GPS_LAT, smsdata.getCurrent_gps_lat());
        values.put(DBTableColumnIds.SMS_DATA_GPS_LON, smsdata.getCurrent_gps_lon());


        long insertid = mDb.insert(DBTableColumnIds.SMS_DATA_TABLE_NAME,null,values);
        smsdata.setId(insertid);
        return  smsdata;
    }


    public boolean isTimeStampInDB(String TS){
        String check4timestamp = String.format("SELECT * FROM %s WHERE %s = \"%s\"",
                DBTableColumnIds.SMS_DATA_TABLE_NAME,
                DBTableColumnIds.SMS_DATA_TIMESTAMP,TS);
        Cursor timestampcount = mDb.rawQuery(check4timestamp,null);
        int count = timestampcount.getCount();
        timestampcount.close();
        if( count > 0){

            return true;
        }else{
            return false;
        }

    }

    //----RETURN FUNCTIONS START HERE-----//
    /*
        Returned Columns:
        "_id"     	INTEGER PRIMARY KEY,
	    "country"	TEXT,
	    "MCC"    	INTEGER,
	    "lat"    	TEXT,
	    "lon"    	TEXT
     */
    public Cursor returnDefaultLocation(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DEFAULT_LOCATION_TABLE_NAME,null);
    }

    /*
      Returned Columns:
    	"_id"      	INTEGER PRIMARY KEY,
    	"name"    	TEXT,
    	"type"    	TEXT,
    	"key"     	TEXT,
    	"time_add"	TEXT,
    	"time_exp"	TEXT
    */
    public Cursor returnApiKeys(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.API_KEYS_TABLE_NAME,null);
    }

    /*
      Returned Columns:
    	"_id"         	INTEGER PRIMARY KEY,
    	"name"       	TEXT,
    	"description"	TEXT,
    	"thresh"     	INTEGER,
    	"thfine"     	REAL
    */
    public Cursor returnCounterMeasures(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.COUNTER_MEASURES_TABLE_NAME,null);
    }

    /*
      Returned Columns:
	    "_id"        	INTEGER PRIMARY KEY,
	    "MCC"       	TEXT,
	    "MNC"       	TEXT,
	    "LAC"       	TEXT,
	    "op_name"   	TEXT,
        "band_plan" 	TEXT,
        "__EXPAND__"	TEXT
    */
    public Cursor returnDBeCapabilities(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DBE_CAPABILITIES_TABLE_NAME,null);
    }

    /*
        Returned Columns:
        "_id"        	INTEGER PRIMARY KEY AUTOINCREMENT,
        "DBsource"  	TEXT NOT NULL,
        "RAT"       	TEXT,
        "MCC"       	INTEGER,
        "MNC"       	INTEGER,
        "LAC"       	INTEGER,
        "CID"       	INTEGER,
        "PSC"       	INTEGER,
        "gps_lat"   	TEXT,
        "gps_lon"   	TEXT,
        "isGPSexact"	INTEGER,
        "avg_range" 	INTEGER,
        "avg_signal"	INTEGER,
        "samples"   	INTEGER,
        "time_first"	TEXT,
        "time_last" 	TEXT,
        "rej_cause" 	INTEGER
*/
    public Cursor returnDBeImport(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DBE_IMPORT_TABLE_NAME,null);
    }

    /*
        Returned Columns:
        "_id"        	INTEGER PRIMARY KEY AUTOINCREMENT,
        "RAT"       	TEXT NOT NULL,
        "MCC"       	INTEGER NOT NULL,
        "MNC"       	INTEGER NOT NULL,
        "LAC"       	INTEGER NOT NULL,
        "CID"       	INTEGER NOT NULL,
        "PSC"       	INTEGER,
        "T3212"     	INTEGER,
        "A5x"       	INTEGER,
        "ST_id"     	INTEGER,
        "time_first"	TEXT,
        "time_last" 	TEXT
*/
    public Cursor returnDBiBts(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DBI_BTS_TABLE_NAME,null);
    }

    /*
    Returned Columns:
        "_id"           INTEGER PRIMARY KEY AUTOINCREMENT,
        "bts_id"       	INTEGER NOT NULL,
        "nc_list"      	TEXT,
        "time"         	TEXT NOT NULL,
        "gpsd_lat"     	TEXT NOT NULL,
        "gpsd_lon"     	TEXT NOT NULL,
        "gpsd_accuracy"	INTEGER,
        "gpse_lat"     	TEXT,
        "gpse_lon"     	TEXT,
        "speed"        	TEXT,
        "bb_power"     	TEXT,
        "bb_rf_temp"   	TEXT,
        "tx_power"     	TEXT,
        "rx_signal"    	TEXT,
        "rx_stype"     	TEXT,
        "BCCH"         	TEXT,
        "TMSI"         	TEXT,
        "TA"           	INTEGER,
        "PD"           	INTEGER,
        "BER"          	INTEGER,
        "AvgEcNo"      	TEXT,
        "isSubmitted"  	INTEGER DEFAULT 0,
        "isNeighbour"  	INTEGER DEFAULT 0,
        FOREIGN KEY("bts_id")
        REFERENCES "DBi_bts"("_id") <--TODO how do I get this column programmattically it doesnt show up in db?
*/
    public Cursor returnDBiMeasure(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DBI_MEASURE_TABLE_NAME,null);
    }

    /*
        Returned Columns
        "_id"         	INTEGER PRIMARY KEY,
        "code"       	INTEGER,
        "name"       	TEXT,
        "description"	TEXT,
        "p1"         	INTEGER,
        "p2"         	INTEGER,
        "p3"         	INTEGER,
        "p1_fine"    	REAL,
        "p2_fine"    	REAL,
        "p3_fine"    	REAL,
        "app_text"   	TEXT,
        "func_use"   	TEXT,
        "istatus"    	INTEGER,
        "CM_id"      	INTEGER
     */
    public Cursor returnDetectionFlags(){
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DETECTION_FLAGS_TABLE_NAME,null);
    }
    /**
     Returned Columns:
     "_id"            	INTEGER PRIMARY KEY AUTOINCREMENT,
     "time"     		TEXT NOT NULL,
     "LAC"           	INTEGER NOT NULL,
     "CID"           	INTEGER NOT NULL,
     "PSC"           	INTEGER,
     "gpsd_lat"      	TEXT,--Should this be double?
     "gpsd_lon"      	TEXT,--Should this be double?
     "gpsd_accu"     	INTEGER,
     "DF_id"         	INTEGER,
     "DF_description"	TEXT,
     -- Do we need these?
     FOREIGN KEY("DF_id")
     REFERENCES "DetectionFlags"("_id")
     */
    public Cursor returnEventLogData() {
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.EVENTLOG_TABLE_NAME,null);
    }

    /**
     Returned Columns:
     "_id"         	INTEGER PRIMARY KEY,
     "description"	TEXT
     */
    public Cursor returnSectorType() {
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.SECTOR_TYPE_TABLE_NAME,null);
    }

    /**
     Returned Columns:
     "_id"     	INTEGER PRIMARY KEY AUTOINCREMENT,
     "detection_str"   	TEXT,
     "sms_type"			TEXT--(WapPush MWI TYPE0 etc..)
     */
    public Cursor returnDetectionStrings() {
        return mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME,null);
    }

    /*
    Returned Columns:
    "_id"         INTEGER PRIMARY KEY AUTOINCREMENT,
    "time"        TEXT,
    "number"      TEXT,
    "smsc"        TEXT,
    "message"     TEXT,
    "type"     	  TEXT,        -- WapPush MWI TYPE0 etc..)
    "class"  	  TEXT,			--(CLASS 0 etc...)
    "lac"         INTEGER,
    "cid"         INTEGER,
    "rat"         TEXT,
    "gps_lat"     REAL,
    "gps_lon"     REAL,
    "isRoaming"   INTEGER
 */
    public Cursor returnSmsData(){
        return  mDb.rawQuery("SELECT * FROM "+ DBTableColumnIds.SMS_DATA_TABLE_NAME,null);
    }

    //----END OF RETURN DATABASE CURSORS------//

    //----START OF INSERT FUNCTIONS-----//

    public void insertDefaultLocation(String country,
                                      int mcc,
                                      String lat,
                                      String lon){

        ContentValues def_location = new ContentValues();
        def_location.put(DBTableColumnIds.DEFAULT_LOCATION_COUNTRY, country);
        def_location.put(DBTableColumnIds.DEFAULT_LOCATION_MCC, mcc);
        def_location.put(DBTableColumnIds.DEFAULT_LOCATION_LAT, lat);
        def_location.put(DBTableColumnIds.DEFAULT_LOCATION_LON, lon);
        mDb.insert(DBTableColumnIds.DEFAULT_LOCATION_TABLE_NAME, null, def_location);
    }

    public boolean insertApiKeys(String name,
                                 String type,
                                 String key,
                                 String time_add,
                                 String time_exp){

        ContentValues ApiKeys = new ContentValues();
        ApiKeys.put(DBTableColumnIds.API_KEYS_NAME,name);
        ApiKeys.put(DBTableColumnIds.API_KEYS_TYPE,type);
        ApiKeys.put(DBTableColumnIds.API_KEYS_KEY,key);
        ApiKeys.put(DBTableColumnIds.API_KEYS_TIME_ADD,time_add);
        ApiKeys.put(DBTableColumnIds.API_KEYS_TIME_EXP,time_exp);

        String query = String.format("SELECT * FROM %s WHERE %s = \"%s\"",
                DBTableColumnIds.API_KEYS_TABLE_NAME,
                DBTableColumnIds.API_KEYS_KEY,                key);

        Cursor cursor = mDb.rawQuery(query,null);
        if( cursor.getCount() <= 0){
            //only insert if key not in db
            mDb.insert(DBTableColumnIds.API_KEYS_TABLE_NAME, null, ApiKeys);
            cursor.close();
            return true;
        }else{
            cursor.close();
            return false;
        }

    }

    public void insertCounterMeasures(String name,
                                      String description,
                                      int thresh,
                                      double thfine){

        ContentValues counterMeasures = new ContentValues();
        counterMeasures.put(DBTableColumnIds.COUNTER_MEASURES_NAME,name);
        counterMeasures.put(DBTableColumnIds.COUNTER_MEASURES_DESCRIPTION,description);
        counterMeasures.put(DBTableColumnIds.COUNTER_MEASURES_THRESH,thresh);
        counterMeasures.put(DBTableColumnIds.COUNTER_MEASURES_THFINE,thfine);

        //TODO do I need to check or update or are we just inserting without any checks
        mDb.insert(DBTableColumnIds.COUNTER_MEASURES_TABLE_NAME, null, counterMeasures);
    }

    /*
    	"MCC"       	TEXT,
        "MNC"       	TEXT,
        "LAC"       	TEXT,
        "op_name"   	TEXT,
        "band_plan" 	TEXT,
        "__EXPAND__"	TEXT

     */
    public void insertDBeCapabilities(String mcc,
                                      String mnc,
                                      String lac,
                                      String op_name,
                                      String band_plan,
                                      String __EXPAND__){

        ContentValues dbeCapabilities = new ContentValues();
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_MCC,mcc);
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_MNC,mnc);
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_LAC,lac);
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_OP_NAME,op_name);
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_BAND_PLAN,band_plan);
        dbeCapabilities.put(DBTableColumnIds.DBE_CAPABILITIES_EXPAND,__EXPAND__);

        //TODO do I need to check or update or are we just inserting without any checks
        mDb.insert(DBTableColumnIds.DBE_CAPABILITIES_TABLE_NAME, null, dbeCapabilities);
    }

    /**
     *  Description:    This method is used to insert and populate the downloaded or previously
     *                  backed up OCID details into the DBe_import database table.
     *                  It also prevents adding multiple entries of the same cell-id, when OCID
     *                  downloads are repeated.
     *
     *  Issues:     [ ] None, but see GH issue #303 for a smarter OCID download handler.
     *
     *  Notes:       a) Move to:  CellTracker.java  see:
     *                  https://github.com/SecUpwN/Android-IMSI-Catcher-Detector/issues/290#issuecomment-72303486
     *               b) OCID CellID is of the "long form" (>65535) when available...
     *               c) is also used to where CSV data is populating the opencellid table.
     *
     *
     *
     *
     *
     */
    public boolean insertDBeImport(String db_src,
                                   String rat,
                                   int mcc,
                                   int mnc,
                                   int lac,
                                   int cid,
                                   int psc,
                                   String lat,
                                   String lon,
                                   int isGPSexact,
                                   int avg_range,
                                   int avg_signal,
                                   int samples,
                                   String time_first,
                                   String time_last,
                                   int rej_cause){

        ContentValues dbeImport = new ContentValues();
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_DBSOURCE,db_src);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_RAT,rat);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_MCC,mcc);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_MNC,mnc);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_LAC,lac);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_CID,cid);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_PSC,psc);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_GPS_LAT,lat);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_GPS_LON,lon);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_IS_GPS_EXACT,isGPSexact);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_AVG_RANGE,avg_range);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_AVG_SIGNAL,avg_signal);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_SAMPLES,samples);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_TIME_FIRST,time_first);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_TIME_LAST,time_last);
        dbeImport.put(DBTableColumnIds.DBE_IMPORT_REJ_CAUSE,rej_cause);

        String query = String.format("SELECT * FROM %s WHERE %s = %d AND %s = %d ",
                DBTableColumnIds.DBE_IMPORT_TABLE_NAME,
                DBTableColumnIds.DBE_IMPORT_LAC,                lac,
                DBTableColumnIds.DBE_IMPORT_CID,                cid);

        /*
            Check that the lac and cid not known in the DBe_import
            to avoid duplicate cells

            Replaces openCellExists()
         */
        Cursor cursor = mDb.rawQuery(query,null);
        boolean isCellinDB = cursor.getCount() >0;
        cursor.close();
        return isCellinDB;
    }
    /*
        Created this because we dont need to insert all the data in this table
        because we dont have items like tmsi and others yet
     */
    public void insertBTS(Device device){

        //If lac and cellID not in DB store it
        if(!cellInDbiBts(device.mCell.getLAC(),device.mCell.getCID())) {
            ContentValues values = new ContentValues();
            values.put(DBTableColumnIds.DBI_BTS_MCC, device.mCell.getMCC());
            values.put(DBTableColumnIds.DBI_BTS_MNC, device.mCell.getMNC());
            values.put(DBTableColumnIds.DBI_BTS_LAC, device.mCell.getLAC());
            values.put(DBTableColumnIds.DBI_BTS_CID, device.mCell.getCID());
            values.put(DBTableColumnIds.DBI_BTS_PSC, device.mCell.getPSC());
            values.put(DBTableColumnIds.DBI_BTS_TIME_FIRST, MiscUtils.getCurrentTimeStamp());
            values.put(DBTableColumnIds.DBI_BTS_TIME_LAST, MiscUtils.getCurrentTimeStamp());
            values.put(DBTableColumnIds.DBI_BTS_LAT, device.mCell.getLat());
            values.put(DBTableColumnIds.DBI_BTS_LON, device.mCell.getLon());
            mDb.insert(DBTableColumnIds.DBI_BTS_TABLE_NAME, null, values);

            Log.i(TAG, "Dbi_bts inserted");
        }else{
            //if cell is in the DB update it to last time seen
            ContentValues values = new ContentValues();
            values.put(DBTableColumnIds.DBI_BTS_TIME_LAST, MiscUtils.getCurrentTimeStamp());
            mDb.update( DBTableColumnIds.DBI_BTS_TABLE_NAME,
                    values,
                    "CID=?", new String[]{Integer.toString(device.mCell.getCID())} );
            Log.i(TAG, "Dbi_bts Last Seen Updated");

        }

        //Checking to see is cellID already in DBi_measure--|
        if(!cellInDbiMeasure(device.mCell.getCID())){//<----|
            ContentValues dbiMeasure = new ContentValues();

            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BTS_ID,device.mCell.getCID());
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_NC_LIST,nc_list);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TIME, MiscUtils.getCurrentTimeStamp());

            String slat = String.valueOf(device.mCell.getLat());
            String slon = String.valueOf(device.mCell.getLon());

            if (slat == null){slat = "0.0";}
            if (slon == null){slat = "0.0";}

            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_LAT, slat);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_LON, slon);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_ACCURACY, device.mCell.getAccuracy());
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSE_LAT,gpse_lat);
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSE_LON,gpse_lon);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BB_POWER,String.valueOf(device.mCell.getDBM()));
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BB_RF_TEMP,bb_rf_temp);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TX_POWER,String.valueOf(device.mCell.getRssi()));
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RX_SIGNAL,rx_signal);
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RX_STYPE,rx_stype);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RAT, String.valueOf(device.mCell.getNetType()));
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BCCH,BCCH);
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TMSI,TMSI);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TA,device.mCell.getTimingAdvance());
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_PD,PD);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BER,(int)device.mCell.getBearing());//TODO this isnt bearing where do we get this value?
            //dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_AVG_EC_NO,AvgEcNo);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_IS_SUBMITTED,1);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_IS_NEIGHBOUR,0);
            mDb.insert(DBTableColumnIds.DBI_MEASURE_TABLE_NAME, null, dbiMeasure);

            Log.i(TAG, "Dbi_measure inserted");

        }else{
            //TODO: do we need to update DBi_measure with any data if the cell is already known to database
         }

    }
    /**
     * Inserts (API?) Cell Details into Database (DBi_bts)
     */
    public void insertBTS(
                           int mcc,
                           int mnc,
                           int lac,
                           int cid,
                           int psc,
                           int t3231,
                           int a5x,
                           int st_id,
                           String time_first,
                           String time_last,
                           double lat,
                           double lon
    ) {
        //TODO this was the old if statement if (cid != -1 && (latitude != 0.0 && longitude != 0.0)) do we need to add other checks?
        if (cid != -1) {
            //Populate Content Values for Insert or Update
            ContentValues btsValues = new ContentValues();
            btsValues.put(DBTableColumnIds.DBI_BTS_MCC,        mcc);
            btsValues.put(DBTableColumnIds.DBI_BTS_MNC,        mnc);
            btsValues.put(DBTableColumnIds.DBI_BTS_LAC,        lac);
            btsValues.put(DBTableColumnIds.DBI_BTS_CID,        cid);
            btsValues.put(DBTableColumnIds.DBI_BTS_PSC,        psc);
            btsValues.put(DBTableColumnIds.DBI_BTS_T3212,    t3231);
            btsValues.put(DBTableColumnIds.DBI_BTS_A5X,        a5x);
            btsValues.put(DBTableColumnIds.DBI_BTS_ST_ID,    st_id);
            btsValues.put(DBTableColumnIds.DBI_BTS_TIME_FIRST, time_first);
            btsValues.put(DBTableColumnIds.DBI_BTS_TIME_LAST,  time_last);
            btsValues.put(DBTableColumnIds.DBI_BTS_LAT, lat);
            btsValues.put(DBTableColumnIds.DBI_BTS_LON,  lon);


            String query = String.format("SELECT * FROM %s WHERE %s = %d AND %s = %d",
                    DBTableColumnIds.DBI_BTS_TABLE_NAME,
                    DBTableColumnIds.DBI_BTS_LAC,                    lac,
                    DBTableColumnIds.DBI_BTS_CID,                    cid);

            //Check that the lac and cid not known if not insert
            Cursor cursor = mDb.rawQuery(query,null);
            if( cursor.getCount() <= 0){
                cursor.close();
                mDb.insert(DBTableColumnIds.DBI_BTS_TABLE_NAME, null, btsValues);

            }else{
                //TODO EVA do I need to update an already known cell?
                cursor.close();
                mDb.update( DBTableColumnIds.DBI_BTS_TABLE_NAME, btsValues, "CID=?", new String[]{Integer.toString(cid)} );

            }

        }

    }

    /**
        TODO: add descritpion what this functions does
    */
    public void insertDbiMeasure(int bts_id,
                                 String nc_list,
                                 String time,
                                 String gpsd_lat,
                                 String gpsd_lon,
                                 int gpsd_accuracy,
                                 String gpse_lat,
                                 String gpse_lon,
                                 String bb_power,
                                 String bb_rf_temp,
                                 String tx_power,
                                 String rx_signal,
                                 String rx_stype,
                                 String rat,
                                 String BCCH,
                                 String TMSI,
                                 int TA,
                                 int PD,
                                 int BER,
                                 String AvgEcNo,
                                 int isSubmitted,
                                 int isNeighbour){

        //Check bts_id is not already stored int Dbi_measure. Only adds new cell if false
        if(cellInDbiMeasure(bts_id)){
            ContentValues dbiMeasure = new ContentValues();
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BTS_ID,bts_id);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_NC_LIST,nc_list);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TIME,time);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_LAT,gpsd_lat);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_LON,gpsd_lon);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSD_ACCURACY,gpsd_accuracy);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSE_LAT,gpse_lat);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_GPSE_LON,gpse_lon);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BB_POWER,bb_power);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BB_RF_TEMP,bb_rf_temp);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TX_POWER,tx_power);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RX_SIGNAL,rx_signal);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RX_STYPE,rx_stype);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_RAT,rat);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BCCH,BCCH);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TMSI,TMSI);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_TA,TA);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_PD,PD);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_BER,BER);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_AVG_EC_NO,AvgEcNo);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_IS_SUBMITTED,isSubmitted);
            dbiMeasure.put(DBTableColumnIds.DBI_MEASURE_IS_NEIGHBOUR,isNeighbour);
            mDb.insert(DBTableColumnIds.DBI_MEASURE_TABLE_NAME, null, dbiMeasure);

        }

    }

    /**
     TODO: add descritpion what this functions does
     */
    public void insertDetectionFlags(int code,
                                     String name,
                                     String description,
                                     int p1,int p2,int p3,
                                     double p1_fine,double p2_fine,double p3_fine,
                                     String app_text,
                                     String func_use,
                                     int istatus,
                                     int CM_id
    ){

        ContentValues detectionFlags = new ContentValues();
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_CODE,code);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_NAME,name);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_DESCRIPTION,description);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P1,p1);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P2,p2);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P3,p3);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P1_FINE,p1_fine);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P2_FINE,p2_fine);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_P3_FINE,p3_fine);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_APP_TEXT,app_text);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_FUNC_USE,func_use);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_IS_STATUS,istatus);
        detectionFlags.put(DBTableColumnIds.DETECTION_FLAGS_CM_ID,CM_id);

        mDb.insert(DBTableColumnIds.DETECTION_FLAGS_TABLE_NAME, null, detectionFlags);
    }

    /**
     * Description:     Inserts log data into the EventLog table
     *
     * Notes:           Table item order:
     *                  time,LAC,CID,PSC,gpsd_lat,gpsd_lon,gpsd_accu,DF_id,DF_desc
     *
     *
     * This table was previously known as insertDetection
     */
    public void insertEventLog(String time,
                               int lac,
                               int cid,
                               int psc,
                               String gpsd_lat,
                               String gpsd_lon,
                               int gpsd_accu,
                               int DF_id,
                               String DF_description){

        //Query to check that the event was already logged
        //SELECT * FROM EventLog WHERE CID = 1234 AND LAC = 4321 AND DF_id BETWEEN 1 AND 4
        String query = String.format("SELECT * FROM %s WHERE %s = %d AND %s = %d AND %s BETWEEN 1 AND 4",
                DBTableColumnIds.EVENTLOG_TABLE_NAME,       //EventLog
                DBTableColumnIds.EVENTLOG_CID,  cid,        //CID
                DBTableColumnIds.EVENTLOG_LAC,  lac,        //LAC
                DBTableColumnIds.EVENTLOG_DF_ID,  DF_id);   //DF_id

        //Check that the lac and cid not known if not insert
        Cursor cursor = mDb.rawQuery(query,null);


        if( cursor.getCount() <= 0){
            cursor.close();
            //Event not logged so sending to database
            ContentValues eventLog = new ContentValues();
            eventLog.put(DBTableColumnIds.EVENTLOG_TIME,time);
            eventLog.put(DBTableColumnIds.EVENTLOG_LAC,lac);
            eventLog.put(DBTableColumnIds.EVENTLOG_CID,cid);
            eventLog.put(DBTableColumnIds.EVENTLOG_PSC,psc);
            eventLog.put(DBTableColumnIds.EVENTLOG_LAT,gpsd_lat);
            eventLog.put(DBTableColumnIds.EVENTLOG_LON,gpsd_lon);
            eventLog.put(DBTableColumnIds.EVENTLOG_ACCU,gpsd_accu);
            eventLog.put(DBTableColumnIds.EVENTLOG_DF_ID,DF_id);
            eventLog.put(DBTableColumnIds.EVENTLOG_DF_DESC,DF_description);

            mDb.insert(DBTableColumnIds.EVENTLOG_TABLE_NAME, null, eventLog);
            Log.v(TAG, mTAG + ": Insert Detection into EventLog Table: " + cid);

        }else{
            //TODO do we need to do anything if event already logged?
        }
        cursor.close();

    }

    /**
     TODO: add descritpion what this functions does
     */
    public void insertSectorType(String description){

        ContentValues sectorType = new ContentValues();
        sectorType.put(DBTableColumnIds.SECTOR_TYPE_DESCRIPTION,description);
        mDb.insert(DBTableColumnIds.SECTOR_TYPE_TABLE_NAME, null, sectorType);

    }

    /**
     TODO: add descritpion what this functions does
     */
    public void insertDetectionStrings(String detection_string,
                                          String sms_type){

        ContentValues detectonStrings = new ContentValues();
        detectonStrings.put(DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING,detection_string);
        detectonStrings.put(DBTableColumnIds.DETECTION_STRINGS_SMS_TYPE,sms_type);


        String query = String.format("SELECT * FROM %s WHERE %s = \"%s\" AND %s = \"%s\"",
                DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME,
                DBTableColumnIds.DETECTION_STRINGS_LOGCAT_STRING,          detection_string,
                DBTableColumnIds.DETECTION_STRINGS_SMS_TYPE,                sms_type);

        //Check that string not in db then insert
        Cursor cursor = mDb.rawQuery(query,null);

        if( cursor.getCount() <= 0){
            mDb.insert(DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME, null, detectonStrings);
            cursor.close();
        }else{
            cursor.close();
         }
    }

    /**
         Description:
                    inserts detected silent sms data into TABLE: SmsData
     */
    public boolean insertSmsData(String time,
                                 String number,
                                 String smsc,
                                 String message,
                                 String type,
                                 String CLASS,//<-- had to put in uppercase class is used be api
                                 int lac,
                                 int cid,
                                 String rat,
                                 double gps_lat,
                                 double gps_lon,
                                 int isRoaming){

        ContentValues smsData = new ContentValues();
        smsData.put(DBTableColumnIds.SMS_DATA_TIMESTAMP,time);
        smsData.put(DBTableColumnIds.SMS_DATA_SENDER_NUMBER,number);
        smsData.put(DBTableColumnIds.SMS_DATA_SENDER_SMSC,smsc);
        smsData.put(DBTableColumnIds.SMS_DATA_SENDER_MSG,message);
        smsData.put(DBTableColumnIds.SMS_DATA_SMS_TYPE,type);
        smsData.put(DBTableColumnIds.SMS_DATA_SMS_CLASS,CLASS);
        smsData.put(DBTableColumnIds.SMS_DATA_LAC,lac);
        smsData.put(DBTableColumnIds.SMS_DATA_CID,cid);
        smsData.put(DBTableColumnIds.SMS_DATA_RAT,rat);
        smsData.put(DBTableColumnIds.SMS_DATA_GPS_LAT,gps_lat);
        smsData.put(DBTableColumnIds.SMS_DATA_GPS_LON,gps_lon);
        smsData.put(DBTableColumnIds.SMS_DATA_ROAM_STATE,isRoaming);



        String query = String.format("SELECT * FROM %s WHERE %s = \"%s\"",
                DBTableColumnIds.SMS_DATA_TABLE_NAME,
                DBTableColumnIds.SMS_DATA_TIMESTAMP,                time);

        //Check that timestamp not in db then insert
        Cursor cursor = mDb.rawQuery(query,null);
        if( cursor.getCount() <= 0){
            mDb.insert(DBTableColumnIds.DETECTION_STRINGS_TABLE_NAME, null, smsData);
            cursor.close();
            return true;
        }else{
            cursor.close();
            return false;
        }
    }


    /**
     *  Description:    This checks if a cell with a given CID already exists
     *                  in the (DBe_import) database.
     */
    public boolean openCellExists(int cellID) {
        String qry = String.format("SELECT * FROM %s WHERE %s = %d",
                DBTableColumnIds.DBE_IMPORT_TABLE_NAME,
                DBTableColumnIds.DBE_IMPORT_CID,                cellID);
        Cursor cursor = mDb.rawQuery(qry, null);
        boolean exists = cursor.getCount() > 0;
        //Log.v(TAG, mTAG + ": Does CID: " + cellID + " exist in DBe_import? " + exists);
        cursor.close();
        return exists;
    }

    /*
        Check cid and lac is in Dbi_bts
        Replaces cellExists()
    */
    public boolean cellInDbiBts(int lac,int cellID){
        String query = String.format("SELECT * FROM %s WHERE %s = %d AND %s = %d",
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_LAC,                lac,
                DBTableColumnIds.DBI_BTS_CID,                cellID);

        Cursor cursor = mDb.rawQuery(query,null);
        if(cursor.getCount() > 0)
        {cursor.close();
            return true;
        }else{cursor.close();
            return false;
        }

    }

    /*
        Check is cid(bts_id) in DBi_measure
    */
    public boolean cellInDbiMeasure(int cellID){
        String query = String.format("SELECT * FROM %s WHERE %s = %d",
                DBTableColumnIds.DBI_MEASURE_TABLE_NAME,
                DBTableColumnIds.DBI_MEASURE_BTS_ID,                cellID);

        Cursor cursor = mDb.rawQuery(query,null);
        if(cursor.getCount() > 0)
        {cursor.close();
            return true;
        }else{cursor.close();
            return false;
        }

    }

    /*
        Check that the time_first timestamp in Dbi_bts is set
        and if set that means we only need to update time_last
    */
    public boolean firstTimeSeenSet(int lac,int cellID){
        String query = String.format("SELECT * FROM %s WHERE %s = %d AND %s = %d",
                DBTableColumnIds.DBI_BTS_TABLE_NAME,
                DBTableColumnIds.DBI_BTS_LAC,                lac,
                DBTableColumnIds.DBI_BTS_CID,                cellID);
        Cursor cursor = mDb.rawQuery(query,null);

        if (cursor.moveToNext()){
            String timestamp = cursor.getString(cursor.getColumnIndex(DBTableColumnIds.DBI_BTS_TIME_FIRST));
            if(timestamp != null)
            {
                cursor.close();
                return true;
            }
        }
        cursor.close();
        return false;
        }
}

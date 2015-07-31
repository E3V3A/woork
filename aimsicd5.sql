/*
=======================================================================
FileName:	aimsicd.sql
Version:	0.5
Formatting:	8 char TAB, ASCII, UNIX EOL
Author:		E:V:A (Team AIMSICD)
Date:		2015-01-24
Last:		2015-07-31
Info:		https://github.com/SecUpwN/Android-IMSI-Catcher-Detector
=======================================================================

Description:

	This is the aimsicd.db SQL script. It is used in the 
	"Android IMIS-Cather Detector" (AIMSICD) application, 
	to create all and pre-populate some its DB tables.

Dependencies:


Pre-loaded Imports:

	* defaultlocation.csv	# The default MCC country values
	* DetectionFlags.csv	# Detection flag descriptions and parameter settings
	- CounterMeasures.csv	# Counter-measures descriptions and thresholds
	- API_keys.csv		# API keys for various external DBs (expiration)
	? DBe_capabilities	# MNO capabilities details (WIP)
	---------------------------------------------------------------
	[*,-,?] = required, WIP, maybe
	---------------------------------------------------------------

How to use:

	# To build the database use: 
	# cd /data/data/com.SecUpwN.AIMSICD/databases/
	# cat aimsicd.sql | sqlite3 aimsicd.db

Developer Notes:

	a) The sqlite_sequence table is created and initialized 
	   automatically whenever a normal table that contains 
	   an AUTOINCREMENT column is created.

	b) Older AOS require that the primary id field of your tables 
	   had to be "_id" so Android would know where to bind the id 
	   field of your tables. This is no longer required, but we
	   decided to keep the underscore "_" to indicate and easily 
	   identfy a primary key field. We may reconsider.

	c) Older AOS DBs also required that the "android_metadata" be
	   pre-cresated and populated. This is no longer needed (?) 
	   and handled automatically by AOS.

	d) PSC is used to identify different WCDMA cells on the downlink 
	   while Secondary Scrambling Codes are used to differentiate
	   UE on the uplink. 

	   So is the AOS provided PSC, the Uplink (UL) or the Downlink (DL) PSC? 
	   - PSC in the downlink seperate cells, while in the uplink they seperate users.
	   - Each cell has a different scrambling code on downlink, while on uplink each 
	     user has a different scrambling code.

	e) The SQL foreign key constraint is used to enforce the relationship 
	   between the DBi_bts and the DBi_measure table. When enabled, the 
	   constraint is enforced by SQLite. Attempting to insert a row into 
	   the DBi_measure table that does not correspond to any row in the 
	   DBi_bts table will fail, as will attempting to delete a row from 
	   the DBi_bts table when there exist dependent rows in the 
	   DBi_measure table .
	
	   Foreign key constraints are disabled by default (for backwards 
	   compatibility), so must be enabled separately for each database 
	   connection. To test the foreign_keys PRAGMA status, use:

		sqlite> PRAGMA foreign_keys;
	   
	   To enable the foreign key constraints in the SQLite DB, use:

		sqlite> PRAGMA foreign_keys=ON;

	   For details and more advanced use, see [2].

	f) PRAGMA cache_spill=1 has the side-effect of acquiring an EXCLUSIVE 
	   lock on the database file. Hence, some applications that have large 
	   long-running transactions may want to disable cache spilling in 
	   order to prevent the application from acquiring an exclusive lock 
	   on the database until the moment that the transaction COMMITs.


ChangeLog:

	2015-01-24	E:V:A	Removed PK/FK on EventLog Table:
				FOREIGN KEY("DF_id")
				REFERENCES "DetectionFlags"("_id")
	2015-07-18	E:V:A	Updated THIS schema to match recent DB overhaul PR.
				and changed all time/date related data types to use INTEGER
	2015-07-20	E:V:A	Made all GPS related data types REAL
				Made all (except SmsData) time/date related data types INTEGER


=======================================================================
*/

-- ========================================================
--  Special Reference Notes
-- ========================================================
/* 
  DBe_capabilities:
  [1] http://en.wikipedia.org/wiki/Cellular_network#Coverage_comparison_of_different_frequencies

  SQLite foreign keys usage:
  [2] https://www.sqlite.org/foreignkeys.html

  SQLite PRAGMA Statements:
  [3] https://www.sqlite.org/pragma.html

*/

-- ========================================================
--  START
-- ========================================================

-- // Enable some PRAGMA's

PRAGMA foreign_keys=ON;			-- 0 by default, Enforce foreign key constraints 
-- PRAGMA page_size=bytes;		-- 1024 by default, increase for huge tables
-- PRAGMA cache_size=pages; 		-- 0 by default TEMP, otherwise ~2000
-- PRAGMA cache_spill=boolean; 		-- 1 by default, disable to prevent locks
-- PRAGMA threads=N;			-- 0 by default, enable for more threads

BEGIN TRANSACTION;

-- ========================================================
-- Let's drop old tables upon first app install/update
-- ========================================================

-- // perhaps we want some persistent tables?
DROP TABLE IF EXISTS "API_keys";	-- only useful if we have multiple sources
DROP TABLE IF EXISTS "EventLog";	-- should be cleared from within app
DROP TABLE IF EXISTS "DetectionStrings";-- should be loaded from file?

-- // volatile tables
DROP TABLE IF EXISTS "android_metadata";
DROP TABLE IF EXISTS "defaultlocation";
DROP TABLE IF EXISTS "CounterMeasures";
DROP TABLE IF EXISTS "DBe_capabilities";
DROP TABLE IF EXISTS "DBe_import";
DROP TABLE IF EXISTS "DBi_bts";
DROP TABLE IF EXISTS "DBi_measure";
DROP TABLE IF EXISTS "DetectionFlags";
DROP TABLE IF EXISTS "SectorType";
DROP TABLE IF EXISTS "SmsData";

-- ========================================================
-- CREATE new tables 
-- ========================================================

CREATE TABLE "android_metadata"  ( 
	"locale"	TEXT DEFAULT 'en_US'
	);

CREATE TABLE "defaultlocation"  ( 
	"_id"     	INTEGER PRIMARY KEY,
	"country"	TEXT,			--
	"MCC"    	INTEGER,		-- 
	"lat"    	REAL,			-- 
	"lon"    	REAL			-- 
	);

CREATE TABLE "API_keys"  ( 
	"_id"      	INTEGER PRIMARY KEY,
	"name"    	TEXT,			-- 
	"type"    	TEXT,			-- 
	"key"     	TEXT,			-- 
	"time_add"	INTEGER,		-- 
	"time_exp"	INTEGER			-- 
	);

CREATE TABLE "CounterMeasures"  ( 
	"_id"         	INTEGER PRIMARY KEY,
	"name"       	TEXT,			-- 
	"description"	TEXT,			--  
	"thresh"     	INTEGER,		--  INT [0-5]  "Threat Level Detection Threshold": Settings as used before taking any action.
	"thfine"     	REAL			-- REAL [0-1]  Threashold fine tuning parameter
	);

CREATE TABLE "DBe_capabilities"  ( 
	"_id"        	INTEGER PRIMARY KEY,
	"MCC"       	TEXT,			-- 
	"MNC"       	TEXT,			-- 
	"LAC"       	TEXT,			-- 
	"op_name"   	TEXT,			-- 
	"band_plan" 	TEXT,			-- 
	"__EXPAND__"	TEXT			-- 
	);

CREATE TABLE "DBe_import"  ( 
	"_id"        	INTEGER PRIMARY KEY AUTOINCREMENT,
	"DBsource"  	TEXT NOT NULL,		-- * The source of imported data:  OCID, MLS, SkyHook etc.
	"RAT"       	TEXT,			-- Consider making this an INTEGER
	"MCC"       	INTEGER,		-- 
	"MNC"       	INTEGER,		-- 
	"LAC"       	INTEGER,		-- 
	"CID"       	INTEGER,		-- 
	"PSC"       	INTEGER,		-- 
	"gps_lat"   	REAL,			-- 
	"gps_lon"   	REAL,			-- 
	"isGPSexact"	INTEGER,		-- 
	"avg_range" 	INTEGER,		-- 
	"avg_signal"	INTEGER,		-- [dBm]  Does this need to be REAL for "-nn"?
	"samples"   	INTEGER,		--        Does this need to be REAL for "-1"
	"time_first"	INTEGER,		-- * Does not exsist in OCID 
	"time_last" 	INTEGER,		-- * Does not exsist in OCID 
	"rej_cause" 	INTEGER			-- * Updated by the DB consistency check
	);


CREATE TABLE "DBi_bts"  ( 
	"_id"        	INTEGER PRIMARY KEY AUTOINCREMENT,
	"MCC"       	INTEGER NOT NULL,	-- 
	"MNC"       	INTEGER NOT NULL,	-- 
	"LAC"       	INTEGER NOT NULL,	-- 
	"CID"       	INTEGER NOT NULL,	-- 
	"PSC"       	INTEGER,		--	-- Does AOS API provide the UL or DL PSC?
	"T3212"     	INTEGER DEFAULT 0,	-- Fix java to allow null here
	"A5x"       	INTEGER DEFAULT 0,	-- Fix java to allow null here
	"ST_id"     	INTEGER DEFAULT 0,	-- Fix java to allow null here
	"time_first"	INTEGER NOT NULL,	-- 
	"time_last" 	INTEGER NOT NULL,	-- 
	"gps_lat"       REAL,			-- Exact GPS, either from DBi_import (when available) or by manual placement
        "gps_lon"       REAL			-- Exact GPS, either from DBi_import (when available) or by manual placement
	);

CREATE TABLE "DBi_measure"  ( 
	"_id"           INTEGER PRIMARY KEY AUTOINCREMENT,
	"bts_id"       	INTEGER NOT NULL,	-- DBi_bts:_id
	"nc_list"      	TEXT,			-- Neighboring Cells List (TODO: specify content)
	"time"         	INTEGER NOT NULL,	-- [s]
	"gpsd_lat"     	REAL,			-- Device GPS (allow NULL)
	"gpsd_lon"     	REAL,			-- Device GPS (allow NULL)
	"gpsd_accu"	INTEGER,		-- Device GPS position accuracy [m]
	"bb_power"     	INTEGER,		-- [mW] or [mA]		(from BP power rail usage)
	"bb_rf_temp"   	INTEGER,		-- [C]			(from BP internal thermistor)
	"tx_power"     	INTEGER,		-- [dBm]		(from BP )
	"rx_signal"    	INTEGER,		-- [dBm] or ASU		(from API or BP) -- Consider REAL (usually negative)
	"rx_stype"     	TEXT,			-- Reveived Signal power Type [RSSI, ...] etc.
	"RAT"		INTEGER NOT NULL,	-- Radio Access Technology 
	"BCCH"         	INTEGER,		-- Broadcast Channel
	"TMSI"         	TEXT,			-- Temporary IMSI (hex)
	"TA"           	INTEGER DEFAULT 0,	-- Timing Advance (GSM, LTE)	-- allow NULL
	"PD"           	INTEGER DEFAULT 0,	-- Propagation Delay (LTE)	-- allow NULL
	"BER"          	INTEGER DEFAULT 0,	-- Bit Error Rate		-- allow NULL
	"AvgEcNo"      	INTEGER DEFAULT 0,	-- Average Ec/No		-- consider REAL? Why?
	"isSubmitted"  	INTEGER DEFAULT 0,	-- * Has been submitted to OCID/MLS etc?
	"isNeighbour"  	INTEGER DEFAULT 0,	-- * Is a neighboring BTS? [Is this what we want?]
	"con_state"	TEXT NOT NULL,		-- AOS: DATA_ACTIVITY/CONNECTION and CALL/SERVICE_STATE	
	FOREIGN KEY("bts_id")			-- 
	REFERENCES "DBi_bts"("_id")		-- 
	);

CREATE TABLE "DetectionFlags"  ( 
	"_id"         	INTEGER PRIMARY KEY,
	"code"       	INTEGER,		-- The IMSI-Catcher-Catcher Variable Name (S1,...,L4, etc.)
	"name"       	TEXT,			-- The internal AIMSICD flag name (if any) or free field
	"description"	TEXT,			-- Detailed description of detection flag. 
	"p1"         	INTEGER,		-- INT [1-3]  "color code"; Used to give a rough measure of variable precedence.
	"p2"         	INTEGER,		-- INT [1-3]  "Variable Interception Priority": To what extent the variable is used to for tracking your network connections.
	"p3"         	INTEGER,		-- INT [1-3]  "Variable Localization Priority": To what extent the variable is used to localize the victim.
	"p1_fine"    	REAL,			-- REAL [0-1] For p1 fine-tuning and settings of variables
	"p2_fine"    	REAL,			-- REAL [0-1] For p2 fine-tuning and settings of variables
	"p3_fine"    	REAL,			-- REAL [0-1] For p3 fine-tuning and settings of variables
	"app_text"   	TEXT,			-- TEXT       Application Information Text: Short text to be shown when pushing (i)
	"func_use"   	TEXT,			-- TEXT       Where in the Java code it is used
	"istatus"    	INTEGER,		-- INT [0-3]  Implementation Status: [0,1,2,3]=[not,WIP,complete,deprecated]
	"CM_id"      	INTEGER			-- INT        Virtual FK("CounterMeasures:_id") Description of possible Counter Measures, if any.
	);

CREATE TABLE "EventLog"  ( 
	"_id"           INTEGER PRIMARY KEY AUTOINCREMENT,
	"time"     	INTEGER NOT NULL,	--  DEFAULT current_timestamp ?
	"LAC"           INTEGER NOT NULL,	-- 
	"CID"           INTEGER NOT NULL,	-- 
	"PSC"           INTEGER,		-- 
	"gpsd_lat"      REAL,			-- 
	"gpsd_lon"      REAL,			-- 
	"gpsd_accu"     INTEGER,		-- 
	"DF_id"         INTEGER,		-- 
	"DF_desc"	TEXT			-- 
	);

CREATE TABLE "SectorType"  ( 
	"_id"         	INTEGER PRIMARY KEY,
	"description"	TEXT			-- Description of where MNO CID vs. antenna sectors are  
	);

CREATE TABLE "SmsData"  ( 
	"_id"     	INTEGER PRIMARY KEY AUTOINCREMENT,
	"time"   	INTEGER NOT NULL,	-- 
	"number"	TEXT,			-- 
	"smsc"		TEXT,			-- 
	"message"	TEXT,			-- 
	"type"		TEXT,			-- perhaps "class" as in ETSI docs. (WapPush, MWI, TYPE0 etc..)
	"class"		TEXT,			-- (Type-0, CLASS 0, etc...)
	"lac"		INTEGER,		-- 
	"cid"		INTEGER,		-- 
	"rat"		TEXT,			-- "current_net_type"
	"gps_lat"	REAL,			-- 
	"gps_lon"	REAL,			-- 
	"isRoaming"	INTEGER			-- "roam_state" (Boolean)
	);

CREATE TABLE "DetectionStrings"  (
	"_id"		INTEGER PRIMARY KEY AUTOINCREMENT,
	"det_str"	TEXT,			-- Logcat detection string 
	"sms_type"	TEXT			-- see "class" and "type" in SmsData
	);   

-- ========================================================
-- CREATE some VIEW's
-- ========================================================


-- ========================================================
-- CREATE some INDEX'es
-- ========================================================


-- ========================================================
-- INSERT of required or pre-populated tables 
-- ========================================================

-- INSERT INTO "android_metadata" VALUES ('en_US');
-- INSERT INTO "SmsData" VALUES (1,"2015-01-24 21:00:00",ADREZZ,DizzPlay,CLAZZ,ZMZC,DaTestMessage);



-- ========================================================
--   END
-- ========================================================
COMMIT;

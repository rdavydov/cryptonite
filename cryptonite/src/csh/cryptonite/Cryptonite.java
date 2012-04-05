// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

// Copyright (c) 2012, Christoph Schmidt-Hieber

package csh.cryptonite;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Arrays;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.ActivityManager.RunningServiceInfo;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import android.graphics.drawable.Drawable;

import android.net.Uri;

import android.os.Bundle;
import android.os.Environment;

import android.support.v4.app.FragmentActivity;
import android.support.v4.view.ViewPager;

import android.text.SpannableString;
import android.text.util.Linkify;
import android.text.method.ScrollingMovementMethod;

import android.util.Log;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;

import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.Session.AccessType;
import com.dropbox.client2.session.TokenPair;

import csh.cryptonite.storage.Storage;
import csh.cryptonite.storage.VirtualFile;

public class Cryptonite extends FragmentActivity
{

    private static final int REQUEST_PREFS=0, REQUEST_CODE_PICK_FILE_OR_DIRECTORY=1;
    public static final int MOUNT_MODE=0, SELECTLOCALENCFS_MODE=1, SELECTDBENCFS_MODE=2,
        VIEWMOUNT_MODE=3, SELECTLOCALEXPORT_MODE=4, LOCALEXPORT_MODE=5, DROPBOX_AUTH_MODE=6,
        SELECTDBEXPORT_MODE=7, DBEXPORT_MODE=8, SELECTLOCALUPLOAD_MODE=9, SELECTDBUPLOAD_MODE=10;
    private static final int DIRPICK_MODE=0;
    public static final int FILEPICK_MODE=1;
    protected static final int MSG_SHOW_TOAST = 0;
    public static final int MY_PASSWORD_DIALOG_ID = 0, MY_PASSWORD_CONFIRM_DIALOG_ID = 4;
    private static final int DIALOG_MARKETNOTFOUND=1, DIALOG_OI_UNAVAILABLE=2, DIALOG_JNI_FAIL=3, 
            DIALOG_TERM_UNAVAILABLE=5;
    private static final int MAX_JNI_SIZE = 512;
    public static final int TERM_UNAVAILABLE=0, TERM_OUTDATED=1, TERM_AVAILABLE=2;
    public static final String MNTPNT = "/csh.cryptonite/mnt";
    public static final String TAG = "cryptonite";
    public static final String DBTAB_TAG = "tab_db", LOCALTAB_TAG="tab_local", EXPERTTAB_TAG="tab_expert";

    private String encfsVersion;
    private String opensslVersion;
    public String mountInfo;
    public String textOut;
    
    private static boolean hasJni = false;
    
    public String currentDialogStartPath = "/";
    public String currentDialogLabel = "";
    public String currentDialogButtonLabel = "OK";
    public String currentDialogRoot = "/";
    public String currentDialogRootName = currentDialogRoot;
    private String currentReturnPath = "/";
    private String currentOpenPath = "/";
    private String currentUploadPath = "/";
    private String currentPassword = "\0";
    private String encfsBrowseRoot = "/";
    private String[] currentReturnPathList = {};
    public int currentDialogMode = SelectionMode.MODE_OPEN;

    public String mntDir = "/sdcard" + MNTPNT;
    public int opMode = -1;
    public int prevMode = -1;
    private boolean alert = false;
    private String alertMsg = "";
 
    public boolean mLoggedIn = false;
    public boolean hasFuse = false;
    public boolean triedLogin = false;
    private boolean mInstrumentation = false;
    private boolean mUseAppFolder;
    
    private TabHost mTabHost;
    private ViewPager  mViewPager;
    private TabsAdapter mTabsAdapter;
    
    private DropboxFragment dbFragment;
    private LocalFragment localFragment;
    
    public CryptoniteApp mApp;
    
    // If you'd like to change the access type to the full Dropbox instead of
    // an app folder, change this value.
    final static private AccessType ACCESS_TYPE = AccessType.DROPBOX;

    final static public String ACCOUNT_PREFS_NAME = "csh.cryptonite_preferences";
    final static public String ACCOUNT_DB_PREFS_NAME = "csh.cryptonite_db_preferences";
    final static private String ACCESS_KEY_NAME = "ACCESS_KEY";
    final static private String ACCESS_SECRET_NAME = "ACCESS_SECRET";
    
    /** Called when the activity is first created. */
    @Override public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.main);

        mApp = (CryptoniteApp) getApplication();
        getResources();

        if (!hasJni) {
            jniFail();
            return;
        }
        
        encfsVersion = "EncFS " + jniEncFSVersion();
        opensslVersion = jniOpenSSLVersion();
        textOut = encfsVersion + "\n" + opensslVersion;
        Log.v(TAG, encfsVersion + " " + opensslVersion);

        SharedPreferences prefs = getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        mApp.setupReadDirs(prefs.getBoolean("cb_extcache", false));

        if (!externalStorageIsWritable() || !ShellUtils.supportsFuse()) {
            mountInfo = getString(R.string.mount_info_unsupported);
        } else {
            mountInfo = getString(R.string.mount_info);
            mntDir = prefs.getString("txt_mntpoint", defaultMntDir());
            File mntDirF = new File(mntDir);
            if (!mntDirF.exists()) {
                mntDirF.mkdirs();
            }
        }

        hasFuse = ShellUtils.supportsFuse();

        /* Running from Instrumentation? */
        if (getIntent() != null) {
            mInstrumentation = getIntent().getBooleanExtra("csh.cryptonite.instrumentation", false);
        } else {
            mInstrumentation = false;
        }
        
        if (mApp.needsEncFSBinary()) {
            final ProgressDialog pd = ProgressDialog.show(this,
                    this.getString(R.string.wait_msg),
                    this.getString(R.string.copying_bins), true);
            new Thread(new Runnable(){
                public void run(){
                    mApp.cpBin("encfs");
                    mApp.cpBin("truecrypt");
                    runOnUiThread(new Runnable(){
                        public void run() {
                            if (pd.isShowing())
                                pd.dismiss();
                            mApp.setEncFSBinaryVersion();
                        }
                    });
                }
            }).start();
        }
        
        if (!mInstrumentation) {
            if (!prefs.getBoolean("cb_norris", false) && !mApp.getDisclaimerShown()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
                builder.setIcon(R.drawable.ic_launcher_cryptonite)
                    .setTitle(R.string.disclaimer)
                    .setMessage(R.string.no_warranty)
                    .setPositiveButton(R.string.understand,
                                       new DialogInterface.OnClickListener() {
                                           public void onClick(DialogInterface dialog,
                                                               int which) {
                                               mApp.setDisclaimerShown(true);
                                           }
                                       });
                AlertDialog dialog = builder.create();
                dialog.show();
            }
        }

        mTabHost = (TabHost)findViewById(android.R.id.tabhost);
        mTabHost.setup();

        mViewPager = (ViewPager)findViewById(R.id.pager);
        mTabsAdapter = new TabsAdapter(this, mTabHost, mViewPager);
        
        mTabsAdapter.addTab(mTabHost.newTabSpec(DBTAB_TAG)
                .setIndicator(getString(R.string.dropbox_tabtitle)),
                DropboxFragment.class, null);
        mTabsAdapter.addTab(mTabHost.newTabSpec(LOCALTAB_TAG)
                .setIndicator(getString(R.string.local_tabtitle)),
                LocalFragment.class, null);
        mTabsAdapter.addTab(mTabHost.newTabSpec(EXPERTTAB_TAG)
                .setIndicator(getString(R.string.expert_tabtitle)),
                ExpertFragment.class, null);
        
        if (savedInstanceState != null) {
            mTabHost.setCurrentTabByTag(savedInstanceState.getString("tab"));
        }
        
    }

    public static boolean isValidMntDir(Context context, File newMntDir) {
        return isValidMntDir(context, newMntDir, true);
    }
    
    public static boolean isValidMntDir(Context context, File newMntDir, boolean showToast) {
        if (!newMntDir.exists()) {
            if (showToast) {
                Toast.makeText(context, R.string.txt_mntpoint_nexists, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        if (!newMntDir.isDirectory()) {
            if (showToast) {
                Toast.makeText(context, R.string.txt_mntpoint_nisdir, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        if (!newMntDir.canWrite()) {
            if (showToast) {
                Toast.makeText(context, R.string.txt_mntpoint_ncanwrite, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        if (newMntDir.list().length != 0) {
            if (showToast) {
                Toast.makeText(context, R.string.txt_mntpoint_nempty, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }

    public static String defaultMntDir() {
        return Environment.getExternalStorageDirectory().getPath() + MNTPNT;
    }

    /** Called upon exit from other activities */
    public synchronized void onActivityResult(final int requestCode,
            int resultCode, final Intent data) 
    {

        switch (requestCode) {
        case SelectionMode.MODE_OPEN:
        case SelectionMode.MODE_OPEN_DB:
        case SelectionMode.MODE_OPEN_UPLOAD_SOURCE:
        case SelectionMode.MODE_OPEN_CREATE:
            /* file dialog */
            if (resultCode == Activity.RESULT_OK && data != null) {
                currentReturnPath = data.getStringExtra(FileDialog.RESULT_EXPORT_PATHS);
                if (currentReturnPath != null ) {
                    switch (opMode) {
                    case MOUNT_MODE:
                    case SELECTLOCALENCFS_MODE:
                    case SELECTDBENCFS_MODE:
                        showDialog(MY_PASSWORD_DIALOG_ID);
                        break;
                    case LOCALEXPORT_MODE:
                    case DBEXPORT_MODE:
                        if (currentReturnPathList != null) {
                            final ProgressDialog pd = ProgressDialog.show(this,
                                    this.getString(R.string.wait_msg),
                                    this.getString(R.string.running_export), true);
                            new Thread(new Runnable(){
                                public void run(){
                                    String exportName = currentReturnPath + "/Cryptonite";
                                    Log.v(TAG, "Exporting to " + exportName);
                                    if (!new File(exportName).exists()) {
                                        new File(exportName).mkdirs();
                                    }
                                    if (!new File(exportName).exists()) {
                                        alert = true;
                                    } else {
                                        alert = !mApp.getStorage().exportEncFSFiles(currentReturnPathList, encfsBrowseRoot, 
                                                    currentReturnPath + "/Cryptonite");
                                    }
                                    runOnUiThread(new Runnable(){
                                        public void run() {
                                            if (pd.isShowing())
                                                pd.dismiss();
                                            if (alert) {
                                                showAlert(R.string.error, R.string.export_failed);
                                                alert = false;
                                            }
                                        }
                                    });
                                }
                            }).start();
                        }
                        break;
                    case SELECTDBUPLOAD_MODE:
                    case SELECTLOCALUPLOAD_MODE:
                        final String srcPath = data.getStringExtra(FileDialog.RESULT_SELECTED_FILE);
                        /* Does the file exist? */
                        uploadEncFSFile(srcPath);
                        break;
                    }
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                /* Log.d(TAG, "file not selected"); */
            }
            break;
        case SelectionMode.MODE_OPEN_MULTISELECT:
        case SelectionMode.MODE_OPEN_MULTISELECT_DB:
            /* file dialog */
            if (resultCode == Activity.RESULT_OK && data != null) {
                currentReturnPathList = data.getStringArrayExtra(FileDialog.RESULT_EXPORT_PATHS);
                if (currentReturnPathList != null && currentReturnPathList.length > 0) {

                    if (currentReturnPathList.length > MAX_JNI_SIZE) {
                        showAlert(R.string.error, R.string.jni_arg_too_large);
                        break;
                    }

                    /* Select destination directory for exported files */
                    currentDialogLabel = Cryptonite.this.getString(R.string.select_exp);
                    currentDialogButtonLabel = Cryptonite.this.getString(R.string.select_exp_short);
                    currentDialogMode = SelectionMode.MODE_OPEN_CREATE;
                    if (externalStorageIsWritable()) {
                        currentDialogStartPath = 
                                getDownloadDir().getPath();
                        File downloadDir = new File(currentDialogStartPath);
                        if (!downloadDir.exists()) {
                            downloadDir.mkdir();
                        }
                        if (!downloadDir.exists()) {
                            currentDialogStartPath = "/";
                        }
                    } else {
                        currentDialogStartPath = "/";
                    }
                    currentDialogRoot = "/";
                    currentDialogRootName = currentDialogRoot;
                    /* mApp.setCurrentDBEncFS(""); Leave this untouched for dbExport */
                    if (mApp.getStorage() != null) {
                        opMode = mApp.getStorage().exportMode;
                    } else {
                        return;
                    }
                    launchBuiltinFileBrowser();
                } else {
                    currentOpenPath = data.getStringExtra(FileDialog.RESULT_OPEN_PATH);
                    if (currentOpenPath != null && currentOpenPath.length() > 0) {
                        openEncFSFile(currentOpenPath, encfsBrowseRoot);
                    } else {
                        currentUploadPath = data.getStringExtra(FileDialog.RESULT_UPLOAD_PATH);
                        if (currentUploadPath != null && currentUploadPath.length() > 0) {
                            /* select file to upload */
                            currentDialogLabel = Cryptonite.this.getString(R.string.select_upload);
                            currentDialogButtonLabel = Cryptonite.this.getString(R.string.select_upload_short);
                            currentDialogMode = SelectionMode.MODE_OPEN_UPLOAD_SOURCE;
                            if (externalStorageIsWritable()) {
                                currentDialogStartPath = Environment
                                        .getExternalStorageDirectory()
                                        .getPath();
                            } else {
                                currentDialogStartPath = "/";
                            }
                            currentDialogRoot = "/";
                            currentDialogRootName = currentDialogRoot;
                            if (mApp.getStorage() != null) {
                                opMode = mApp.getStorage().uploadMode;
                            } else {
                                return;
                            }
                            launchBuiltinFileBrowser();
                        }
                    }
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {

            }
            break;
        case REQUEST_PREFS:
            SharedPreferences prefs = getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            Editor prefEdit = prefs.edit();
            
            mApp.setupReadDirs(prefs.getBoolean("cb_extcache", false));
            
            mntDir = prefs.getString("txt_mntpoint", defaultMntDir());

            /* If app folder settings have changed, we'll have to log out the user
             * from his Dropbox and restart the authentication from scratch during
             * the next login:
             */
            if (prefs.getBoolean("cb_appfolder", false) != mUseAppFolder) {
                prefEdit.putBoolean("dbDecided", true);
                prefEdit.commit();
                /* enforce re-authentication */
                mApp.setDBApi(null);
                if (mLoggedIn) {
                    Toast.makeText(Cryptonite.this,
                            R.string.dropbox_forced_logout,
                            Toast.LENGTH_LONG).show();
                    logOut();
                }
            }
            break;
        case REQUEST_CODE_PICK_FILE_OR_DIRECTORY:
            /* from external OI file browser */
            if (resultCode == RESULT_OK && data != null) {
                // obtain the filename
                Uri fileUri = data.getData();
                if (fileUri != null) {
                    currentReturnPath = fileUri.getPath();
                }
            }
            break;
        case CreateEncFS.CREATE_DB:
        case CreateEncFS.CREATE_LOCAL:
            break;
        default:
            Log.e(TAG, "Unknown request code");
        }
    }

    private void uploadEncFSFile(final String srcPath) {
        final String stripstr = mApp.getStorage().stripStr(currentUploadPath, encfsBrowseRoot, srcPath);
        
        /* Run in separate thread in case this involves a network operation */
        new Thread(new Runnable(){
            public void run(){
                final String nextFilePath = mApp.getStorage().encodedExists(stripstr);
                runOnUiThread(new Runnable(){
                    public void run() {
                        if (!nextFilePath.equals(stripstr)) {
                            AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
                            builder.setIcon(R.drawable.ic_launcher_cryptonite)
                            .setTitle(R.string.file_exists)
                            .setMessage(R.string.file_exists_options)
                            .setPositiveButton(R.string.overwrite,
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    uploadEncFSFileExec(stripstr, srcPath);
                                }
                            })
                            .setNeutralButton(R.string.rename, 
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    uploadEncFSFileExec(nextFilePath, srcPath);
                                }
                            })
                            .setNegativeButton(R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {

                                }
                            });  
                            AlertDialog dialog = builder.create();
                            dialog.show();
                        }else {
                            uploadEncFSFileExec(stripstr, srcPath);
                        }
                    }
                });
            }
        }).start();
    }

    private void uploadEncFSFileExec(final String targetPath, final String srcPath) {
        final ProgressDialog pd = ProgressDialog.show(this,
                this.getString(R.string.wait_msg),
                this.getString(R.string.encrypting), true);
        alertMsg = "";
        new Thread(new Runnable(){
            public void run(){
                if (!mApp.getStorage().uploadEncFSFile(targetPath, srcPath)) {
                    alertMsg = getString(R.string.upload_failure);
                }        
                runOnUiThread(new Runnable(){
                    public void run() {
                        if (pd.isShowing())
                            pd.dismiss();
                        if (!alertMsg.equals("")) {
                            showAlert(R.string.error, alertMsg);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasJni) {
            return;
        }
        if (mApp.getDBApi() != null && 
                mApp.getDBApi().getSession() != null) {
            AndroidAuthSession session = mApp.getDBApi().getSession();
    
            // The next part must be inserted in the onResume() method of the
            // activity from which session.startAuthentication() was called, so
            // that Dropbox authentication completes properly.
            // Make sure we're returning from an authentication attempt at all.
            if (session.authenticationSuccessful()) {
                triedLogin = false;
                try {
                    // Mandatory call to complete the auth
                    session.finishAuthentication();
    
                    // Store it locally in our app for later use
                    TokenPair tokens = session.getAccessTokenPair();
                    storeKeys(tokens.key, tokens.secret);
                    
                    mApp.clearDBHashMap();
                    
                    setLoggedIn(true);
                } catch (IllegalStateException e) {
                    Toast.makeText(Cryptonite.this, 
                            getString(R.string.dropbox_auth_fail) + ": " + e.getLocalizedMessage(), 
                            Toast.LENGTH_LONG).show();
                }
            } else {
                if (triedLogin) {
                    triedLogin = false;
                    logOut();
                }
            }
        } else {
            setLoggedIn(false);
        }
    }

    public void showAlert(int alert_id, int msg_id) {
        showAlert(getString(alert_id), getString(msg_id));
    }
    
    private void showAlert(int alert_id, String msg) {
        showAlert(getString(alert_id), msg);
    }
    
    private void showAlert(String alert, String msg) {
        showAlert(alert, msg, "OK");
    }
    
    private void showAlert(String alert, String msg, String btnLabel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
        builder.setIcon(R.drawable.ic_launcher_cryptonite)
            .setTitle(alert)
            .setMessage(msg)
            .setPositiveButton(btnLabel,
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface dialog,
                                                       int which) {
                                       
                                   }
                               });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /** Deletes all files and subdirectories under dir.
     * Returns true if all deletions were successful.
     * If a deletion fails, the method stops attempting to delete and returns false.
     */
    public static boolean deleteDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i=0; i<children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

        // The directory is now empty so delete it
        return dir.delete();
    }
    
    /** This will run the shipped encfs binary and spawn a daemon on rooted devices
     */
    private void mountEncFS(final String srcDir) {
        textOut = encfsVersion + "\n" + opensslVersion;

        if (jniIsValidEncFS(srcDir) != jniSuccess()) {
            showAlert(R.string.error, R.string.invalid_encfs);
            Log.v(TAG, "Invalid EncFS");
            return;
        }
        
        if (!isValidMntDir(this, new File(mntDir), true)) {
            showAlert(R.string.error, R.string.mount_point_invalid);
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this,
                this.getString(R.string.wait_msg),
                this.getString(R.string.running_encfs), true);
        Log.v(TAG, "Running encfs with " + srcDir + " " + mntDir);
        alertMsg = "";
        new Thread(new Runnable(){
                public void run(){
                    String encfsoutput = "";
                    String[] cmdlist = {mApp.getEncFSBinPath(), "--public", "--stdinpass",
                            "\"" + srcDir + "\"", "\"" + mntDir + "\""};
                    try {
                        encfsoutput = ShellUtils.runBinary(cmdlist, mApp.getBinDirPath(), currentPassword, true);
                    } catch (IOException e) {
                        alertMsg = getString(R.string.mount_fail) + ": " + e.getMessage();
                    } catch (InterruptedException e) {
                        alertMsg = getString(R.string.mount_fail) + ": " + e.getMessage();
                    }
                    final String fEncfsOutput = encfsoutput;
                    runOnUiThread(new Runnable(){
                            public void run() {
                                if (pd.isShowing())
                                    pd.dismiss();
                                if (fEncfsOutput != null && fEncfsOutput.length() > 0) {
                                    textOut = encfsVersion + "\n" + fEncfsOutput;
                                }
                                if (!alertMsg.equals("")) {
                                    showAlert(R.string.error, alertMsg);
                                }
                                nullPassword();
                            }
                        });
                }
            }).start();
            
    }
    
    /** Initialize an EncFS volume. This will check
     * whether the EncFS volume is valid an initialize the EncFS
     * root information
     * 
     * @param srcDir Path to EncFS volume
     * @param pwd password
     */
   private void initEncFS(final String srcDir) {
       alertMsg = "";
       
       final ProgressDialog pd = ProgressDialog.show(this, 
               this.getString(R.string.wait_msg), 
               this.getString(R.string.running_encfs), true);
       new Thread(new Runnable(){
               public void run(){
                   if (!mApp.getStorage().initEncFS(srcDir, currentDialogRoot)) {
                       alertMsg = getString(R.string.invalid_encfs);
                       mApp.resetStorage();
                   } else {
                       mApp.setCurrentBrowsePath(currentReturnPath);
                       mApp.setCurrentBrowseStartPath(currentDialogStartPath);
                       mApp.setEncFSPath(currentReturnPath.substring(currentDialogStartPath.length()));
                       Log.i(TAG, "Dialog root is " + currentReturnPath);
                       if (jniInit(srcDir, currentPassword) != jniSuccess()) {
                           Log.v(TAG, getString(R.string.browse_failed));
                           alertMsg = getString(R.string.browse_failed);
                           mApp.resetStorage();
                       } else {
                           Log.v(TAG, "Decoding succeeded");
                       }
                   }
                   runOnUiThread(new Runnable(){
                           public void run() {
                               if (pd.isShowing())
                                   pd.dismiss();
                               nullPassword();
                               updateDecryptButtons();
                               if (alertMsg.length()!=0) {
                                   showAlert(R.string.error, alertMsg);
                               }
                           }
                       });
               }
           }).start();
   }

   public void updateDecryptButtons() {
       if (dbFragment != null) {
           dbFragment.updateDecryptButtons();
       }
       if (localFragment != null) {
           localFragment.updateDecryptButtons();
       }
   }

   /** Browse an EncFS volume using a virtual file system.
    * File names are queried on demand when a directory is opened.
    * @param browsePath EncFS path 
    * @param browseStartPath Root path
    */
   public void browseEncFS(final String browsePath, final String browseStartPath) {
       final VirtualFile browseDirF = new VirtualFile(VirtualFile.VIRTUAL_TAG + "/" + mApp.getStorage().browsePnt);
       browseDirF.mkdirs();

       final ProgressDialog pd = ProgressDialog.show(this,
               this.getString(R.string.wait_msg),
               this.getString(R.string.running_encfs), true);
       new Thread(new Runnable(){
               public void run(){
                   Log.i(TAG, "Dialog root is " + browsePath);
                   currentDialogStartPath = browseDirF.getPath();
                   currentDialogLabel = getString(R.string.select_file_export);
                   currentDialogButtonLabel = getString(R.string.export);
                   currentDialogRoot = currentDialogStartPath;
                   encfsBrowseRoot = currentDialogRoot;
                   currentDialogRootName = getString(R.string.encfs_root);
                   currentDialogMode = mApp.getStorage().fdSelectionMode;
                   runOnUiThread(new Runnable(){
                           public void run() {
                               if (pd.isShowing())
                                   pd.dismiss();
                               opMode = mApp.getStorage().selectExportMode;
                               launchBuiltinFileBrowser();
                           }
                       });
               }
           }).start();
    }
    
    public File getPrivateDir(String label) {
        return getPrivateDir(label, Context.MODE_PRIVATE);
    }
    
    public File getPrivateDir(String label, int mode) {
        /* Tear down and recreate the browse directory to make
         * sure we have appropriate permissions */
        File browseDirF = getBaseContext().getDir(label, mode);
        if (browseDirF.exists()) {
            if (!deleteDir(browseDirF)) {
                showAlert(R.string.error, R.string.target_dir_cleanup_failure);
                return null;
            }
        }
        browseDirF = getBaseContext().getDir(label, mode);
        return browseDirF;
    }
    
    private void nullPassword() {
        char[] fill = new char[currentPassword.length()];
        Arrays.fill(fill, '\0');
        currentPassword = new String(fill);
    }
    
    @Override protected Dialog onCreateDialog(int id) {
        switch (id) {
         case MY_PASSWORD_DIALOG_ID:
             LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);

             final View layout = inflater.inflate(R.layout.password_dialog, (ViewGroup) findViewById(R.id.root));
             final EditText password = (EditText) layout.findViewById(R.id.EditText_Pwd);

             AlertDialog.Builder builder = new AlertDialog.Builder(this);
             builder.setTitle(R.string.title_password);
             builder.setView(layout);
             builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int whichButton) {
                         removeDialog(MY_PASSWORD_DIALOG_ID);
                     }
                 });
             builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int which) {
                         currentPassword = password.getText().toString();
                         removeDialog(MY_PASSWORD_DIALOG_ID);
                         if (currentPassword.length() > 0) {
                             switch (opMode) {
                              case MOUNT_MODE:
                                  mountEncFS(currentReturnPath);
                                  opMode = prevMode;
                                  break;
                              case SELECTLOCALENCFS_MODE:
                              case SELECTDBENCFS_MODE:
                                  initEncFS(currentReturnPath);
                                  break;
                             }
                         } else {
                             showAlert(R.string.error, R.string.empty_password);
                         }
                     }
                 });
             return builder.create();
         case DIALOG_OI_UNAVAILABLE:
             return new AlertDialog.Builder(Cryptonite.this)
                 .setIcon(R.drawable.ic_launcher_folder)
                 .setTitle(R.string.app_oi_missing)
                 .setPositiveButton(R.string.app_oi_get, new DialogInterface.OnClickListener() {
                         public void onClick(DialogInterface dialog, int whichButton) {
                             Intent intent = new Intent(Intent.ACTION_VIEW,
                                                        Uri.parse("market://details?id=org.openintents.filemanager"));
                             try {
                                 startActivity(intent);
                             } catch (ActivityNotFoundException e) {
                                 showDialog(DIALOG_MARKETNOTFOUND);
                             }
                         }
                     })
                 .setNegativeButton(R.string.app_oi_builtin, new DialogInterface.OnClickListener() {
                         public void onClick(DialogInterface dialog, int whichButton) {
                             launchBuiltinFileBrowser();
                         }
                     })
                 .create();
         case DIALOG_TERM_UNAVAILABLE:
             return new AlertDialog.Builder(Cryptonite.this)
                 .setIcon(R.drawable.app_terminal)
                 .setTitle(R.string.app_terminal_missing)
                 .setPositiveButton(R.string.app_terminal_get, new DialogInterface.OnClickListener() {
                         public void onClick(DialogInterface dialog, int whichButton) {
                             Intent intent = new Intent(Intent.ACTION_VIEW,
                                                        Uri.parse("market://details?id=jackpal.androidterm"));
                             try {
                                 startActivity(intent);
                             } catch (ActivityNotFoundException e) {
                                 showDialog(DIALOG_MARKETNOTFOUND);
                             }
                         }
                     })
                 .create();             
         case DIALOG_MARKETNOTFOUND:
             return new AlertDialog.Builder(Cryptonite.this)
                 .setIcon(android.R.drawable.ic_dialog_alert)
                 .setTitle(R.string.market_missing)
                 .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                         public void onClick(DialogInterface dialog, int whichButton) {
                             /* Return silently */
                         }
                     })
                 .create();
        }

        return null;
    }

    /** Creates an options menu */
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    /** Opens the options menu */
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
         case R.id.preferences:
             SharedPreferences prefs = getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
             /* Store current app folder choice */
             mUseAppFolder = prefs.getBoolean("cb_appfolder", false);
             Intent settingsActivity = new Intent(getBaseContext(),
                                                  Preferences.class);
             startActivityForResult(settingsActivity, REQUEST_PREFS);
             return true;
         case R.id.about:
             AlertDialog builder;
             try {
                 builder = AboutDialogBuilder.create(this);
                 builder.show();
                 return true;
             } catch (PackageManager.NameNotFoundException e) {
                 return false;
             }
         default:
             return super.onOptionsItemSelected(item);
        }
    }

    private static class AboutDialogBuilder {
        public static AlertDialog create(Context context) throws PackageManager.NameNotFoundException {
            PackageInfo pInfo = context.getPackageManager().
                getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Drawable pIcon = context.getPackageManager().
                getApplicationIcon(context.getPackageName());
            String aboutTitle = String.format("%s %s", context.getString(R.string.app_name), pInfo.versionName);
            String aboutText = context.getString(R.string.about);

            final TextView message = new TextView(context);
            final SpannableString s = new SpannableString(aboutText);

            message.setPadding(5, 5, 5, 5);
            message.setMovementMethod(new ScrollingMovementMethod());
            message.setText(s);
            Linkify.addLinks(message, Linkify.ALL);

            return new AlertDialog.Builder(context).setTitle(aboutTitle).
                setIcon(pIcon).
                setCancelable(true).
                setPositiveButton(context.getString(android.R.string.ok), null).
                setView(message).create();
        }
    }

    public static boolean externalStorageIsWritable() {
        /* Check sd card state */
        String state = Environment.getExternalStorageState();

        boolean extStorAvailable = false;
        boolean extStorWriteable = false;

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            // We can read and write the media
            extStorAvailable = extStorWriteable = true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // We can only read the media
            extStorAvailable = true;
            extStorWriteable = false;
        } else {
            // Something else is wrong. It may be one of many other states, but all we need
            //  to know is we can neither read nor write
            extStorAvailable = extStorWriteable = false;
        }

        return extStorAvailable && extStorWriteable;
    }

    public void launchFileBrowser(int mode) {
        SharedPreferences prefs = getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        boolean useBuiltin = prefs.getBoolean("cb_builtin", false);
        if (!useBuiltin) {
            // Note the different intent: PICK_DIRECTORY
            String oiIntent = "org.openintents.action.PICK_FILE";
            if (mode == DIRPICK_MODE) {
                oiIntent = "org.openintents.action.PICK_DIRECTORY";
            }
            Intent intent = new Intent(oiIntent);

            // Construct URI from file name.
            File file = new File(currentDialogStartPath);
            intent.setData(Uri.fromFile(file));

            intent.putExtra("org.openintents.extra.TITLE", currentDialogLabel);
            intent.putExtra("org.openintents.extra.BUTTON_TEXT", currentDialogButtonLabel);

            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FILE_OR_DIRECTORY);
            } catch (ActivityNotFoundException e) {
                showDialog(DIALOG_OI_UNAVAILABLE);
            }
        } else {
            launchBuiltinFileBrowser();
        }

    }
    
    public void launchBuiltinFileBrowser() {
        Intent intent = new Intent(getBaseContext(), FileDialog.class);
        intent.putExtra(FileDialog.CURRENT_ROOT, currentDialogRoot);
        intent.putExtra(FileDialog.CURRENT_ROOT_NAME, currentDialogRootName);
        intent.putExtra(FileDialog.BUTTON_LABEL, currentDialogButtonLabel);
        intent.putExtra(FileDialog.START_PATH, currentDialogStartPath);
        intent.putExtra(FileDialog.LABEL, currentDialogLabel);
        intent.putExtra(FileDialog.SELECTION_MODE, currentDialogMode);
        startActivityForResult(intent, currentDialogMode);
    }
    
    public void createEncFS(final boolean isDB) {
        SharedPreferences prefs = getBaseContext().getSharedPreferences(Cryptonite.ACCOUNT_PREFS_NAME, 0);
        if (!prefs.getBoolean("cb_norris", false)) {

            AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
            builder.setIcon(R.drawable.ic_launcher_cryptonite)
            .setTitle(R.string.warning)
            .setMessage(R.string.create_warning)
            .setPositiveButton(R.string.create_short,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                        int which) {
                    Intent intent = new Intent(getBaseContext(), CreateEncFS.class);
                    int createMode = CreateEncFS.CREATE_LOCAL;
                    if (isDB) {
                        createMode = CreateEncFS.CREATE_DB;
                    }
                    intent.putExtra(CreateEncFS.START_MODE, createMode);
                    startActivityForResult(intent, createMode);
                }
            })
            .setNegativeButton(R.string.cancel,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                        int which) {
                }
            });  
            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            Intent intent = new Intent(getBaseContext(), CreateEncFS.class);
            int createMode = CreateEncFS.CREATE_LOCAL;
            if (isDB) {
                createMode = CreateEncFS.CREATE_DB;
            }
            intent.putExtra(CreateEncFS.START_MODE, createMode);
            startActivityForResult(intent, createMode);
        }
        
        
    }
    
    public void logOut() {
        // Remove credentials from the session
        if (mApp.getDBApi() != null) {
            mApp.getDBApi().getSession().unlink();
            // Clear our stored keys
            clearKeys();
        
            mApp.clearDBHashMap();
        }
        
        // Change UI state to display logged out version
        setLoggedIn(false);
    }

    /**
     * Convenience function to change UI state based on being logged in
     */
    private void setLoggedIn(boolean loggedIn) {
        mLoggedIn = loggedIn;
        if (!loggedIn) {
            if (mApp.isDropbox()) {
                jniResetVolume();
                mApp.resetStorage();
            }
        }
    }

    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_DB_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }
    
    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     *
     * @return Array of [access_key, access_secret], or null if none stored
     */
    private String[] getKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_DB_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key != null && secret != null) {
            String[] ret = new String[2];
            ret[0] = key;
            ret[1] = secret;
            return ret;
        } else {
            return null;
        }
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void storeKeys(String key, String secret) {
        // Save the access key for later
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_DB_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.putString(ACCESS_KEY_NAME, key);
        edit.putString(ACCESS_SECRET_NAME, secret);
        edit.commit();
    }

    public void buildSession() {
        // Has the user already decided whether to use an app folder?
        SharedPreferences prefs = 
                getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        if (!prefs.getBoolean("dbDecided", false)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
            builder.setIcon(R.drawable.ic_launcher_cryptonite)
            .setTitle(R.string.dropbox_access_title)
            .setMessage(R.string.dropbox_access_msg)
            .setPositiveButton(R.string.dropbox_full,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                        int which) {
                    setSession(false);
                }   
            })  
            .setNegativeButton(R.string.dropbox_folder,
                    new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog,
                        int which) {
                    setSession(true);
                }
            });
            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            setSession(prefs.getBoolean("cb_appfolder", false));
        }
    }
    
    private void setSession(boolean useAppFolder) {
        SharedPreferences prefs = 
                getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.putBoolean("dbDecided", true);
        if (!edit.commit()) {
            Log.e(Cryptonite.TAG, "Couldn't write preferences");
        }
        edit.putBoolean("cb_appfolder", useAppFolder);
        if (!edit.commit()) {
            Log.e(Cryptonite.TAG, "Couldn't write preferences");
        }
        
        AndroidAuthSession session;
        AppKeyPair appKeyPair;
        AccessType accessType;
        
        String[] stored = getKeys();
        
        if (useAppFolder) {
            appKeyPair = new AppKeyPair(jniFolderKey(), jniFolderPw());
            accessType = AccessType.APP_FOLDER;
        } else {
            appKeyPair = new AppKeyPair(jniFullKey(), jniFullPw());
            accessType = AccessType.DROPBOX;
        }
        if (stored != null && stored.length >= 2) {
            AccessTokenPair accessToken = new AccessTokenPair(stored[0], stored[1]);
            session = new AndroidAuthSession(appKeyPair, accessType, accessToken);
        } else {
            session = new AndroidAuthSession(appKeyPair, accessType);
        }
        mApp.setDBApi(new DropboxAPI<AndroidAuthSession>(session));

        triedLogin = true;
        // Start the remote authentication
        mApp.getDBApi()
            .getSession().startAuthentication(Cryptonite.this);
    }

    public static File getExternalCacheDir(final Context context) {
        /* Api >= 8 */
        return context.getExternalCacheDir();

        /* Api < 8
        // e.g. "<sdcard>/Android/data/<package_name>/cache/"
        final File extCacheDir = new File(Environment.getExternalStorageDirectory(),
                                          "/Android/data/" + context.getApplicationInfo().packageName + "/cache/");
        extCacheDir.mkdirs();
        return extCacheDir;*/
    }
    
    private static File getDownloadDir() {
        /* Api >= 8 */
        return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        /* Api < 8 
        File downloadDir = new File(Environment.getExternalStorageDirectory(), "/download");
        if (!downloadDir.exists()) {
            File downloadDirD = new File(Environment.getExternalStorageDirectory(), "/Download");
            if (!downloadDirD.exists()) {
                // Make "download" dir
                downloadDir.mkdirs();
                return downloadDir;
            } else {
                return downloadDirD;
            }
        } else {
            return downloadDir;
        }*/
    }
    
    private boolean openEncFSFile(final String encFSFilePath, String fileRoot) {

        SharedPreferences prefs = getBaseContext().getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        mApp.setupReadDirs(prefs.getBoolean("cb_extcache", false));

        /* normalise path names */
        String bRoot = new File(fileRoot).getPath();
        String bPath = new File(encFSFilePath).getPath();
        String stripstrtmp = bPath.substring(bRoot.length());
        if (!stripstrtmp.startsWith("/")) {
            stripstrtmp = "/" + stripstrtmp;
        }

        final String stripstr = stripstrtmp;
        
        /* Convert current path to encoded file name */
        final String encodedPath = jniEncode(stripstr);

        /* Set up temp dir for decrypted file */
        String destPath = mApp.getOpenDir().getPath() + (new File(bPath)).getParent().substring(bRoot.length());

        (new File(destPath)).mkdirs();
        alertMsg = "";
        final ProgressDialog pd = ProgressDialog.show(this,
                this.getString(R.string.wait_msg),
                this.getString(R.string.decrypting), true);
            new Thread(new Runnable(){
                public void run(){
                    if (!mApp.getStorage().decryptEncFSFile(encodedPath, mApp.getOpenDir().getPath())) {
                        alertMsg = getString(R.string.decrypt_failure);
                        Log.e(TAG, "Error while attempting to copy " + encodedPath);
                    }
                    runOnUiThread(new Runnable(){
                        public void run() {
                            if (pd.isShowing())
                                pd.dismiss();
                            if (!alertMsg.equals("")) {
                                showAlert(R.string.error, alertMsg);
                                return;
                            }
                            /* Copy the resulting file to a readable folder */
                            String openFilePath = mApp.getOpenDir().getPath() + stripstr;
                            String readableName = (new File(encFSFilePath)).getName();
                            String readablePath = mApp.getReadDir().getPath() + "/" + readableName;
                            File readableFile = new File(readablePath);
                            
                            /* Make sure the readable Path exists */
                            readableFile.getParentFile().mkdirs();
                            
                            try {
                                FileOutputStream fos = new FileOutputStream(readableFile);

                                FileInputStream fis = new FileInputStream(new File(openFilePath));

                                byte[] buffer = new byte[fis.available()]; 

                                fis.read(buffer);

                                fos.write(buffer);

                                fis.close();
                                fos.close();

                                /* Make world readable */
                                try {
                                    ShellUtils.chmod(readablePath, "644");
                                } catch (InterruptedException e) {
                                    Log.e(Cryptonite.TAG, e.toString());
                                }
                                
                                /* Delete tmp directory */
                                deleteDir(mApp.getOpenDir());
                            } catch (IOException e) {
                                Toast.makeText(Cryptonite.this, "Error while attempting to open " + readableName
                                        + ": " + e.toString(), Toast.LENGTH_LONG).show();
                                return;
                            }
                            
                            fileOpen(readablePath);
                            
                        }
                    });
                }
            }).start();
        return true;        
    }
    
    private boolean fileOpen(String filePath) {
        /* Guess MIME type */
        Uri data = Uri.fromFile(new File(filePath));

        MimeTypeMap myMime = MimeTypeMap.getSingleton();
        String extension = Storage.fileExt(filePath);
        String contentType;
        if (extension.length() == 0) {
            contentType = null;
        } else {
            contentType = myMime.getMimeTypeFromExtension(extension.substring(1));
        }
        
        /* Attempt to guess file type from content; seemingly very unreliable */
        if (contentType == null) {
            try {
                FileInputStream fis = new FileInputStream(filePath);
                contentType = URLConnection.guessContentTypeFromStream(fis);
            } catch (IOException e) {
                Log.e(TAG, "Error while attempting to guess MIME type of " + filePath
                        + ": " + e.toString());
                contentType = null;
            }
        }

        if (contentType == null) {
            Log.e(TAG, "Couldn't find content type; resorting to text/plain");
            contentType = "text/plain";
        }
        
        Intent intent = new Intent();
        
        intent.setAction(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(data, contentType);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showAlert(getString(R.string.activity_not_found_title), 
                    getString(R.string.activity_not_found_msg));
            Log.e(TAG, "Couldn't find activity: " + e.toString());
            return false;
        }
        
        return true;
    }

    public static int hasExtterm(Context context) {
        ComponentName termComp = new ComponentName("jackpal.androidterm", "jackpal.androidterm.Term");
        try {
            PackageInfo pinfo = context.getPackageManager().getPackageInfo(termComp.getPackageName(), 0);
            int patchCode = pinfo.versionCode;

            if (patchCode < 32) {
                return TERM_OUTDATED;
            } else {
                return TERM_AVAILABLE;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return TERM_UNAVAILABLE;
        }
    }

    public void launchTerm() {
        launchTerm(false);
    }
    
    public void launchTerm(boolean root) {
        /* Is a reminal emulator running? */
        
            /* If Terminal Emulator is not installed or outdated,
             * offer to download
             */
            if (hasExtterm(getBaseContext())!=TERM_AVAILABLE) {
                showDialog(DIALOG_TERM_UNAVAILABLE);
            } else {
                ComponentName termComp = new ComponentName("jackpal.androidterm", "jackpal.androidterm.Term");
                try {
                    PackageInfo pinfo = getBaseContext().getPackageManager().getPackageInfo(termComp.getPackageName(), 0);
                    String patchVersion = pinfo.versionName;
                    Log.v(TAG, "Terminal Emulator version: " + patchVersion);
                    int patchCode = pinfo.versionCode;

                    if (patchCode < 32) {
                        showAlert(R.string.error, R.string.app_terminal_outdated);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.setComponent(termComp);
                        runTerm(intent, extTermRunning(), root);
                    }

                } catch (PackageManager.NameNotFoundException e) {
                    Toast.makeText(Cryptonite.this, R.string.app_terminal_missing, Toast.LENGTH_LONG).show();
                }
            }
    }

    private void runTerm(Intent intent, boolean running, boolean root) {
        /* If the terminal is running, abort */
        if (running) {
            new AlertDialog.Builder(Cryptonite.this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.warning)
                .setMessage(R.string.term_service_running)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        ;
                    }
                })
                .create().show();
            return;
        }
        
        String initCmd = "export PATH=" + mApp.getBinDirPath() + ":${PATH};";
        if (root) {
            initCmd += " su;";
        }
        intent.putExtra("jackpal.androidterm.iInitialCommand", initCmd);
        startActivity(intent);
    }

    private boolean extTermRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
             if ("jackpal.androidterm.TermService".equals(service.service.getClassName())) {
                 return true;
             }
        }
        return false;
    }
    
    public void setDBFragment(DropboxFragment fragment) {
        dbFragment = fragment;
    }
    
    public void setLocalFragment(LocalFragment fragment) {
        localFragment = fragment;
    }
    
    /* Native methods are implemented by the
     * 'cryptonite' native library, which is packaged
     * with this application.
     */
    public native int     jniFailure();
    public static native int jniSuccess();
    public static native int     jniIsValidEncFS(String srcDir);
    public static native int jniVolumeLoaded();
    public static native int jniResetVolume();
    public native int     jniBrowse(String srcDir, String destDir, String password);
    public native int     jniInit(String srcDir, String password);
    public static native int jniCreate(String srcDir, String password, int config);
    public native int     jniExport(String[] exportPaths, String exportRoot, String destDir);
    public static native int jniDecrypt(String encodedName, String destDir, boolean forceReadable);
    public static native int jniEncrypt(String decodedPath, String srcPath, boolean forceReadable);
    public static native String jniDecode(String name);
    public static native String jniEncode(String name);
    public native String  jniEncFSVersion();
    public native String  jniOpenSSLVersion();
    public native String  jniFullKey();
    public native String  jniFullPw();
    public native String  jniFolderKey();
    public native String  jniFolderPw();

    private void jniFail() {
        AlertDialog.Builder builder = new AlertDialog.Builder(Cryptonite.this);
        builder.setIcon(R.drawable.ic_launcher_cryptonite)
        .setTitle(R.string.error)
        .setMessage(R.string.jni_fail)
        .setPositiveButton(R.string.send_email,
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog,
                    int which) {
                removeDialog(DIALOG_JNI_FAIL);
                Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
                emailIntent.setType("plain/text");
                emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL,
                        new String[]{"christoph.schmidthieber@googlemail.com"});
                emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                        Cryptonite.this.getString(R.string.crash_report));
                emailIntent.putExtra(android.content.Intent.EXTRA_TEXT,
                        getString(R.string.crash_report_content));
                Cryptonite.this.startActivity(Intent.createChooser(emailIntent, "Send mail..."));
                finish();
            }   
        })  
        .setNeutralButton(R.string.send_report,
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog,
                    int which) {
                removeDialog(DIALOG_JNI_FAIL);
                Intent reportIntent = new Intent(android.content.Intent.ACTION_VIEW);
                String url = "https://code.google.com/p/cryptonite/issues/detail?id=9";
                reportIntent.setData(Uri.parse(url));
                Cryptonite.this.startActivity(reportIntent);
                finish();
            }
        })
        .setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog,
                    int which) {
                removeDialog(DIALOG_JNI_FAIL);
                finish();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /* this is used to load the 'cryptonite' library on application
     * startup. The library has already been unpacked into
     * /data/data/csh.cryptonite/lib/libcryptonite.so at
     * installation time by the package manager.
     */
    static {
        try {
            System.loadLibrary("cryptonite");
            Cryptonite.hasJni = true;
        } catch (java.lang.UnsatisfiedLinkError e) {
            Cryptonite.hasJni = false;
        }
    }

}

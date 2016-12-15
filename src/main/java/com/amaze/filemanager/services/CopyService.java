/*
 * Copyright (C) 2014 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.services;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.amaze.filemanager.ProgressListener;
import com.amaze.filemanager.R;
import com.amaze.filemanager.RegisterCallback;
import com.amaze.filemanager.activities.MainActivity;
import com.amaze.filemanager.filesystem.BaseFile;
import com.amaze.filemanager.utils.DataPackage;
import com.amaze.filemanager.utils.Futils;
import com.amaze.filemanager.filesystem.HFile;
import com.amaze.filemanager.filesystem.RootHelper;
import com.amaze.filemanager.utils.GenericCopyThread;
import com.stericson.RootTools.RootTools;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class CopyService extends Service {
    HashMap<Integer, Boolean> hash = new HashMap<Integer, Boolean>();
    public HashMap<Integer, DataPackage> hash1 = new HashMap<Integer, DataPackage>();
    boolean rootmode;
    NotificationManager mNotifyManager;
    NotificationCompat.Builder mBuilder;
    Context c;
    Futils utils ;
    @Override
    public void onCreate() {
        c = getApplicationContext();
        utils=new Futils();
        SharedPreferences Sp=PreferenceManager.getDefaultSharedPreferences(this);
        rootmode=Sp.getBoolean("rootmode",false);
        registerReceiver(receiver3, new IntentFilter("copycancel"));
    }


    boolean foreground=true;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle b = new Bundle();
        ArrayList<BaseFile> files = intent.getParcelableArrayListExtra("FILE_PATHS");
        String FILE2 = intent.getStringExtra("COPY_DIRECTORY");
        int mode=intent.getIntExtra("MODE",0);
        mNotifyManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        b.putInt("id", startId);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notificationIntent.putExtra("openprocesses",true);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        mBuilder = new NotificationCompat.Builder(c);
        mBuilder.setContentIntent(pendingIntent);
        mBuilder.setContentTitle(getResources().getString(R.string.copying));
        mBuilder.setSmallIcon(R.drawable.ic_content_copy_white_36dp);

        if(foreground){
            startForeground(Integer.parseInt("456"+startId),mBuilder.build());
            foreground=false;
        }
        b.putBoolean("move", intent.getBooleanExtra("move", false));
        b.putString("FILE2", FILE2);
        b.putInt("MODE",mode);
        b.putParcelableArrayList("files", files);
        hash.put(startId, true);
        DataPackage intent1 = new DataPackage();
        intent1.setName(files.get(0).getName());
        intent1.setTotal(0);
        intent1.setDone(0);
        intent1.setId(startId);
        intent1.setP1(0);
        intent1.setP2(0);
        intent1.setMove(intent.getBooleanExtra("move", false));
        intent1.setCompleted(false);
        hash1.put(startId,intent1);
        //going async
        new DoInBackground().execute(b);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }
    ProgressListener progressListener;


    public void onDestroy() {
        this.unregisterReceiver(receiver3);
    }

    public class DoInBackground extends AsyncTask<Bundle, Void, Integer> {
        ArrayList<BaseFile> files;
        boolean move;
        Copy copy;
        public DoInBackground() {
        }

        protected Integer doInBackground(Bundle... p1) {
            String FILE2 = p1[0].getString("FILE2");
            int id = p1[0].getInt("id");
            files = p1[0].getParcelableArrayList("files");
            move=p1[0].getBoolean("move");
            copy=new Copy();
            copy.execute(id, files, FILE2,move,p1[0].getInt("MODE"));

            // TODO: Implement this method
            return id;
        }

        @Override
        public void onPostExecute(Integer b) {
            publishResults("", 0, 0, b, 0, 0, true, move);
            generateNotification(copy.failedFOps,move);
            Intent intent = new Intent("loadlist");
            sendBroadcast(intent);
            hash.put(b,false);
            boolean stop=true;
            for(int a:hash.keySet()){
            if(hash.get(a))stop=false;
            }
            if(!stop)
            stopSelf(b);
            else stopSelf();

        }

        class Copy {
            long totalBytes = 0L;
            boolean calculatingTotalSize=false;
            ArrayList<HFile> failedFOps;
            ArrayList<BaseFile> toDelete;
            boolean copy_successful;
            public Copy() {
                copy_successful=true;
                failedFOps=new ArrayList<>();
                toDelete=new ArrayList<>();
            }

            /**
             * Calculate total amount of bytes to be copied/moved
             * Updates totalBytes of progressHandles if provided
             * ToDo: enable in root
             * @param files
             * @param progressHandler
             * @return
             */
            long getTotalBytes(final ArrayList<BaseFile> files, final ProgressHandler progressHandler) {
                calculatingTotalSize=true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long totalBytes = 0l;
                        try {
                            for (int i = 0; i < files.size(); i++) {
                                HFile f1 = (files.get(i));
                                if (f1.isDirectory()) {
                                    totalBytes = totalBytes + f1.folderSize();
                                } else {
                                    totalBytes = totalBytes + f1.length();
                                    //long lRootLength = RootTools.getSpace(fi.getpa);
                                }
                            }
                            if(progressHandler != null)
                                progressHandler.setTotalSize(totalBytes);
                        } catch (Exception e) {
                        }
                        Copy.this.totalBytes=totalBytes;
                    calculatingTotalSize=false;
                    }
                }).run();

                return totalBytes;
            }

            /**
             * copy/move procedures
             * If any or both, target or source, are not writable, it will use root tools to perform the action
             * @param id
             * @param files
             * @param FILE2
             * @param move
             * @param mode
             */
            public void execute(final int id, final ArrayList<BaseFile> files, final String FILE2, final boolean move,int mode)
            {
                boolean bSourceWritable = isSourceWritable(files);
                boolean bTargetWritable = (utils.checkFolder((FILE2), c) == 1);
                boolean bCopyOk  = true;

                if (bTargetWritable && bSourceWritable)                                             //Check if both, source and target are writable
                {
                    final ProgressHandler progressHandler=new ProgressHandler(-1);
                    BufferHandler bufferHandler=new BufferHandler(c);
                    GenericCopyThread copyThread = new GenericCopyThread(c);
                    progressHandler.setProgressListener(new ProgressHandler.ProgressListener() {
                        @Override
                        public void onProgressed(String fileName, float p1, float p2, float speed, float avg) {
                            publishResults(fileName, (int) p1, (int) p2, id, progressHandler.totalSize, progressHandler.writtenSize, false, move);
                            System.out.println(new File(fileName).getName() + " Progress " + p1 + " Secondary Progress " + p2 + " Speed " + speed + " Avg Speed " + avg);
                        }
                    });
                    getTotalBytes(files, progressHandler);
                    for (int i = 0; i < files.size(); i++) {
                        BaseFile f1 = (files.get(i));
                        Log.e("Copy","basefile\t"+f1.getPath());
                        try {
                            if (hash.get(id)){
                                HFile hFile=new HFile(mode,FILE2, files.get(i).getName(),f1.isDirectory());
                                copyFiles((f1),hFile, bufferHandler, copyThread, progressHandler, id);
                            }
                            else{
                                break;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e("Copy","Got exception checkout");

                            failedFOps.add(files.get(i));
                            for(int j=i+1;j<files.size();j++)failedFOps.add(files.get(j));
                            break;
                        }
                    }
                    int i=1;
                    while(bufferHandler.writing && copyThread.thread == null){
                        try {
                            if(i>5)i=5;
                            Thread.sleep(i*100);
                            i++;
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    // waiting for generic copy thread to finish before returning from this point
                    try {

                        if (copyThread.thread!=null) {
                            copyThread.thread.join();
                            Log.d(getClass().getSimpleName(), "Thread alive: " + copyThread.thread.isAlive());
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        // thread interrupted for some reason, probably already been handled
                    }
                }
                else if (rootmode)                                                                  //Cuando el destino es una carpeta que necesita root
                {
                    String totalBytes =  utils.readableFileSize(getTotalBytes(files, null));

                    int id1 = Integer.parseInt("456" + id);
                    if(move)
                        mBuilder.setContentTitle(getResources().getString(R.string.moving));
                    mBuilder.setContentText(totalBytes);
                    mNotifyManager.notify(id1, mBuilder.build());

                    for (int i = 0; i < files.size(); i++)
                        copyRoot(files.get(i), FILE2, !bTargetWritable);                            //Remount target only if its not writable
                }
                else
                {
                    for(BaseFile f:files)
                        failedFOps.add(f);
                    bCopyOk = false;
                }


                if(bCopyOk)
                {
                    //Check if copy was performed ok
                    for (int i = 0; i < files.size(); i++)
                    {
                        String path = files.get(i).getPath();
                        String name = files.get(i).getName();
                        if (!checkFiles(new HFile(files.get(i).getMode(), path), new HFile(HFile.ROOT_MODE, FILE2 + "/" + name)))
                        {
                            failedFOps.add(files.get(i));
                            bCopyOk = false;
                            break;
                        }
                    }


                    //Delete files from source if move is selected and copy was performed ok
                    if (move && bCopyOk)
                    {
                        ArrayList<BaseFile> toDelete = new ArrayList<>();
                        for (BaseFile a : files)
                        {
                            if (!failedFOps.contains(a))
                                toDelete.add(a);
                        }
                        new DeleteTask(getContentResolver(), c).execute((toDelete));
                    }
                }
            }


            /**
             * Copy from/to a folder with root permissions.
             * Copy is recursive and maintaining the permissions!!!
             * Caveat: Do not use
             *          CommandCapture cmdCapture = new CommandCapture(0, command);
             *          RootTools.getShell(true).add(cmdCapture);
             *          while (!cmdCapture.isFinished()){}
             *
             * Different approaches:
             *      -a: tries to preserve file structure. Does not copy symlinks when copying to external/internal (fat32)
             *      -rp: same as above but copies symlinks as a regular file
             *      -drp: same as above but preserving links. Does not work on directories          //Caveat: -r could fail in /dev/console. Use -R instead //test ls -la
             * @param bfSource
             * @param sDestination
             * @param bRw
             * @return
             */
            boolean copyRoot(BaseFile bfSource, String sDestination, boolean bRw)
            {
                String path = bfSource.getPath().replace(" ", "\\ ");                               //Escape sequence for blank spaces
                String name = bfSource.getName().replace(" ", "\\ ");

                String sTargetPath = sDestination.replace(" ", "\\ ") + "/";
                String sSource = path;
                String sTarget  = sTargetPath + name;

                Log.e("Root Copy", path);

                try
                {
                    if(bRw)
                        RootTools.remount(sTargetPath,"rw");

                    String command = "cp -a " + sSource + " " + sTarget;                            //Recursive copy maintaining permissions
                    String sResult = shellExec(command, true);

                    if(bRw)
                        RootTools.remount(sTargetPath,"ro");                                        //only if its not root

                    return true;
                }
                catch(Exception e1)
                {
                    return false;
                }
            }

            private void copyFiles(final BaseFile sourceFile,final HFile targetFile,BufferHandler bufferHandler, GenericCopyThread copyThread, ProgressHandler progressHandler,final int id) throws IOException {
                Log.e("Generic Copy",sourceFile.getPath());
                if (sourceFile.isDirectory()) {
                    if(!hash.get(id))return;
                    if (!targetFile.exists())
                        targetFile.mkdir(c);
                    if(!targetFile.exists()){
                        Log.e("Copy","cant make dir");
                        failedFOps.add(sourceFile);
                        copy_successful=false;
                        return;
                    }
                    targetFile.setLastModified(sourceFile.lastModified());
                    if(!hash.get(id)) return;

                    ArrayList<BaseFile> filePaths = sourceFile.listFiles(false);
                    if (filePaths.size() == 0)
                        bufferHandler.writing = false;                                              //Si no hay archivos para copiar, indicamos que no escribimos

                    for (BaseFile file : filePaths) {
                        HFile destFile = new HFile(targetFile.getMode(),targetFile.getPath(), file.getName(),file.isDirectory());
                        copyFiles(file, destFile,bufferHandler, copyThread, progressHandler, id);
                    }
                    if(!hash.get(id))
                        return;
                } else {
                    if (!hash.get(id))
                        return;
                    Log.e("Copy","Copy start for "+targetFile.getName());

                    // start a new thread only after previous work is done
                    try {
                        if (copyThread.thread!=null) copyThread.thread.join();
                        copyThread.startThread(sourceFile, targetFile, progressHandler);
                    } catch (InterruptedException e) {
                        // thread interrupted due to some problem. we must return
                        failedFOps.add(sourceFile);
                        copy_successful = false;
                    }
                }
            }

            /**
             * Check if source folder is writable or not
             * @param files: ArrayList of BaseFile
             * @return
             */
            private boolean isSourceWritable (final ArrayList<BaseFile> files)
            {
                boolean bSourceWritable = true;                                                     //Assume is writable
                if(files.size() > 0)                                                                //Check if the list is not empty
                {
                    BaseFile bfSource = files.get(0);                                               //We only need to check the first
                    String sSourceFolder = bfSource.getPath().replace(" ", "\\ ");                  //Name could have blank spaces

                    if(!bfSource.isDirectory())                                                     //If it's not a directory, remove the name to get the folder name
                    {
                        String sSourceName = bfSource.getName().replace(" ", "\\ ");
                        sSourceFolder = sSourceFolder.replace(sSourceName, "");
                    }

                    if(utils.checkFolder(sSourceFolder, c) != 1)
                        bSourceWritable = false;
                }

                return bSourceWritable;
            }

            /**
             * Executes commands from terminal and waits for response
             * @param sCommand: command to execute
             * @param bSU: true if required su -only on rooted devives-
             * @return string with the output
             */
            public String shellExec(String sCommand, boolean bSU)
            {
                StringBuffer sOutput = new StringBuffer();
                Process pProcess;

                try
                {
                    if(bSU)                                                                         //if su is required, the command must be executed within the same process!!!
                    {
                        pProcess = Runtime.getRuntime().exec("su");
                        DataOutputStream dos = new DataOutputStream(pProcess.getOutputStream());
                        dos.writeBytes(sCommand + "\n");
                        dos.writeBytes("exit\n");
                        dos.flush();
                        dos.close();
                    }
                    else
                        pProcess = Runtime.getRuntime().exec(sCommand);

                    pProcess.waitFor();                                                             //Waiting the process to end

                    BufferedReader reader = new BufferedReader(new InputStreamReader(pProcess.getInputStream()));
                    String line = "";
                    while ((line = reader.readLine()) != null)
                        sOutput.append(line + "\\n");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                String response = sOutput.toString();
                return response;
            }
        }
    }


    void generateNotification(ArrayList<HFile> failedOps,boolean move) {
        if(failedOps.size()==0)
            return;

        mNotifyManager.cancelAll();
        NotificationCompat.Builder mBuilder=new NotificationCompat.Builder(c);
        mBuilder.setContentTitle("Operation Unsuccessful");
        mBuilder.setContentText("Some files weren't %s successfully".replace("%s",move?"moved":"copied"));
        Intent intent= new Intent(this, MainActivity.class);
        intent.putExtra("failedOps",failedOps);
        intent.putExtra("move",move);
        PendingIntent pIntent = PendingIntent.getActivity(this, 101, intent,PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(pIntent);
        mBuilder.setSmallIcon(R.drawable.ic_content_copy_white_36dp);
        mNotifyManager.notify(741,mBuilder.build());
        intent=new Intent("general_communications");
        intent.putExtra("failedOps",failedOps);
        intent.putExtra("move",move);
        sendBroadcast(intent);
    }

    private void publishResults(String a, int p1, int p2, int id, long total, long done, boolean b, boolean move) {
        if (hash.get(id)) {
            //notification
            mBuilder.setProgress(100, p1, false);
            mBuilder.setOngoing(true);
            int title = R.string.copying;
            if (move) title = R.string.moving;
            mBuilder.setContentTitle(utils.getString(c, title));
            mBuilder.setContentText(new File(a).getName() + " " + utils.readableFileSize(done) + "/" + utils.readableFileSize(total));
            int id1 = Integer.parseInt("456" + id);
            mNotifyManager.notify(id1, mBuilder.build());
            if (p1 == 100 || total == 0) {
                mBuilder.setContentTitle("Copy completed");
                if (move)
                    mBuilder.setContentTitle("Move Completed");
                mBuilder.setContentText("");
                mBuilder.setProgress(0, 0, false);
                mBuilder.setOngoing(false);
                mBuilder.setAutoCancel(true);
                mNotifyManager.notify(id1, mBuilder.build());
                publishCompletedResult(id, id1);
            }
            //for processviewer
            DataPackage intent = new DataPackage();
            intent.setName(new File(a).getName());
            intent.setTotal(total);
            intent.setDone(done);
            intent.setId(id);
            intent.setP1(p1);
            intent.setP2(p2);
            intent.setMove(move);
            intent.setCompleted(b);
            hash1.put(id,intent);
            try {
                if(progressListener!=null){
                    progressListener.onUpdate(intent);
                    if(b)progressListener.refresh();
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else publishCompletedResult(id, Integer.parseInt("456" + id));
    }

    public void publishCompletedResult(int id,int id1){
        try {
            mNotifyManager.cancel(id1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Check if copy is successful
     * Recursive checking across the directories
     * In case of symlink file, does not check the file and returns true
     * ToDo: show progress/message
     * @param hFile1: source
     * @param hFile2: target
     * @return true if ok
     */
    boolean checkFiles(HFile hFile1,HFile hFile2){
        if(RootHelper.isDirectory(hFile1.getPath(),rootmode,5))
        {
            if(!RootHelper.fileExists(hFile2.getPath()))
                return false;
            ArrayList<BaseFile> baseFiles=RootHelper.getFilesList(hFile1.getPath(),true,true,null);
            if(baseFiles.size()>0){
                                                                                                    //boolean b=true;
                for(BaseFile baseFile:baseFiles){
                  if(!checkFiles(new HFile(baseFile.getMode(),baseFile.getPath()),new HFile(hFile2.getMode(),hFile2.getPath()+"/"+(baseFile.getName()))))
                      return false;                                                                 //b=false;
                }
                return true;                                                                        //return b;
            }
            return RootHelper.fileExists(hFile2.getPath());
        }
        else
        {
            long lFile1 = hFile1.length();
            if(lFile1 == -1)                                                                        //Its a symlink so it does not have any length
                return true;                                                                        //Do not check length of destination or even if it exists

            long lFile2 = hFile2.length();
            return (lFile1 == lFile2);
        }
    }


    private BroadcastReceiver receiver3 = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            //cancel operation
            hash.put(intent.getIntExtra("id", 1), false);
        }
    };
    //bind with processviewer
    RegisterCallback registerCallback= new RegisterCallback.Stub() {
        @Override
        public void registerCallBack(ProgressListener p) throws RemoteException {
            progressListener=p;
        }

        @Override
        public List<DataPackage> getCurrent() throws RemoteException {
            List<DataPackage> dataPackages=new ArrayList<>();
            for (int i : hash1.keySet()) {
               dataPackages.add(hash1.get(i));
            }
            return dataPackages;
        }
    };
    @Override
    public IBinder onBind(Intent arg0) {
        // TODO Auto-generated method stub
        return registerCallback.asBinder();
    }
}

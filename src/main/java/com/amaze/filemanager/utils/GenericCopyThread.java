package com.amaze.filemanager.utils;

import android.content.Context;
import android.util.Log;

import com.amaze.filemanager.filesystem.BaseFile;
import com.amaze.filemanager.filesystem.HFile;
import com.amaze.filemanager.services.ProgressHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by vishal on 26/10/16.
 */

public class GenericCopyThread implements Runnable {

    private BaseFile mSourceFile;
    private HFile mTargetFile;
    private Context mContext;
    private ProgressHandler progressHandler;

    public Thread thread;

    public GenericCopyThread(Context context) {
        this.mContext = context;
    }

    @Override

    public void run()
    {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try
        {
            inputStream = mSourceFile.getInputStream();
            outputStream = mTargetFile.getOutputStream(mContext);

            // writing to file
            progressHandler.setFileName(mSourceFile.getName());
            //progressHandler.setTotalSize(mSourceFile.getSize());

            int length=0;
            byte[] buffer;
            final int iReadLength = 1024 * 512;//  *60;
            buffer = new byte[iReadLength];

            while((length=inputStream.read(buffer)) !=- 1) {
                progressHandler.addReadLength(length);
                outputStream.write(buffer, 0, length);
                progressHandler.addWrittenLength(length, 0);
            }
            outputStream.flush();                                                                   //OJO
        } catch (IOException e)
        {
            e.printStackTrace();
            Log.d(getClass().getSimpleName(), "I/O Error!");
        }
        finally
        {
            if (inputStream!=null)
            {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (outputStream != null)
            {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void startThread(BaseFile sourceFile, HFile targetFile, ProgressHandler progressHandler) {

        this.mSourceFile = sourceFile;
        this.mTargetFile = targetFile;
        this.progressHandler = progressHandler;
        thread = new Thread(this);
        thread.start();
    }
}

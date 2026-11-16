package jp.nano.rawgallery.camera;

import android.content.Context;
import java.io.*;

/** Closed counters only: never writes photo names, paths, source IDs, or credentials. */
public final class CameraImportMetrics {
    private final Context context;
    private final String route;
    private final long start=android.os.SystemClock.elapsedRealtime();
    public long sourceBytes, destinationBytes, legacyBytes, listingMs=-1, historyMs=-1;
    public int listed=-1, checked, known, candidates=-1, processed, saved, matches;
    public int attempt;
    /** Classification belongs to the current attempt; committed saves and body bytes span retries. */
    public void beginAttempt(){attempt++;listed=-1;checked=0;known=0;candidates=-1;processed=0;matches=0;listingMs=-1;historyMs=-1;}
    private Runnable savingListener=()->{};
    public void setSavingListener(Runnable listener){savingListener=listener;}
    public void saving(){ImportProgress.INSTANCE.photoSaving(saved);savingListener.run();}
    public CameraImportMetrics(Context context,String route){this.context=context.getApplicationContext();this.route=route;}
    public CameraImportCopy.Copy countSource(CameraImportCopy.Copy copy){return out->copy.write(new FilterOutputStream(out){
        @Override public void write(int b)throws IOException{sourceBytes++;out.write(b);}
        @Override public void write(byte[] b,int off,int len)throws IOException{sourceBytes+=len;out.write(b,off,len);}
    });}
    public void content(){ImportProgress.INSTANCE.photoContent(processed,candidates,saved,matches,sourceBytes,destinationBytes+legacyBytes);}
    public void terminal(String outcome){
        content();ImportProgress.INSTANCE.photoTerminal(outcome,"");
        persist(outcome,"operation");
    }
    /** An attempt may end while the service remains active and retries; do not publish terminal UI. */
    public void recordAttempt(String outcome){persist(outcome,"attempt");}
    private void persist(String outcome,String scope){
        String line="route="+route+" outcome="+outcome+" scope="+scope+" attempt="+attempt+" classificationScope=attempt savedScope=operation bytesScope=operation"+" listed="+listed+" checked="+checked+" known="+known+" needsContent="+candidates+" processed="+processed+" saved="+saved+" contentMatches="+matches+" sourceBytes="+sourceBytes+" destinationVerifyBytes="+destinationBytes+" legacyReadBytes="+legacyBytes+" listingMs="+listingMs+" historyMs="+historyMs+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-start);
        android.util.Log.i("CameraImportMetrics",line);
        context.getSharedPreferences("camera_import_metrics",0).edit().putString("last",line).commit();
    }
}

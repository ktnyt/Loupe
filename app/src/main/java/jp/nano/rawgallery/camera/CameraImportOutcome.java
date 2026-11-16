package jp.nano.rawgallery.camera;

/** User cancellation takes precedence over concurrent transport errors. */
public final class CameraImportOutcome {
    private CameraImportOutcome(){}
    public static String resolve(boolean cancellationRequested,boolean aborted,boolean error,int failed){
        if(cancellationRequested||(aborted&&!error))return "cancelled";
        return error||failed>0?"failed":"complete";
    }
}

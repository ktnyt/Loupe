package jp.nano.rawgallery.camera;

public final class CameraCardRoutingTest {
    public static void main(String[] args) {
        var direct = CameraCardRouting.Route.DIRECT;
        var withoutDetach = CameraCardRouting.Route.DIRECT_WITHOUT_DETACH;
        var mounted = CameraCardRouting.Route.MOUNTED_SOURCE;
        require(CameraCardRouting.route(false, true, true) == direct, "PTP with external destination is unchanged");
        require(CameraCardRouting.route(true, false, false) == direct, "USB reader with internal destination is unchanged");
        require(CameraCardRouting.route(true, true, false) == direct, "Mass-storage camera with internal destination is unchanged");
        require(CameraCardRouting.route(true, true, true) == withoutDetach, "Mass-storage camera cannot detach a kernel driver");
        require(CameraCardRouting.route(true, false, true) == mounted, "Generic reader with external destination cannot open raw USB");
        require(CameraCardRouting.onAttach(mounted, true, false) == CameraCardRouting.Attach.CONFIRM_MOUNTED,
                "Opted-in generic USB attach opens mounted-card confirmation when no watcher owns auto import");
        require(CameraCardRouting.onAttach(mounted, true, true) == CameraCardRouting.Attach.OPEN_SCREEN,
                "An active mounted-card watcher owns automatic submission");
        require(CameraCardRouting.onAttach(mounted, false, false) == CameraCardRouting.Attach.OPEN_SCREEN,
                "USB attach without automatic import only opens the screen");
        require(CameraCardRouting.onAttach(direct, true, true) == CameraCardRouting.Attach.START_DIRECT
                        && CameraCardRouting.onAttach(withoutDetach, true, false) == CameraCardRouting.Attach.START_DIRECT,
                "PTP and known-camera USB attachment routing remain direct");

        require(CameraCardRouting.compareVolumes("01ab-2345", "01ab-2345", "abcd-ef12") == CameraCardRouting.Volumes.DISTINCT,
                "Separate mounted source and pinned target");
        require(CameraCardRouting.compareVolumes("01ab-2345", "01ab-2345", "01AB-2345") == CameraCardRouting.Volumes.SAME_CARD,
                "Card identity is case-insensitive");
        require(CameraCardRouting.compareVolumes("01ab-2345", "01ab-2345", null) == CameraCardRouting.Volumes.SOURCE_UNAVAILABLE,
                "No recognized source cannot start");
        require(CameraCardRouting.compareVolumes("01ab-2345", "abcd-ef12", "1234-5678") == CameraCardRouting.Volumes.TARGET_CHANGED,
                "Selection cannot change across handoff");
        System.out.println("CameraCardRoutingTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

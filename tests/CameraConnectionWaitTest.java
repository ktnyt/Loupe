package jp.nano.rawgallery.camera;
import java.io.*;
import java.util.concurrent.atomic.*;
public final class CameraConnectionWaitTest {
    static final class Clock implements CameraConnectionWait.Time {
        long now;
        public long millis(){return now;}
        public void pause(long millis){now+=millis;}
    }
    public static void main(String[] args)throws Exception {
        Clock clock=new Clock();AtomicInteger calls=new AtomicInteger();Object cameraNetwork=new Object();
        Object session=CameraConnectionWait.await(15000,cancelled->{int n=calls.incrementAndGet();if(n<=2)throw new IOException("Old home Wi-Fi endpoint is not a camera");return cameraNetwork;},()->false,clock);
        if(session!=cameraNetwork||calls.get()!=3)throw new AssertionError("Validated new Network was not handed through");
        AtomicInteger cancelledCalls=new AtomicInteger();
        try{CameraConnectionWait.await(15000,c->{cancelledCalls.incrementAndGet();return cameraNetwork;},()->true,new Clock());throw new AssertionError("Cancellation accepted");}catch(InterruptedIOException expected){}
        if(cancelledCalls.get()!=0)throw new AssertionError("Cancelled request probed a Network");
        Clock deadline=new Clock();
        try{CameraConnectionWait.await(1000,c->{throw new IOException("Old Network remains");},()->false,deadline);throw new AssertionError("Non-camera Network was accepted");}catch(IOException expected){if(deadline.now!=1000)throw new AssertionError("Deadline not bounded");}
        AtomicInteger absent=new AtomicInteger();
        if(CameraConnectionWait.await(15000,c->absent.incrementAndGet()==1?null:cameraNetwork,()->false,new Clock())!=cameraNetwork)throw new AssertionError("No-network transition failed");
        System.out.println("PASS camera connection: old Wi-Fi remains, delayed camera network, validated-session handoff, cancellation and deadline");
    }
}

package com.greatwall.callring.entrypoint;

import com.greatwall.callring.entrypoint.calling.PlaybackServiceController;
import java.util.*;
import static com.greatwall.callring.entrypoint.calling.PlaybackServiceController.Command.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackServiceControllerTest {
    static class Media implements PlaybackServiceController.Media {
        Runnable prepared, error; int starts, releases;
        public void initialize(Runnable p, Runnable e) { prepared=p; error=e; }
        public void configureSource() {}
        public void prepareAsync() {}
        public void start() { starts++; }
        public void release() { releases++; }
    }
    static class Rig {
        long now; boolean ringing, active, focusGranted=true; Runnable focusLoss; int sync, clears;
        List<Integer> ready=new ArrayList<>(); List<Runnable> worker=new ArrayList<>();
        List<Runnable> posted=new ArrayList<>(); Map<Long,Runnable> delayed=new HashMap<>();
        List<Media> media=new ArrayList<>();
        PlaybackServiceController controller=new PlaybackServiceController(
            ()->now, new PlaybackServiceController.Scheduler() {
                public void post(Runnable r) { posted.add(r); }
                public void later(Runnable r,long ms) { delayed.put(ms,r); }
                public void remove(Runnable r) { delayed.values().removeIf(v->v==r); }
                public void clear() { delayed.clear(); posted.clear(); }
            }, worker::add, ()->{ Media m=new Media();media.add(m);return m; },
            ()->true, ()->true, ()->true, ()->new PlaybackServiceController.Observation(ringing,active),
            (mode,state)->{}, ()->clears++, event->{}, preview->{},
            ()->new PlaybackServiceController.Lease() { public void acquire() {} public void release() {} }, loss->{focusLoss=loss;return focusGranted;}, ()->{}, ()->sync++, ()->{}, ()->{}, ()->{});
    }
    @Test public void wrongCancelAndDuplicateStartPreserveAttemptAndReadinessIsOnce() {
        Rig r=new Rig();r.controller.command(PREPARE,"a",r.ready::add);
        r.controller.command(CANCEL,"other",null);
        List<Integer> other=new ArrayList<>();r.controller.command(PREPARE,"b",other::add);
        assertEquals(Arrays.asList(0),other);
        r.worker.remove(0).run();r.media.get(0).prepared.run();
        assertEquals(Arrays.asList(1),r.ready);
        assertTrue(r.controller.commitPrepared("a"));assertFalse(r.controller.commitPrepared("a"));
        r.controller.command(STOP,null,null);r.controller.command(STOP,null,null);
        assertEquals(Arrays.asList(1),r.ready);assertEquals(1,r.sync);assertEquals(1,r.clears);
    }
    @Test public void deadlineIsIndependentOfCleanupTimeoutAndLatePreparedIsIgnored() {
        Rig r=new Rig();r.controller.command(PREPARE,"a",r.ready::add);
        r.worker.remove(0).run();r.media.get(0).prepared.run();r.now=1800;
        assertFalse(r.controller.commitPrepared("a"));
        r.delayed.get(2400L).run();r.media.get(0).prepared.run();
        assertEquals(Arrays.asList(1),r.ready);assertEquals(1,r.sync);
    }
    @Test public void staleCallbackCannotPrepareReplacementAndMediaReleaseFollowsQueuedConfiguration() {
        Rig r=new Rig();r.controller.command(PREPARE,"a",r.ready::add);
        Media old=r.media.get(0);r.controller.command(STOP,null,null);
        List<Integer> next=new ArrayList<>();r.controller.command(PREPARE,"b",next::add);
        old.prepared.run();assertTrue(next.isEmpty());
        r.worker.remove(0).run();assertEquals(0,old.releases);
        r.worker.remove(0).run();assertEquals(1,old.releases);
        r.media.get(1).prepared.run();assertEquals(Arrays.asList(1),next);
    }
    @Test public void timeoutBeforeReadyReportsFailureOnceAndDestroyDoesNotSync() {
        Rig r=new Rig();r.controller.command(PREPARE,"a",r.ready::add);
        r.controller.destroy();r.media.get(0).prepared.run();
        assertEquals(Arrays.asList(0),r.ready);assertEquals(0,r.sync);
    }
    @Test public void pollStartsOnceThenActiveCallFinishesOnce() {
        Rig r=new Rig();r.ringing=true;r.controller.command(PREPARE,"a",r.ready::add);
        r.media.get(0).prepared.run();assertTrue(r.controller.commitPrepared("a"));
        r.posted.remove(0).run();r.delayed.get(150L).run();assertEquals(1,r.media.get(0).starts);
        r.active=true;r.delayed.get(150L).run();assertEquals(1,r.sync);assertEquals(1,r.clears);
    }
    @Test public void previewFocusDenialAndLossFinishWithoutCallSync() {
        for (boolean granted : new boolean[]{false,true}) {
            Rig r=new Rig();r.focusGranted=granted;r.controller.command(PREVIEW,"a",r.ready::add);
            r.media.get(0).prepared.run();if (granted) r.focusLoss.run();
            assertEquals(0,r.sync);assertEquals(1,r.clears);assertEquals(Arrays.asList(0),r.ready);
        }
    }

}

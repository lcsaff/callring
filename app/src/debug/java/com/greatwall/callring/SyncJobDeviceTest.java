package com.greatwall.callring;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.provider.CallLog;
import android.test.InstrumentationTestCase;

public class SyncJobDeviceTest extends InstrumentationTestCase {
    public void testCallLogChangeIsObservedBySystemWithoutKeepingProcessAlive() {
        Context context=getInstrumentation().getTargetContext();
        assertTrue("test requires CallLog permission",CallLogSynchronizer.permitted(context));
        SyncJobService.schedule(context);
        JobInfo observer=context.getSystemService(JobScheduler.class).getPendingJob(75);
        assertNotNull("system CallLog changes must be able to wake a synchronization job",observer);
        assertFalse("content observers cannot be persisted jobs",observer.isPersisted());
        assertEquals(CallLog.Calls.CONTENT_URI,observer.getTriggerContentUris()[0].getUri());
    }
}

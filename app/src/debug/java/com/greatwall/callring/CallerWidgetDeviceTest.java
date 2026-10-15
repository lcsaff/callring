package com.greatwall.callring;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import java.util.UUID;

/** Widget tests own only these two preference keys; they never clear app preferences. */
public class CallerWidgetDeviceTest extends InstrumentationTestCase {
    private Context context;
    private int first, second;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        first = 1000000000 + (UUID.randomUUID().hashCode() & 0x0ffffffe);
        second = first + 1;
    }
    @Override protected void tearDown() throws Exception {
        new CallerWidgetProvider().onDeleted(context, new int[]{first, second});
        super.tearDown();
    }
    public void testUnboundWidgetHasNoNumber() {
        assertEquals("", CallerWidgetProvider.binding(context, first));
    }
    public void testBindingsRemainIndependentAndNormalizeSeparators() {
        CallerWidgetProvider.bind(context, first, "3 (1234)");
        CallerWidgetProvider.bind(context, second, "+86 31235");
        assertEquals("31234", CallerWidgetProvider.binding(context, first));
        assertEquals("+8631235", CallerWidgetProvider.binding(context, second));
    }
    public void testInvalidNumberCannotReplaceBinding() {
        CallerWidgetProvider.bind(context, first, "31234");
        try {
            CallerWidgetProvider.bind(context, first, "");
            fail("empty widget number accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            CallerWidgetProvider.bind(context, first, "31234;anything");
            fail("invalid widget number accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals("31234", CallerWidgetProvider.binding(context, first));
    }
    public void testDeletedWidgetRemovesOnlyItsBinding() {
        CallerWidgetProvider.bind(context, first, "31234");
        CallerWidgetProvider.bind(context, second, "31235");
        new CallerWidgetProvider().onDeleted(context, new int[]{first});
        assertEquals("", CallerWidgetProvider.binding(context, first));
        assertEquals("31235", CallerWidgetProvider.binding(context, second));
    }
    public void testActionPendingIntentsAreImmutableAndIsolated() {
        PendingIntent whiteFirst = CallerWidgetProvider.actionPendingIntent(context, first, CallerWidgetProvider.ACTION_WHITE);
        PendingIntent whiteSecond = CallerWidgetProvider.actionPendingIntent(context, second, CallerWidgetProvider.ACTION_WHITE);
        PendingIntent quietFirst = CallerWidgetProvider.actionPendingIntent(context, first, CallerWidgetProvider.ACTION_QUIET);
        PendingIntent same = CallerWidgetProvider.actionPendingIntent(context, first, CallerWidgetProvider.ACTION_WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(whiteFirst.isImmutable());
        assertEquals(whiteFirst, same);
        assertFalse(whiteFirst.equals(whiteSecond));
        assertFalse(whiteFirst.equals(quietFirst));
        whiteFirst.cancel(); whiteSecond.cancel(); quietFirst.cancel();
    }
    public void testOrphanWidgetCannotToggleGlobalQuietMode() {
        QuietHoursPolicy.Mode before = CallerRepository.get(context).quietMode();
        CallerWidgetProvider.bind(context, first, "31234");
        new CallerWidgetProvider().onReceive(context, new Intent(context, CallerWidgetProvider.class)
                .setAction(CallerWidgetProvider.ACTION_QUIET)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, first));
        assertEquals(before, CallerRepository.get(context).quietMode());
    }
}

package com.example.android.notepad;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Home-screen widget that shows notes as a grid of colorful sticky notes.
 */
public class NotePadWidgetProvider extends AppWidgetProvider {

    // PendingIntent.FLAG_MUTABLE was added in API 31; this app compiles against API 23.
    private static final int FLAG_MUTABLE_COMPAT = 0x02000000;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager appWidgetManager,
                                     int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_notes);

        // Binds the collection of sticky notes through the RemoteViews service.
        Intent serviceIntent = new Intent(context, NotePadWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_notes_grid, serviceIntent);
        views.setEmptyView(R.id.widget_notes_grid, R.id.widget_empty);

        // Tapping the header opens the notes list.
        Intent openApp = new Intent(context, NotesList.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_header,
                PendingIntent.getActivity(context, 0, openApp, immutableFlags()));

        // Tapping "+" creates a new note.
        Intent addNote = new Intent(Intent.ACTION_INSERT, NotePad.Notes.CONTENT_URI);
        addNote.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widget_add,
                PendingIntent.getActivity(context, 1, addNote, immutableFlags()));

        // Template used by each note card; the fill-in intent supplies the note URI.
        Intent editTemplate = new Intent(Intent.ACTION_EDIT);
        editTemplate.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setPendingIntentTemplate(R.id.widget_notes_grid,
                PendingIntent.getActivity(context, 2, editTemplate, templateFlags()));

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static int immutableFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static int templateFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            // Fill-in intents must be merged into the template on Android 12 and above.
            flags |= FLAG_MUTABLE_COMPAT;
        }
        return flags;
    }

    /**
     * Asks every placed widget to reload its note data.
     */
    public static void notifyNotesChanged(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, NotePadWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        if (ids.length > 0) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_notes_grid);
        }
    }
}

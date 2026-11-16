package com.example.android.notepad;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Supplies the note cards shown inside the home-screen widget.
 */
public class NotePadWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new NoteViewsFactory(getApplicationContext());
    }

    private static class NoteViewsFactory implements RemoteViewsFactory {

        private static final String[] PROJECTION = new String[] {
                NotePad.Notes._ID, // 0
                NotePad.Notes.COLUMN_NAME_TITLE, // 1
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, // 2
                NotePad.Notes.COLUMN_NAME_PINNED, // 3
                NotePad.Notes.COLUMN_NAME_NOTE, // 4
                NotePad.Notes.COLUMN_NAME_COLOR, // 5
        };

        // One rounded card drawable for each color in NoteColors.VALUES.
        private static final int[] CARD_BACKGROUNDS = {
                R.drawable.widget_card_0,
                R.drawable.widget_card_1,
                R.drawable.widget_card_2,
                R.drawable.widget_card_3,
                R.drawable.widget_card_4,
                R.drawable.widget_card_5,
                R.drawable.widget_card_6,
                R.drawable.widget_card_7,
        };

        private final Context mContext;
        private final SimpleDateFormat mDateFormat =
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        private Cursor mCursor;

        NoteViewsFactory(Context context) {
            mContext = context;
        }

        @Override
        public void onCreate() {
            // No-op; the cursor is loaded in onDataSetChanged().
        }

        @Override
        public void onDataSetChanged() {
            if (mCursor != null) {
                mCursor.close();
            }
            mCursor = mContext.getContentResolver().query(
                    NotePad.Notes.CONTENT_URI,
                    PROJECTION,
                    null,
                    null,
                    NotePad.Notes.DEFAULT_SORT_ORDER);
        }

        @Override
        public void onDestroy() {
            if (mCursor != null) {
                mCursor.close();
                mCursor = null;
            }
        }

        @Override
        public int getCount() {
            return mCursor == null ? 0 : mCursor.getCount();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (mCursor == null || !mCursor.moveToPosition(position)) {
                return null;
            }

            int colorIndex = NoteColors.index(mCursor.getInt(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_COLOR)));

            RemoteViews views = new RemoteViews(
                    mContext.getPackageName(), R.layout.widget_note_item);

            String title = mCursor.getString(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_TITLE));
            String note = mCursor.getString(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_NOTE));
            long modified = mCursor.getLong(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE));
            boolean pinned = mCursor.getInt(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_PINNED)) == 1;

            note = note == null ? "" : note.replace('\n', ' ').trim();
            if (note.length() > 40) {
                note = note.substring(0, 40) + "…";
            }

            views.setTextViewText(R.id.widget_note_title, title);
            views.setTextColor(R.id.widget_note_title, NoteColors.title(colorIndex));

            views.setTextViewText(R.id.widget_note_preview, note);
            views.setTextColor(R.id.widget_note_preview, NoteColors.secondary(colorIndex));

            views.setTextViewText(R.id.widget_note_time,
                    mDateFormat.format(new Date(modified)));
            views.setTextColor(R.id.widget_note_time, NoteColors.time(colorIndex));

            views.setInt(R.id.widget_note_root, "setBackgroundResource",
                    CARD_BACKGROUNDS[colorIndex]);

            views.setViewVisibility(R.id.widget_note_pin, pinned ? View.VISIBLE : View.GONE);
            views.setTextColor(R.id.widget_note_pin, NoteColors.pin(colorIndex));

            // Tapping a card opens that note in the editor.
            long id = mCursor.getLong(
                    mCursor.getColumnIndexOrThrow(NotePad.Notes._ID));
            Uri noteUri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, id);
            Intent fillInIntent = new Intent();
            fillInIntent.setData(noteUri);
            views.setOnClickFillInIntent(R.id.widget_note_root, fillInIntent);

            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (mCursor != null && mCursor.moveToPosition(position)) {
                return mCursor.getLong(
                        mCursor.getColumnIndexOrThrow(NotePad.Notes._ID));
            }
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}

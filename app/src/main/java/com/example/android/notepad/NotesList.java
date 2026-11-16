/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import com.example.android.notepad.NotePad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


/**
 * Displays notes as a grid of colorful sticky notes. Will display notes from the {@link Uri}
 * provided in the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 *
 * Besides the original sample, the grid shows the last modification time, supports searching
 * by title or content, lets the user pin notes and choose a sticky-note color, and can export
 * notes as plain text.
 */
public class NotesList extends Activity implements AdapterView.OnItemClickListener {

    // For logging and debugging
    private static final String TAG = "NotesList";

    /**
     * The columns needed by the cursor adapter
     */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID, // 0
            NotePad.Notes.COLUMN_NAME_TITLE, // 1
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, // 2
            NotePad.Notes.COLUMN_NAME_PINNED, // 3
            NotePad.Notes.COLUMN_NAME_NOTE, // 4
            NotePad.Notes.COLUMN_NAME_COLOR, // 5
    };

    /** The index of the title column */
    private static final int COLUMN_INDEX_TITLE = 1;

    // The URI of the notes directory currently being displayed.
    private Uri mBaseUri;

    // Search box and its clear button.
    private EditText mSearchText;
    private View mClearSearch;

    // The grid that displays the sticky notes.
    private GridView mGrid;

    // The adapter that binds notes to the grid.
    private NotesAdapter mAdapter;

    // Formats the modification timestamp shown for each note.
    private final SimpleDateFormat mDateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    /**
     * onCreate is called when Android starts this Activity from scratch.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The user does not need to hold down the key to use menu shortcuts.
        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        /* If no data is given in the Intent that started this Activity, then this Activity
         * was started when the intent filter matched a MAIN action. We should use the default
         * provider URI.
         */
        Intent intent = getIntent();
        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }
        mBaseUri = intent.getData();

        // Uses a custom layout with a search box, a sticky-note grid and an empty view.
        setContentView(R.layout.notes_list);

        mSearchText = (EditText) findViewById(R.id.search_text);
        mClearSearch = findViewById(R.id.clear_search);
        mClearSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearchText.setText("");
            }
        });

        mSearchText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Hides the clear button when the search box is empty.
                mClearSearch.setVisibility(
                        TextUtils.isEmpty(s.toString()) ? View.GONE : View.VISIBLE);
                refreshList();
            }
        });

        mGrid = (GridView) findViewById(R.id.notes_grid);
        mGrid.setOnCreateContextMenuListener(this);
        mGrid.setOnItemClickListener(this);

        // Shows the empty view when the grid (or the search result) contains no notes.
        mGrid.setEmptyView(findViewById(android.R.id.empty));

        mAdapter = new NotesAdapter(this, null);
        mGrid.setAdapter(mAdapter);
    }

    /**
     * Reloads the grid from the provider, applying the current search keyword if any.
     */
    private void refreshList() {
        String keyword = mSearchText.getText().toString().trim();
        String selection = null;
        String[] selectionArgs = null;

        if (!TextUtils.isEmpty(keyword)) {
            // Matches the keyword against both the title and the content of a note.
            selection = NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ? OR "
                    + NotePad.Notes.COLUMN_NAME_NOTE + " LIKE ?";
            String like = "%" + keyword + "%";
            selectionArgs = new String[] { like, like };
        }

        Cursor cursor = getContentResolver().query(
                mBaseUri,
                PROJECTION,
                selection,
                selectionArgs,
                NotePad.Notes.DEFAULT_SORT_ORDER);

        // Uses a search-specific message when a keyword returns no matches.
        TextView emptyText = (TextView) findViewById(R.id.empty_text);
        TextView emptyHint = (TextView) findViewById(R.id.empty_hint);
        if (emptyText != null && emptyHint != null) {
            if (TextUtils.isEmpty(keyword)) {
                emptyText.setText(R.string.empty_notes);
                emptyHint.setText(R.string.empty_notes_hint);
            } else {
                emptyText.setText(R.string.empty_search);
                emptyHint.setText(R.string.empty_search_hint);
            }
        }

        // changeCursor() also closes the previous cursor.
        mAdapter.changeCursor(cursor);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Avoids showing the soft keyboard when the grid comes back to the foreground with
        // an empty search box.
        if (TextUtils.isEmpty(mSearchText.getText())) {
            mSearchText.clearFocus();
            mGrid.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
            }
        }

        // Refreshes the grid so that edits made in NoteEditor show up immediately.
        refreshList();
    }

    @Override
    protected void onDestroy() {
        if (mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        super.onDestroy();
    }

    /**
     * Called when the user clicks the device's Menu button the first time for
     * this Activity. Android passes in a Menu object that is populated with items.
     *
     * Sets up a menu that provides the Insert option plus a list of alternative actions for
     * this Activity. Other applications that want to handle notes can "register" themselves in
     * Android by providing an intent filter that includes the category ALTERNATIVE and the
     * mimeTYpe NotePad.Notes.CONTENT_TYPE. If they do this, the code in onCreateOptionsMenu()
     * will add the Activity that contains the intent filter to its list of options. In effect,
     * the menu will offer the user other applications that can handle notes.
     * @param menu A Menu object, to which menu items should be added.
     * @return True, always. The menu should be displayed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        // Generate any additional actions that can be performed on the
        // overall list.  In a normal install, there are no additional
        // actions found here, but this allows other applications to extend
        // our menu with their own actions.
        Intent intent = new Intent(null, mBaseUri);
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // The paste menu item is enabled if there is data on the clipboard.
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        MenuItem mPasteItem = menu.findItem(R.id.menu_paste);

        // If the clipboard contains an item, enables the Paste option on the menu.
        if (clipboard.hasPrimaryClip()) {
            mPasteItem.setEnabled(true);
        } else {
            // If the clipboard is empty, disables the menu's Paste option.
            mPasteItem.setEnabled(false);
        }

        // Gets the number of notes currently being displayed.
        final boolean haveItems = mAdapter.getCount() > 0;

        // In touch mode the grid normally has no selected item, so alternatives are only
        // generated when a row is actually selected.
        final long selectedId = mGrid.getSelectedItemId();
        if (haveItems && selectedId != AdapterView.INVALID_ROW_ID) {

            // This is the selected item.
            Uri uri = ContentUris.withAppendedId(mBaseUri, selectedId);

            // Creates an array of Intents with one element. This will be used to send an Intent
            // based on the selected menu item.
            Intent[] specifics = new Intent[1];

            // Sets the Intent in the array to be an EDIT action on the URI of the selected note.
            specifics[0] = new Intent(Intent.ACTION_EDIT, uri);

            // Creates an array of menu items with one element. This will contain the EDIT option.
            MenuItem[] items = new MenuItem[1];

            // Creates an Intent with no specific action, using the URI of the selected note.
            Intent intent = new Intent(null, uri);

            /* Adds the category ALTERNATIVE to the Intent, with the note ID URI as its
             * data. This prepares the Intent as a place to group alternative options in the
             * menu.
             */
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

            menu.addIntentOptions(
                Menu.CATEGORY_ALTERNATIVE,  // Add the Intents as options in the alternatives group.
                Menu.NONE,                  // A unique item ID is not required.
                Menu.NONE,                  // The alternatives don't need to be in order.
                null,                       // The caller's name is not excluded from the group.
                specifics,                  // These specific options must appear first.
                intent,                     // These Intent objects map to the options in specifics.
                Menu.NONE,                  // No flags are required.
                items                       // The menu items generated from the specifics-to-
            );

            if (items[0] != null) {
                // Sets the Edit menu item shortcut to numeric "1", letter "e"
                items[0].setShortcut('1', 'e');
            }
        } else {
            // If nothing is selected, removes any existing alternative actions from the menu
            menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
        }

        // Displays the menu
        return true;
    }

    /**
     * This method is called when the user selects an option from the menu.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_add) {
            startActivity(new Intent(Intent.ACTION_INSERT, mBaseUri));
            return true;
        } else if (item.getItemId() == R.id.menu_paste) {
            startActivity(new Intent(Intent.ACTION_PASTE, mBaseUri));
            return true;
        } else if (item.getItemId() == R.id.menu_export_all) {
            exportAllNotes();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Exports every note currently stored in the provider by sharing them as plain text.
     */
    private void exportAllNotes() {
        String[] projection = new String[] {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_NOTE
        };
        Cursor cursor = getContentResolver().query(
                mBaseUri, projection, null, null, NotePad.Notes.DEFAULT_SORT_ORDER);

        if (cursor == null) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder builder = new StringBuilder();
        try {
            while (cursor.moveToNext()) {
                builder.append(cursor.getString(0));
                builder.append('\n');
                builder.append(cursor.getString(1));
                builder.append("\n\n");
            }
        } finally {
            cursor.close();
        }

        if (builder.length() == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_all_subject));
        intent.putExtra(Intent.EXTRA_TEXT, builder.toString());
        startActivity(Intent.createChooser(intent, getString(R.string.menu_export_all)));
    }

    /**
     * This method is called when the user long-presses a note in the grid.
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {

        AdapterView.AdapterContextMenuInfo info;

        try {
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        Cursor cursor = (Cursor) mAdapter.getItem(info.position);
        if (cursor == null) {
            return;
        }

        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_context_menu, menu);

        // Sets the menu header to be the title of the selected note.
        menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

        // Shows "取消置顶" for a pinned note, "置顶" otherwise.
        MenuItem pinItem = menu.findItem(R.id.context_pin);
        if (pinItem != null) {
            boolean pinned = cursor.getInt(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_PINNED)) == 1;
            pinItem.setTitle(pinned ? R.string.menu_unpin : R.string.menu_pin);
        }

        // Appends any alternative actions registered by other applications.
        Intent intent = new Intent(null, Uri.withAppendedPath(mBaseUri,
                                        Integer.toString((int) info.id) ));
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    /**
     * This method is called when the user selects an item from the context menu.
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;

        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return false;
        }

        Uri noteUri = ContentUris.withAppendedId(mBaseUri, info.id);
        Cursor cursor = (Cursor) mAdapter.getItem(info.position);

        int id = item.getItemId();
        if (id == R.id.context_open) {
            startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
            return true;
        } else if (id == R.id.context_copy) {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(
                    getContentResolver(),
                    "Note",
                    noteUri));
            return true;
        } else if (id == R.id.context_pin) {
            if (cursor != null) {
                boolean pinned = cursor.getInt(
                        cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_PINNED)) == 1;

                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_PINNED, pinned ? 0 : 1);
                getContentResolver().update(noteUri, values, null, null);

                refreshList();
                NotePadWidgetProvider.notifyNotesChanged(this);
            }
            return true;
        } else if (id == R.id.context_color) {
            if (cursor != null) {
                int color = cursor.getInt(
                        cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_COLOR));
                showColorPicker(noteUri, NoteColors.index(color));
            }
            return true;
        } else if (id == R.id.context_delete) {
            getContentResolver().delete(noteUri, null, null);
            refreshList();
            NotePadWidgetProvider.notifyNotesChanged(this);
            return true;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * Shows a palette dialog and saves the selected sticky-note color.
     */
    private void showColorPicker(final Uri noteUri, final int currentColor) {
        final int[] colors = NoteColors.VALUES;

        GridView colorGrid = new GridView(this);
        int padding = dp(16);
        colorGrid.setPadding(padding, padding, padding, padding);
        colorGrid.setNumColumns(4);
        colorGrid.setHorizontalSpacing(dp(10));
        colorGrid.setVerticalSpacing(dp(10));
        colorGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);

        BaseAdapter colorAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return colors.length;
            }

            @Override
            public Object getItem(int position) {
                return colors[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView swatch = new TextView(NotesList.this);
                swatch.setGravity(Gravity.CENTER);
                swatch.setText(position == currentColor ? "\u2713" : "");
                swatch.setTextSize(18);
                swatch.setTextColor(NoteColors.title(position));

                GradientDrawable background = new GradientDrawable();
                background.setColor(colors[position]);
                background.setCornerRadius(dp(10));
                background.setStroke(dp(1), NoteColors.stroke(position));
                swatch.setBackgroundDrawable(background);
                swatch.setLayoutParams(new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, dp(48)));
                return swatch;
            }
        };
        colorGrid.setAdapter(colorAdapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_color)
                .setView(colorGrid)
                .setNegativeButton(R.string.cancel, null)
                .create();

        colorGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_COLOR, position);
                getContentResolver().update(noteUri, values, null, null);
                refreshList();
                NotePadWidgetProvider.notifyNotesChanged(NotesList.this);
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    /**
     * Called when a sticky note is clicked.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Uri uri = ContentUris.withAppendedId(mBaseUri, id);

        // Gets the action from the incoming Intent
        String action = getIntent().getAction();

        // Handles requests for note data
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Binds a sticky note: background color, title, preview, modification time and pin star.
     */
    private class NotesAdapter extends CursorAdapter {

        NotesAdapter(Context context, Cursor cursor) {
            super(context, cursor, 0);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return LayoutInflater.from(context)
                    .inflate(R.layout.noteslist_item, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            TextView title = (TextView) view.findViewById(R.id.note_title);
            TextView preview = (TextView) view.findViewById(R.id.note_preview);
            TextView time = (TextView) view.findViewById(R.id.note_time);
            TextView pinned = (TextView) view.findViewById(R.id.note_pinned);

            int colorIndex = NoteColors.index(cursor.getInt(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_COLOR)));

            // Paints the card like a colored sticky note.
            GradientDrawable background = new GradientDrawable();
            background.setColor(NoteColors.background(colorIndex));
            background.setCornerRadius(dp(14));
            background.setStroke(dp(1), NoteColors.stroke(colorIndex));
            view.setBackgroundDrawable(background);

            title.setTextColor(NoteColors.title(colorIndex));
            preview.setTextColor(NoteColors.secondary(colorIndex));
            time.setTextColor(NoteColors.time(colorIndex));
            pinned.setTextColor(NoteColors.pin(colorIndex));

            title.setText(cursor.getString(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_TITLE)));

            // Shows a short preview of the note content.
            String note = cursor.getString(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_NOTE));
            note = note == null ? "" : note.replace('\n', ' ').trim();
            if (note.length() > 60) {
                note = note.substring(0, 60) + "…";
            }
            preview.setText(note);

            long modified = cursor.getLong(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE));
            time.setText(context.getString(
                    R.string.modified_at, mDateFormat.format(new Date(modified))));

            boolean isPinned = cursor.getInt(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_PINNED)) == 1;
            pinned.setVisibility(isPinned ? View.VISIBLE : View.INVISIBLE);
        }
    }
}

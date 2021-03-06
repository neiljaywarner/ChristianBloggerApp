/*
 * Copyright 2013 The Android Open Source Project
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

package org.disciplestoday.disciplestoday.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import org.disciplestoday.disciplestoday.data.CupboardSQLiteOpenHelper;
import org.disciplestoday.disciplestoday.utils.SelectionBuilder;


public class FeedProvider extends ContentProvider {
    private CupboardSQLiteOpenHelper mDatabaseHelper;

    /**
     * Content authority for this provider.
     */
    private static final String AUTHORITY = FeedContract.CONTENT_AUTHORITY;
    public static final String TAG = "NJW";

    // The constants below represent individual URI routes, as IDs. Every URI pattern recognized by
    // this ContentProvider is defined using sUriMatcher.addURI(), and associated with one of these
    // IDs.
    //
    // When a incoming URI is run through sUriMatcher, it will be tested against the defined
    // URI patterns, and the corresponding route ID will be returned.
    /**
     * URI ID for route: /articles
     */
    private static final int ROUTE_ARTICLES = 1;

    /**
     * URI ID for route: /articles/{ID}
     */
    private static final int ROUTE_articles_ID = 2;

    /**
     * UriMatcher, used to decode incoming URIs.
     */
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, "articles", ROUTE_ARTICLES);
        sUriMatcher.addURI(AUTHORITY, "articles/*", ROUTE_articles_ID);
    }

    @Override
    public boolean onCreate() {
        mDatabaseHelper = new CupboardSQLiteOpenHelper(getContext());
        return true;
    }

    /**
     * Determine the mime type for articles returned by a given URI.
     */
    @Override
    public String getType(Uri uri) {
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case ROUTE_ARTICLES:
                return FeedContract.Entry.CONTENT_TYPE;
            case ROUTE_articles_ID:
                return FeedContract.Entry.CONTENT_ITEM_TYPE;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
    }

    /**
     * Perform a database query by URI.
     *
     * <p>Currently supports returning all articles (/articles) and individual articles by ID
     * (/articles/{ID}).
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
      //  Log.d(TAG, "--->>query() called with: uri = [" + uri + "], projection = [" + projection + "], selection = [" + selection + "], selectionArgs = [" + selectionArgs + "], sortOrder = [" + sortOrder + "]");
        Log.d(TAG, "Query called with sort order:" + sortOrder)
;        SQLiteDatabase db = mDatabaseHelper.getReadableDatabase();
        SelectionBuilder builder = new SelectionBuilder();
        int uriMatch = sUriMatcher.match(uri);
        switch (uriMatch) {
            case ROUTE_articles_ID:
                // Return a single entry, by ID.
                String id = uri.getLastPathSegment();
                builder.where(FeedContract.Entry._ID + "=?", id);
            case ROUTE_ARTICLES:
                // Return all known articles.
                builder.table(FeedContract.Entry.TABLE_NAME)
                       .where(selection, selectionArgs);
                Cursor c = builder.query(db, projection, sortOrder);
                // Note: Notification URI must be manually set here for loaders to correctly
                // register ContentObservers.
                Context ctx = getContext();
                assert ctx != null;
                c.setNotificationUri(ctx.getContentResolver(), uri);
                return c;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
    }

    /**
     * Insert a new entry into the database.
     * NOTE: When the same article is already there, just replace.  This does four important things
     * 1. Keeps us from having duplicates
     * (In our case there could even be duplicates from feed to feed we'd rather not clog up our db with)
     * TOOD: Make sure no side effects though - does constraint need tweaked - could near duplicate be in 2 feeds, etc?
     * 2. Lets us grab all 10 all the time and never nave a problem
     * 3. Lets us get updates if there are some, which woudl be expected behaviour.
     * ----- If content is updated (fix a typo or whatever) and you said 'no, keep the old one' you'd lose valuable info
     * 4. Leaves it up to well-tested deeply internal sqllite code to do it :)
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.d(TAG, "db insert() called");
        final SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        assert db != null;
        final int match = sUriMatcher.match(uri);
        Uri result;
        switch (match) {
            case ROUTE_ARTICLES:
                long id = db.insertWithOnConflict(FeedContract.Entry.TABLE_NAME, FeedContract.Entry.COLUMN_NAME_ARTICLE_ID, values, SQLiteDatabase.CONFLICT_REPLACE);
                Log.d(TAG, "inserted id=" + id);

                result = Uri.parse(FeedContract.Entry.CONTENT_URI + "/" + id);
                break;
            case ROUTE_articles_ID:
                throw new UnsupportedOperationException("Insert not supported on URI: " + uri);
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        // Send broadcast to registered ContentObservers, to refresh UI.
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return result;
    }

    /**
     * Delete an entry by database by URI.
     */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SelectionBuilder builder = new SelectionBuilder();
        final SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        final int match = sUriMatcher.match(uri);
        int count;
        switch (match) {
            case ROUTE_ARTICLES:
                count = builder.table(FeedContract.Entry.TABLE_NAME)
                        .where(selection, selectionArgs)
                        .delete(db);
                break;
            case ROUTE_articles_ID:
                String id = uri.getLastPathSegment();
                count = builder.table(FeedContract.Entry.TABLE_NAME)
                       .where(FeedContract.Entry._ID + "=?", id)
                       .where(selection, selectionArgs)
                       .delete(db);
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        // Send broadcast to registered ContentObservers, to refresh UI.
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return count;
    }

    /**
     * Update an etry in the database by URI.
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SelectionBuilder builder = new SelectionBuilder();
        final SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        final int match = sUriMatcher.match(uri);
        int count;
        switch (match) {
            case ROUTE_ARTICLES:
                count = builder.table(FeedContract.Entry.TABLE_NAME)
                        .where(selection, selectionArgs)
                        .update(db, values);
                break;
            case ROUTE_articles_ID:
                String id = uri.getLastPathSegment();
                count = builder.table(FeedContract.Entry.TABLE_NAME)
                        .where(FeedContract.Entry._ID + "=?", id)
                        .where(selection, selectionArgs)
                        .update(db, values);
                break;
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
        Context ctx = getContext();
        assert ctx != null;
        ctx.getContentResolver().notifyChange(uri, null, false);
        return count;
    }

}

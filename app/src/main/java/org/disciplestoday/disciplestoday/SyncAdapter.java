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

package org.disciplestoday.disciplestoday;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.disciplestoday.disciplestoday.data.ArticleResponse;
import org.disciplestoday.disciplestoday.data.Feed;
import org.disciplestoday.disciplestoday.data.WordPressService;
import org.disciplestoday.disciplestoday.provider.FeedContract;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.simplexml.SimpleXmlConverterFactory;

import static org.disciplestoday.disciplestoday.SyncUtils.PREF_SETUP_COMPLETE;

/**
 * Define a sync adapter for the app.
 *
 * <p>This class is instantiated in {@link SyncService}, which also binds SyncAdapter to the system.
 * SyncAdapter should only be initialized in SyncService, never anywhere else.
 *
 * <p>The system calls onPerformSync() via an RPC call through the IBinder object supplied by
 * SyncService.
 */
class SyncAdapter extends AbstractThreadedSyncAdapter {
    public static final String TAG = SyncAdapter.class.getSimpleName();

    public static final String ARGS_MODULE_ID = "arg_module_id";

    /**
     * Content resolver, for performing database operations.
     */
    private final ContentResolver mContentResolver;

    /**
     * Constructor. Obtains handle to content resolver for later use.
     */
    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContentResolver = context.getContentResolver();
        Log.i("NJW", "construct syncadapter");
    }

    /**
     * Constructor. Obtains handle to content resolver for later use.
     */
    public SyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        Log.i("NJW", "constructs sycadapter; allowparallelsyncs:" + allowParallelSyncs);
        mContentResolver = context.getContentResolver();
    }

    /**
     * Called by the Android system in response to a request to run the sync adapter. The work
     * required to read data from the network, parse it, and store it in the content provider is
     * done here. Extending AbstractThreadedSyncAdapter ensures that all methods within SyncAdapter
     * run on a background thread. For this reason, blocking I/O and other long-running tasks can be
     * run <em>in situ</em>, and you don't have to set up a separate thread for them.
     .
     *
     * <p>This is where we actually perform any work required to perform a sync.
     * {@link AbstractThreadedSyncAdapter} guarantees that this will be called on a non-UI thread,
     * so it is safe to peform blocking I/O here.
     *
     * <p>The syncResult argument allows you to pass information back to the method that triggered
     * the sync.
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        Log.i("NJW", "Beginning network synchronization");

        // TODO: Fix magic #s, but they correspond to values in MainActivity.getModuleId() and the query parameters of the feeds
        // Download all feeds
        if (extras == null) {
            Log.e(TAG, "extra=null");
            return;
        }

        if (extras.getString(ARGS_MODULE_ID) != null) {
            String moduleId = extras.getString(ARGS_MODULE_ID);
            Log.i(TAG, "-->Syncing:" + moduleId);
            syncDownloadFeed(syncResult, moduleId);
        } else {
            boolean setupComplete = PreferenceManager
                    .getDefaultSharedPreferences(this.getContext()).getBoolean(PREF_SETUP_COMPLETE, false);

            if (setupComplete) {
                Log.e(TAG, "-->Sync all feeds!");
                syncAllFeeds(syncResult);
            } else {
                PreferenceManager.getDefaultSharedPreferences(this.getContext()).edit()
                        .putBoolean(PREF_SETUP_COMPLETE, true).commit();
                Log.e(TAG, "-->Sync all feeds next time... (marking setup complete)");
            }

        }

        Log.i(TAG+"OPS", "Network synchronization complete");
    }

    private void syncAllFeeds(SyncResult syncResult) {
        Log.e(TAG, "********Syncing / downloading second");
        //TODO: Download all of them...
        //syncDownloadFeed(syncResult, "1");
        syncDownloadFeed(syncResult, "2");
        //TODO: Figure out how to get an id... perhpas teh id in the permalink

    }

    private void syncDownloadFeed(SyncResult syncResult, String page) {
        Call<ArticleResponse> call = getCall(page);
        try {
            Log.i("NJW", "---About to execute call for page:" + page);
            Response<ArticleResponse> feedResponse = call.execute();
            ArticleResponse feed = feedResponse.body();
            Log.i("NJW", "got: feed with items size:" + feed.channel.items.size());

            updateLocalFeedData(page,feed, syncResult);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }
    }


    public void updateLocalFeedData(String moduleId, ArticleResponse feed, final SyncResult syncResult)
            throws IOException, XmlPullParserException, RemoteException,
            OperationApplicationException, ParseException {


        List<Article> articles = Article.getArticles(moduleId, feed);

        Log.i(TAG, "***ModuleID:"  + moduleId + " Parsing complete. " + articles.size() + " articles");

        ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();

        // Build hash table of incoming articles
        HashMap<String, Article> entryMap = new HashMap<String, Article>();
        for (Article e : articles) {
            entryMap.put(e.getId(), e);
        }

        // Add new items
        for (Article e : entryMap.values()) {
            batch.add(ContentProviderOperation.newInsert(FeedContract.Entry.CONTENT_URI)
                    .withValue(FeedContract.Entry.COLUMN_NAME_ARTICLE_ID, e.getId())
                    .withValue(FeedContract.Entry.COLUMN_NAME_MODULE_ID, e.getModuleId())
                    .withValue(FeedContract.Entry.COLUMN_NAME_TITLE, e.getTitle())
                    .withValue(FeedContract.Entry.COLUMN_NAME_IMAGE_LINK, e.getImageLink())
                    .withValue(FeedContract.Entry.COLUMN_NAME_FULL_TEXT, e.getFullText())
                    .withValue(FeedContract.Entry.COLUMN_NAME_AUTHOR, e.getAuthor())
                    .withValue(FeedContract.Entry.COLUMN_NAME_SUMMARY, e.getSummary())
                    .withValue(FeedContract.Entry.COLUMN_NAME_LINK, e.getLink())
                    .build());
            syncResult.stats.numInserts++;
        }

        mContentResolver.applyBatch(FeedContract.CONTENT_AUTHORITY, batch);
        mContentResolver.notifyChange(
                FeedContract.Entry.CONTENT_URI, // URI where data was modified
                null,                           // No local observer
                false);                         // IMPORTANT: Do not sync to network
    }


    /**
     * get call based on pageNum
     * @return
     */
    public Call<ArticleResponse> getCall(String pageNum) {
        //todo: figure out how to get logging in again.
        /*
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptor).build();

*/

        // https://jeaniesjourneys.com/feed/?paged=2
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(WordPressService.JEANIE_SHAW_BLOG_URL)
                .addConverterFactory(SimpleXmlConverterFactory.create())
                .build();

        WordPressService service = retrofit.create(WordPressService.class);

        Call<ArticleResponse> articleResponseCall = service.getFeed(pageNum);


        return articleResponseCall;

    }
}